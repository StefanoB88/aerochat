# Modifica e cancellazione dei messaggi (release 1.2) — contratto iOS ↔ Android

## Wire
- Nuovo `PacketType MESSAGE_REVISION = 14` in `aeromesh.proto`.
- `extra_data` = nuovo messaggio in `aeromesh_ext.proto`:

```proto
enum RevisionAction {
  REVISION_ACTION_UNSPECIFIED = 0;
  EDIT = 1;    // nuovo testo per un CHAT_TEXT / PUBLIC_LOUNGE
  DELETE = 2;  // cancellazione per tutti (testo, foto, nota vocale)
}

message MessageRevision {
  bytes target_msg_id = 1;   // 16 byte: msg_id del messaggio originale
  RevisionAction action = 2;
  string new_text = 3;       // solo EDIT, 1..4000 caratteri dopo trim
  int64 revised_at_ms = 4;   // istante della modifica (Unix ms)
}
```

- Inviato nella **stessa conversazione** del messaggio originale, con la stessa chiave e lo stesso
  `recipient_id`: 1-a-1 (diretto), gruppo (multicast), Cabina Pubblica (chiave pubblica della Lounge).
- `isStoreAndForwardEligible = true`. In 1-a-1 e gruppi il mittente lo tiene in coda fino al
  `DELIVERY_ACK` (come `CHAT_TEXT`, `retainUntilAck`); il ricevente risponde con `DELIVERY_ACK` e
  ri-conferma i duplicati. Nella Lounge niente ACK (come i testi della Lounge).
- I client che non conoscono il tipo 14 lo ignorano (nessuna rottura).

## Regole (identiche sui due lati, applicate sia in invio sia in ricezione)
- Finestra: `revised_at_ms - original.created_at_ms <= 15 min` (900 000 ms). In ricezione tolleranza di
  +2 min per orologi non allineati. Fuori finestra: il pacchetto viene scartato.
- Solo l'autore: `envelope.sender_node_id == original.sender_node_id` (e il messaggio locale e' in uscita
  quando lo si modifica dalla UI). Mittenti bloccati: ignorati come sempre.
- EDIT solo su messaggi di testo non cancellati; il testo passa dal filtro linguaggio nella Lounge
  (rifiuto in invio, mascheramento in ricezione) e niente link nella Lounge.
- DELETE su testo, foto e note vocali (non sulle bolle di gioco ne' sui messaggi di sistema).
- Revisione che arriva prima del messaggio originale: tenuta in memoria fino a 64 voci, applicata quando
  l'originale arriva (o dimenticata dopo 15 min).
- Piu' EDIT: vince quello con `revised_at_ms` maggiore. DELETE e' definitivo (un EDIT successivo e' ignorato).

## Database e UI
- `messages.edited_at_ms` (INTEGER NULL) e `messages.is_deleted` (BOOL NOT NULL DEFAULT 0).
- EDIT: aggiorna `text` e `edited_at_ms`; la bolla mostra "modificato" accanto all'ora.
- DELETE: `is_deleted = 1`, `text = NULL`, media su disco rimosso, `media_local_path = NULL`, blurhash e
  waveform azzerati; la bolla mostra in corsivo "Messaggio eliminato" (mittente: "Hai eliminato questo
  messaggio"). Niente risposta/segnala/copia su un messaggio eliminato.
- Menu sul proprio messaggio (entro 15 min, conversazione non scaduta): "Modifica" (solo testo) ed
  "Elimina per tutti" (rosso, con conferma). Dopo 15 min le voci spariscono.
- Modifica: il testo torna nella barra di scrittura con una striscia "Modifica messaggio" e una X per
  annullare; l'invio manda la revisione invece di un nuovo messaggio.
- Nessuna notifica per le revisioni; un messaggio eliminato non conta piu' come non letto.
