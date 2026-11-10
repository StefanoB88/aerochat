# AeroMesh protocol additions – release 1.1

> Stato (2026-11-10): implementato su iOS **e Android**. Vettore cross-platform della Cabina del volo per
> `AZ|204|2026-11-10`: key `021f3ac145f067d79218faaf1d2186661e4b6fb5c89c1c450b93e05db2033202`,
> UUID `4e5ed36a-a223-4e34-a5c9-c68ebaf63dc8`, recipient_id `b9070b8b2b885f61` (testato su entrambe).
>
> Differenze Android: niente riassunto AI della Cabina; traduzione e risposte rapide con ML Kit on-device
> (risposte rapide solo in inglese); la "Live Activity" e' la notifica del servizio in primo piano.

Tutte le modifiche sono **retrocompatibili**: i client 1.0 ignorano i `PacketType` sconosciuti e i
nuovi campi. Il contratto binario resta quello di `aeromesh.proto` / `aeromesh_ext.proto` (aggiornati in
`ios/AeroChat/Core/Protocol/`); copiare i due file anche in `android/app/src/main/proto/`.

## 1. Nuovi `PacketType`

| Valore | Nome | Destinatario / chiave | Coda S&F | `extra_data` |
|---|---|---|---|---|
| 12 | `QUIZ` | `recipient_id` = 8 zeri (Lounge), chiave `SHA256("AeroMesh-Public-Cabin-v1")` | no | `QuizPacket` |
| 13 | `EMERGENCY` | come sopra, `ttl` = 7 (massimo) | **sì** | `EmergencyBroadcast` |

Il router li tratta come la Lounge: multicast (flooding), autenticazione del mittente tramite le chiavi
dichiarate in `InnerPayload` (`sender_x25519_pub` → `node_id`), nessun ACK. Un mittente bloccato non viene
ne' consegnato ne' ritrasmesso (vale ora per **tutti** i pacchetti, anche 1.0).

### QuizPacket (quiz di cabina)

- L'**host** e' l'unica autorita': i partecipanti accettano `QUESTION`/`REVEAL`/`END` solo se
  `sender_node_id == host_node_id` e, dopo la prima domanda, solo per lo stesso `quiz_id`.
- `QUESTION`: `round` (1-based), `total_rounds`, `question` (≤300 char), `options` (2..4, ≤80 char),
  `deadline_ms` (Unix ms). Durata domanda 20 s.
- `ANSWER`: inviato dal partecipante in multicast; solo l'host lo conta (prima risposta per `node_id`,
  entro `deadline_ms + 2000`). Gli altri lo ignorano.
- `REVEAL`: `correct_index` + `scores` (classifica, max 32 voci). Punti: 100 per risposta giusta +
  `floor(remaining_ms / 20000 * 50)` di bonus velocita'. Pausa 6 s, poi la domanda successiva.
- `END`: classifica finale.
- Validazioni lato ricevente: `quiz_id` non vuoto e ≤64 char, `host_node_id` di 8 byte.

### EmergencyBroadcast

- `status`: `OK` (1), `NEED_HELP` (2), `ALL_CLEAR` (3); `UNSPECIFIED` viene scartato.
- `text` ≤ 280 caratteri; `latitude`/`longitude` validi solo se `has_location` (range controllato).
- Il ricevente salva un messaggio `system` nella Cabina Pubblica con
  `"<emoji> <nickname>: <titolo stato> — <text> — 📍 lat, lon"` (vedi `EmergencyService.systemText`),
  mostra un banner prioritario e una notifica *time sensitive*. `msg_id` dell'envelope = `msg_id` del messaggio.

## 2. Cabina del volo (nessuna modifica wire)

Gruppo derivato dalla carta d'imbarco (IATA BCBP) o inserito a mano, uguale per tutti i passeggeri:

```
canonical = "<CARRIER>|<FLIGHT_NUMBER senza zeri iniziali>|<yyyy-MM-dd giorno di partenza, UTC>"
            es. "AZ|204|2026-11-10"
group_secret_key = SHA256("AeroMesh-Flight-Key-v1|" + canonical)              // 32 byte
uuid_bytes       = SHA256("AeroMesh-Flight-UUID-v1|" + canonical)[0..16]
uuid_bytes[6] = (uuid_bytes[6] & 0x0F) | 0x40 ; uuid_bytes[8] = (uuid_bytes[8] & 0x3F) | 0x80   // UUID v4 valido
group_uuid       = UUID(uuid_bytes) ; recipient_id = SHA256(uuid_bytes)[0..8] (come ogni gruppo)
```

