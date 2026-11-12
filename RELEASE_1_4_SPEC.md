# Release 1.4 — Crescita, cabina sociale, affidabilita' — contratto iOS ↔ Android

> Stato (2026-11-12): implementato su Android e su iOS (sezioni A e B, B15 Live Activity inclusa). Tutte le modifiche wire sono retrocompatibili:
> i client precedenti ignorano il campo nuovo di `Presence` e il `PacketType` 20.

Ogni testo visibile va in italiano (lingua sorgente) + le 10 lingue esistenti (en, de, fr, es, pt-PT, nl,
pl, sv, da, nb). Su iOS `Localizable.xcstrings`, su Android `res/values*/strings_growth14.xml`.

---

## A. Wire

### A1. `Presence.seat` (campo 5, opzionale)

```proto
message Presence {
  ...
  string seat = 5;   // posto del passeggero, es. "14C"; vuoto = non condiviso
}
```

- Inviato **solo** se l'utente ha attivato "Mostra il mio posto ai vicini" (default **spento**) e conosce il
  proprio posto (letto dalla carta d'imbarco o inserito a mano).
- Validazione (in invio e in ricezione): trim + maiuscole, regex `^[0-9]{1,3}[A-K]$`; altrimenti vuoto/ignorato.
- Il posto vale solo nella Cabina dello stesso volo: il ricevente lo mostra solo se `flight_code` del
  PRESENCE e' uguale al proprio volo attivo.

### A2. `BOARD_POST = 20` — bacheca della cabina

```proto
enum PacketType { ... BOARD_POST = 20; }   // recipient = Lounge (8 zeri), chiave della Lounge, ttl 3, mai in coda/DB

enum BoardKind {
  BOARD_KIND_UNSPECIFIED = 0;
  BOARD_KIND_LOOKING_FOR = 1;  // "Cerco"      (es. "un caricatore USB-C")
  BOARD_KIND_OFFERING = 2;     // "Offro"      (es. "snack, cuffie in prestito")
  BOARD_KIND_FOUND = 3;        // "Trovato"    (oggetti smarriti)
  BOARD_KIND_SEAT_SWAP = 4;    // "Scambio posto"
}

message BoardPost {
  bytes post_id = 1;        // 16 byte casuali, scelti dall'autore
  BoardKind kind = 2;
  string text = 3;          // 1..140 caratteri dopo trim, niente link (scartato), parolacce mascherate dal ricevente se filtro attivo
  string flight_code = 4;   // volo attivo dell'autore (canonico) o vuoto
  string seat = 5;          // facoltativo, stesse regole di Presence.seat
  int64 created_at_ms = 6;
  bool is_closed = 7;       // l'autore ha chiuso l'annuncio: i riceventi lo tolgono
}
```

- Envelope come PRESENCE (InnerPayload con chiavi dichiarate, mittente = node_id derivato dalla X25519),
  ma `ttl = 3` e **relay consentito** (flooding normale della Lounge, niente coda store-and-forward).
- Ogni nuovo `msg_id` per ogni ritrasmissione: l'autore ripete i propri annunci aperti ogni **60 s** (stesso
  giro del PRESENCE, solo in primo piano) cosi' chi arriva dopo li vede. Dedup lato ricevente per `post_id`.
- Autore: massimo **3** annunci aperti; scadono da soli **6 h** dopo `created_at_ms`. Chiudere = un ultimo
  invio con `is_closed = true`.
- Ricevente: cache in memoria (mai nel DB), max **50** annunci, voce rimossa se non ripetuta da **10 min**,
  se `is_closed`, se mittente bloccato, o se `created_at_ms` piu' vecchio di 6 h. Annunci con `flight_code`
  diverso dal proprio volo attivo (entrambi non vuoti) vengono ignorati.
