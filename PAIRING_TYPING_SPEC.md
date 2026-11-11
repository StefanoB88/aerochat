# Release 1.2 — Chat dopo il QR (PAIR_HELLO) e "sta scrivendo" (TYPING) — contratto iOS ↔ Android

## 1. Token di abbinamento nel QR
- Il QR personale (e i link d'invito `aeromesh://`, stesso formato, e i QR di invito ai gruppi) porta un
  parametro opzionale `pt=<base64url senza padding di 16 byte casuali>`.
- Il token viene generato quando si mostra il proprio QR / si crea un link d'invito e salvato in locale
  (iOS: Keychain o DB; Android: DB/EncryptedSharedPreferences) con scadenza **7 giorni**; si tengono al
  massimo **16** token validi (i piu' vecchi vengono scartati). Lo stesso token resta nel QR finche' la
  scheda e' aperta (non rigenerarlo a ogni ridisegno).
- I parser devono ignorare parametri sconosciuti; un QR senza `pt` funziona come prima (nessun PAIR_HELLO).

## 2. PAIR_HELLO = 15
- Inviato da chi **scansiona** (B) a chi ha **mostrato** il QR (A), subito dopo il pairing, se il QR aveva `pt`.
- Envelope: `type = PAIR_HELLO`, `recipient_id` = node_id di A, `sender_node_id` = node_id di B, ttl di
  default, firmato come sempre (Ed25519 di B).
- Chiave di cifratura del payload (ChaCha20-Poly1305, come gli altri pacchetti):
  `key = HKDF-SHA256(IKM = token(16 byte), salt = "AeroMesh-Salt-v1", info = "AeroMesh-PairHello-v1", 32 byte)`.
- `InnerPayload` con i campi del mittente dichiarato gia' usati da gruppi/Lounge: `sender_x25519_pub`,
  `sender_ed25519_pub`, `sender_nickname`; `extra_data` vuoto.
- Ricezione su A: per ogni token valido prova a decifrare; se nessuno funziona scarta. Poi verifica:
  firma Ed25519 con `sender_ed25519_pub` dichiarata; `SHA256(sender_x25519_pub)[0..<8] == sender_node_id`
  (stessa derivazione del node_id); il mittente non e' bloccato. Se ok: upsert del peer come **fidato** (non
  declassare/sovrascrivere un nickname gia' impostato da PROFILE_UPDATE), `ensureDirectConversation`, invio
  del proprio PROFILE_UPDATE a B e `DELIVERY_ACK` a B (con la normale chiave diretta, ora che B e' noto).
  Nessuna notifica di messaggio; la chat vuota compare in cima alla lista (`last_message_at_ms` = adesso).
  Il token **non** viene consumato (lo stesso QR puo' essere scansionato da piu' persone finche' valido).
- Mittente: store-and-forward con `retainUntilAck` (come CHAT_TEXT) finche' arriva il DELIVERY_ACK; i
  duplicati vengono ri-confermati.
- Anche su B la chat vuota compare subito in lista (oggi gia' creata da `pair`): verificare che la lista
  mostri le conversazioni senza messaggi, ordinate per `last_message_at_ms` (impostarlo all'abbinamento).
- Invito a un gruppo con `pt`: prima PAIR_HELLO, poi il GroupKeyGossip (che A ora sa decifrare).

## 3. TYPING = 16
- Diretto (recipient = node_id del peer, chiave diretta) o di gruppo (recipient_id del gruppo, chiave del
  gruppo, mittente dichiarato come gli altri pacchetti di gruppo). **Mai** nella Cabina Pubblica ne' nella
  Cabina del volo.
- `extra_data = TypingIndicator { bool is_typing = 1; }` in `aeromesh_ext.proto`.
- Non store-and-forward, niente ACK, niente DB, niente notifiche; ttl 2 (diretto e gruppo: il "sta
  scrivendo" ha senso solo vicino); i relay lo inoltrano come gli altri pacchetti (rispettando il ttl).
- Invio: quando il testo nella barra diventa non vuoto -> `true`; poi ripetuto al massimo ogni **4 s** finche'
  si continua a scrivere; `false` quando il testo torna vuoto, quando si invia, quando si esce dalla chat
  o l'app va in background, o dopo **5 s** senza battiture. Non inviare se la chat e' scaduta/bloccata.
- Ricezione: stato in memoria per conversazione+mittente; scade da solo **6 s** dopo l'ultimo `true`;
  `false` o un messaggio in arrivo dallo stesso mittente lo azzerano subito.
- UI: nell'intestazione della chat al posto del sottotitolo "sta scrivendo…" (gruppo: "Marco sta
  scrivendo…", piu' persone: "%d persone stanno scrivendo…"); nella lista chat la riga mostra
  "sta scrivendo…" (colore accento, corsivo) al posto dell'anteprima dell'ultimo messaggio.

## 4. Conferme di eliminazione ancorate
- iOS: `confirmationDialog` di "Eliminare la chat?" (lista chat) ed "Eliminare il messaggio per tutti?"
  (chat e Cabina) agganciati alla riga / alla bolla interessata, cosi' su iOS 26 il popover parte da li'.
- Android: stessa resa — conferma ancorata alla riga/bolla (popup tipo `AeroDropdownMenu` con titolo,
  testo, voce rossa di conferma e "Annulla"), non piu' `AlertDialog` al centro dello schermo.