- Parsing BCBP (primo segmento): `[30..33]` partenza, `[33..36]` arrivo, `[36..39]` compagnia (trim),
  `[39..44]` numero volo (trim di zeri e spazi), `[44..47]` giorno giuliano → anno piu' vicino a oggi.
- Titolo locale: `"AZ 204 · FCO → LHR · 10 nov"`; scadenza locale: mezzanotte UTC del giorno + 36 h.
- Il testo nella Cabina del volo e' **gratuito** per tutti (`FeatureGate`: `groupChat` sempre `allowed`
  se `flight_code != null`); foto/vocali/giochi seguono le regole Pro. Nessun gossip necessario: i membri si
  registrano quando ricevono un testo del gruppo (gia' cosi' in 1.0).
- Su iOS la riga `conversations` ha la colonna `flight_code` (migrazione v7); Android puo' scegliere
  la propria rappresentazione.

## 3. Pass Volo Singolo

- Nuovo prodotto `com.aeromesh.pass24h` (24 h). `ProPassGrant.product_id` puo' valere anche questo id;
  il grant resta limitato a `min(72 h, scadenza del pass di chi lo emette)`, come prima.

## 4. Link di invito (`aeromesh://`)

- L'app iOS registra lo schema URL `aeromesh`; aprendo un link `aeromesh://v1?...` (stesso formato del QR)
  da Messaggi/AirDrop si esegue il pairing. Android dovrebbe dichiarare lo stesso intent-filter.
- Testo dell'invito: link allo store + link `aeromesh://` di pairing (vedi `QRPairingSheet.inviteText`).

## 5. Moderazione (comportamento locale, nessun wire)

- Filtro linguaggio in Cabina (`ContentFilter`): rifiuto in invio, mascheramento in ricezione.
- Segnalazioni salvate in locale (`reports`) e inoltrabili via email a `safety@aeromesh.app`.
- Blocco: niente consegna e **niente relay** dei pacchetti del nodo bloccato.
- Foto da mittenti non `is_trusted` (mai scansionati via QR) mostrate sfocate finche' toccate.

## 6. GAME_MOVE affidabile (implementato su iOS e Android il 2026-11-10)

- Il ricevente di un `GAME_MOVE` (diretto o di gruppo) risponde con `DELIVERY_ACK` come per `CHAT_TEXT`
  (`reply_to_msg_id` = msg_id della mossa). I duplicati di un msg_id gia' visto da un peer noto vengono
  ri-confermati (throttle 15 s), anche senza messaggio salvato (le mosse non hanno una riga propria).
- Il mittente tiene la mossa in `relay_queue` finche' non arriva l'ACK (ritrasmissione ogni 30 s ai vicini
  diretti / all'apertura di un link, per 6 h), come i testi. Prima una mossa persa bloccava la partita.
- Android: `inboundPackets(GAME_MOVE)` dedicato (i chunk media non svuotano piu' la coda delle mosse), il
  servizio giochi si iscrive prima dell'avvio della radio, rollback dello stato se l'invio non parte,
  `resume(gameID)` all'apertura del tavolo ritenta il DEAL mancante.

## 7. RSSI

- iOS ora legge l'RSSI dei link aperti ogni 4 s (`readRSSI`) per il radar di prossimita'. Nessun wire.
  Android: `BluetoothGatt.readRemoteRssi()` con la stessa cadenza, opzionale.

## 1.2 — Modifica e cancellazione dei messaggi (`MESSAGE_REVISION = 14`)

Contratto completo in `MESSAGE_REVISIONS_SPEC.md`. In sintesi:
- `extra_data = MessageRevision { target_msg_id, action (EDIT=1 | DELETE=2), new_text, revised_at_ms }`.
- Viaggia nella stessa conversazione dell'originale (diretto, gruppo, Lounge), store-and-forward;
  ACK + retry in diretto e gruppo, niente ACK in Lounge.
- Accettata solo dall'autore e entro 15 minuti dall'invio (+2 min di tolleranza in ricezione);
  l'EDIT piu' recente vince, il DELETE e' definitivo; revisioni in anticipo sull'originale tenute
  in un buffer di 64 voci.
- DB: `messages.edited_at_ms`, `messages.is_deleted` (iOS migrazione `v8_message_revisions`, Android Room v4).
- Il badge dei non letti (icona app e tab "Chat & Gruppi") esclude la Cabina Pubblica.