- Rispondere a un annuncio = "Saluta" (CONTACT_REQUEST REQUEST) all'autore, con saluto precompilato
  (`"Per il tuo annuncio: <testo troncato>"`, ≤140). Le chiavi dell'autore vengono dalla cache della bacheca
  come per la cache PRESENCE.
- Rate limit ricezione: al massimo 10 annunci distinti per mittente nella cache.

## B. Funzioni locali (nessun wire)

### B1. Scambio posto e mappa della cabina
- "Il mio posto": dalla carta d'imbarco (campo posto BCBP) salvato con il volo, modificabile a mano.
  Impostazione "Mostra il mio posto ai vicini" (default spenta) nella schermata Persone vicine.
- Mappa dei posti (in "Persone vicine", segmento "Mappa"): righe per numero di fila crescente, colonne per
  lettera; mostra i vicini del proprio volo con posto noto + noi evidenziati. Tocco su un passeggero:
  "Saluta" oppure "Proponi scambio posto" = CONTACT_REQUEST con saluto
  `"Ti va di scambiare il posto? Io sono al <mio posto>."` (o senza la seconda frase se il posto non e' noto).
- Annuncio rapido "Scambio posto" in bacheca (kind SEAT_SWAP) con il proprio posto.

### B2. Prima partita gratis
- Oltre al Pass: **una partita gratis al giorno e una per ogni volo** (chi la avvia; accettare e' sempre gratis).
  Chiave di consumo: `flight:<flight_code>` se c'e' un volo attivo, altrimenti `day:<yyyy-MM-dd>` (fuso locale).
  Si consuma quando l'invito parte. Se gia' consumata: paywall come oggi. Nella chat con il passeggero
  virtuale i giochi restano sempre liberi.

### B3. Pass del gruppo visibile
- Alla ricezione di un `PRO_PASS_GRANT` che **estende** lo sblocco: messaggio di sistema nella conversazione
  `"🎁 <nickname> ha sbloccato il Pass per tutto il gruppo fino alle <ora>"` (ora locale breve).

### B4. Premio inviti
- Contatti nuovi arrivati con il nostro QR/link (PAIR_HELLO ricevuto da node_id mai visti): al **3°** distinto
  si sblocca **una volta per installazione** un Pass locale di **24 h** (solo per uso personale: non
  autorizza a regalare il Pass ai gruppi, che resta legato all'acquisto reale). Contatore visibile nel paywall
  e in "Invita amici": "2 su 3 amici invitati".

### B5. Richiesta di recensione
- iOS `requestReview`, Android Play In-App Review. Momenti: cartolina di fine volo mostrata, partita vinta.
  Condizioni: almeno 3 giorni dalla prima apertura, almeno 5 aperture, al massimo una richiesta ogni 90 giorni.

### B6. "Perché non vedo nessuno?"
- Schermata di diagnosi raggiungibile da: Persone vicine vuota, radar vuoto, home "Nessuno vicino per ora".
  Controlli con esito ✓/✗ e azione: Bluetooth acceso (✗ → spiegazione modalita' aereo), permesso Bluetooth
  (✗ → richiesta/Impostazioni), notifiche (avviso), "Visibile alle persone vicine" (✗ → attiva), app
  aperta sull'altro telefono (consiglio), distanza (< 30 m, pochi sedili in mezzo), entrambi con AeroChat
  aggiornato. Pulsante "Riavvia la radio" (stop/start della mesh). Pulsante "Prova col passeggero virtuale".

### B7. Promemoria prima del volo
- Aggiungendo una carta d'imbarco con giorno di partenza **futuro**: notifica locale alle **07:00** del giorno
  di partenza (se gia' passate e il giorno e' oggi: nessuna). Testo: "Oggi voli con <AZ204>: invita ora chi
  viaggia con te, in aereo non potra' scaricare AeroChat." Tocco → foglio "Invita amici".
- Subito dopo l'aggiunta della carta: azione "Invita chi viaggia con te" nel foglio di conferma.

### B8. Cartolina condivisibile con QR
- La cartolina (formato Storie 1080x1920) mostra in basso un QR verso `https://aeromesh.app/get` con la
  scritta "Scarica AeroChat".

### B9. Passaporto di viaggio
- Statistiche cumulative persistite (non solo gli ultimi 10 voli): voli, passeggeri incontrati, messaggi,
  partite giocate/vinte, record di telefoni in catena. Aggiornate alla fine di ogni volo.
- Badge (sbloccati quando la soglia e' raggiunta, con data): primo volo, 5 voli, 10 voli, 10 passeggeri
  incontrati, 50 passeggeri incontrati, prima vittoria, 10 vittorie, 100 messaggi senza internet.
- Schermata "Passaporto" (Impostazioni e cartolina) con badge e numeri; condivisione come immagine Storie.

### B10. Modalita' viaggio
- Selettore nella card della schermata principale (a terra): **Volo**, **Nave**, **Festival**, **Montagna**,
  **Estero**. Salvato in impostazioni. Cambia titolo, sottotitolo e due azioni:
  - Volo: come oggi (Invita amici, Crea gruppo di viaggio).
  - Nave: "Crea il gruppo della crociera", "Persone vicine".
  - Festival: "Crea il gruppo degli amici", "Radar: ritrova gli amici".
  - Montagna: "Crea il gruppo dell'escursione", "Condividi la tua posizione" (apre un gruppo/chat).
  - Estero: "Persone vicine", "Traduzione offline" (spiegazione: tieni premuto un messaggio → Traduci).

### B11. Condividi posizione in chat
- Nel menu allegati di chat 1-a-1 e gruppi (non in Cabina Pubblica/Lounge): "Posizione". Una lettura GPS
  e invio come **CHAT_TEXT** normale: `"📍 <lat 5 decimali>, <lon 5 decimali> (±<accuratezza> m)"`.
  Le bolle che contengono questo formato mostrano il pulsante "Apri in Mappe" (iOS Apple Maps `maps://?ll=`,
  Android `geo:lat,lon?q=lat,lon`). Permesso posizione con il flusso standard (richiesta / Impostazioni).

### B12. Sicurezza
- **Emergenza nella Cabina del volo** (volo attivo o modalita' aereo probabile): "Ho bisogno di aiuto" richiede
  testo di almeno 10 caratteri e una conferma esplicita ("Avvisa prima l'equipaggio: usa il pulsante di
  chiamata sopra il tuo posto. Inviare l'avviso a tutta la cabina?"); al massimo un avviso ogni 10 minuti
  per qualunque stato. Banner nel foglio: "In volo l'equipaggio e' il primo riferimento."
- "Visibile alle persone vicine" anche come interruttore nella schermata Persone vicine.

### B13. Codici promozionali
- iOS: "Hai un codice?" nel paywall → foglio di sistema di riscatto codici offerta.
- Android: "Hai un codice?" → campo testo → apre `https://play.google.com/redeem?code=<codice>`.

### B14. Installare in aereo (Android)
- Nel foglio "Fai scaricare l'app al gate": sezione "Gia' in volo?" — su Android l'app si passa senza internet
  con **Quick Share** (Play Store → profilo → Gestisci app e dispositivo → Condividi app); su iPhone non e'
  possibile: va installata prima dell'imbarco.

### B15. Live Activity meno invadente (solo iOS)
- La Live Activity "A bordo" parte solo **durante un volo** (Cabina del volo attiva o modalita' aereo
  probabile), mai a terra; impostazione "Attivita' in tempo reale" (default **attiva**) per spegnerla.
- Si chiude quando l'app viene chiusa (terminazione), quando l'app torna in primo piano dopo l'atterraggio,
  e da sola dopo 10 min senza vicini e senza non letti. `staleDate` 10 min.
- Android: il canale della notifica del servizio in primo piano diventa a importanza **minima** (silenziosa,
  senza icona nella barra di stato dove il sistema lo consente).
