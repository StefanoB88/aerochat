# Release 1.3 — Persone vicine, "Saluta" e "Sfida" — contratto iOS ↔ Android

## 1. PRESENCE = 17 (chi e' vicino)
- Cifrato con la chiave della Cabina Pubblica (`SHA256("AeroMesh-Public-Cabin-v1")`), `recipient_id` = 8 zeri,
  **ttl 1** (solo vicini diretti), mai store-and-forward, mai salvato nel DB, mai notificato.
- `InnerPayload` con il mittente dichiarato (`sender_x25519_pub`, `sender_ed25519_pub`, `sender_nickname`) come
  i pacchetti di Lounge; `extra_data = Presence` (aeromesh_ext.proto):

```proto
message Presence {
  string avatar_color_hex = 1;  // es. "00E5FF"
  string flight_code = 2;       // canonico "CARRIER|FLIGHT|yyyy-MM-dd" se nella Cabina di un volo, altrimenti vuoto
  bool open_to_games = 3;       // accetta sfide a carte
  int64 sent_at_ms = 4;
}
```

- Invio: all'apertura di ogni link e poi ogni **60 s** solo con app in primo piano, se l'impostazione
  "Visibile alle persone vicine" e' attiva (default: attiva). Spegnendola si smette di inviare (e di
  comparire); si continua a vedere gli altri.
- Ricezione: `openShared(lounge key)` + controllo `SHA256(sender_x25519_pub)[0..<8] == sender_node_id`; mittenti
  bloccati ignorati. Cache in memoria per node_id: chiavi, nickname (filtro linguaggio), colore, volo, RSSI
  dal trasporto se disponibile, `last_seen`. Scade **3 min** dopo l'ultimo PRESENCE. Max 64 voci.
- UI "Persone vicine": lista ordinata per segnale/ultimo visto, con avatar a colore + nickname, "Sul tuo volo"
  se `flight_code` coincide, stato "Gia' nei contatti" per peer gia' presenti, pulsanti **Saluta** e
  **Sfida** (se `open_to_games`).

## 2. CONTACT_REQUEST = 18 ("Saluta" / "Sfida")
- Pacchetto diretto: `recipient_id` = node_id del destinatario, cifrato con la **normale chiave diretta**
  (HKDF(ECDH) con salt "AeroMesh-Salt-v1", info "AeroMesh-Msg-v1") calcolata con la `x25519` presa dal
  PRESENCE (o dal DB se gia' noto). `InnerPayload` con mittente dichiarato. **ttl 2**, niente store-and-
  forward, niente ACK di consegna (la risposta e' un altro CONTACT_REQUEST).
- `extra_data = ContactRequest`:

```proto
enum ContactAction {
  CONTACT_ACTION_UNSPECIFIED = 0;
  REQUEST = 1;
  ACCEPT = 2;
  DECLINE = 3;
}

message ContactRequest {
  ContactAction action = 1;
  bytes request_id = 2;     // 16 byte casuali scelti da chi chiede; ACCEPT/DECLINE lo ripetono
  string greeting = 3;      // solo REQUEST, opzionale, <= 140 caratteri (filtro linguaggio, niente link)
  int32 game_kind = 4;      // solo REQUEST: 0 = semplice "Saluta"; altrimenti GameKind (SCOPA, BRISCOLA...) = "Sfida"
}
```

- Ricezione: il destinatario deve conoscere le chiavi del mittente (DB o cache PRESENCE), altrimenti
  scarta; verifica firma con la ed25519 nota e che le chiavi dichiarate coincidano. Bloccati ignorati.
  Limite: una REQUEST per mittente ogni **10 min** (le altre scartate); max 20 richieste pendenti.
- REQUEST ricevuta: entra in "Richieste" (in memoria, scade dopo **30 min**) + notifica locale ("Marco ti
  saluta" / "Marco ti sfida a Scopa") + banner in app. Azioni: **Accetta** / **Rifiuta** (DECLINE) / ignora.
- ACCEPT inviato o ricevuto (con request_id valido): entrambi fanno upsert del peer come **fidato** (stesso
  livello del QR, non sovrascrivere un nickname da PROFILE_UPDATE), `ensureDirectConversation` in cima,
  `sendProfile`, `refreshTrustedPeers`. Se la REQUEST aveva `game_kind != 0`, **chi ha chiesto** (il
  ricevente dell'ACCEPT) invia subito l'invito di gioco gia' accettato nella nuova chat (flusso esistente
  di invito + accettazione automatica lato chi ha accettato la sfida: l'ACCEPT vale come consenso).
- DECLINE: chi ha chiesto vede "Richiesta rifiutata" (nessun altro effetto).
- Peer gia' nei contatti: "Saluta" apre direttamente la chat; "Sfida" invia l'invito di gioco normale.

## 3. Cose solo locali (nessun wire)
- Ricordo del viaggio, classifica del volo (partite vinte durante il volo), testi di consegna "umani",
  schermata principale contestuale, modalita' prova col passeggero virtuale: vedi README delle due app.

## 4. RADAR_PING = 19 (radar di prossimita' piu' preciso)

> Stato (2026-11-11): implementato su iOS. Android lo ignora (PacketType sconosciuto) senza problemi;
> per parita' basta rispondere con l'RSSI (campo 2) e applicare lo stesso filtro/modello di distanza.

- Envelope: `recipient_id` = 8 zeri (Lounge), chiave della Lounge, `ttl` = 1, inviato con `send(toNode:)`
  **a un solo vicino diretto**, ogni 2 s finche' il radar e' a schermo e l'app in primo piano. Mai in coda,
  mai nel DB (gestito subito dopo la dedup in memoria, prima di `seen_packets`), niente ACK.
- `extra_data` = `RadarPing` (`aeromesh_ext.proto`):
  `target_node_id` (8 byte, il ricevente scarta se non e' il proprio), `rssi_dbm` (**sint32**, RSSI filtrato
  misurato dal mittente su quel link; 0 = non disponibile), `ni_discovery_token` (solo iPhone UWB:
  `NIDiscoveryToken` archiviato con `NSKeyedArchiver` secure coding, max 1024 byte), `is_active`
  (false = radar chiuso, il ricevente ferma la sessione UWB).
- Accettato solo con `hop_count == 0`, dal `node_id` gia' identificato su quel link, mittente non bloccato.
- RSSI remoto: chi e' peripheral non conosce l'RSSI del central collegato; usa quello del ping se non ne
  ha uno proprio, valido 8 s.
- RSSI locale: polling a 1 s con il radar aperto + filtro di Kalman 1D (rumore misura 16 dBm², processo
  2 dBm²/s, picchi > 15 dB pesati 4 volte meno). Distanza: `d = 10^((-59 - rssi) / (10 * 2.3))` m, limitata
  a 0,3…60 m, mostrata arrotondata ("≈ 3 m"). Radar in scala logaritmica 0,3 m (centro) … 30 m (bordo),
  anelli a 1, 5 e 30 m.
- UWB (iPhone 11+): una `NISession` per vicino; quando entrambi hanno il radar aperto e si sono scambiati
  i token, distanza al centimetro e direzione (se il chip la fornisce) sostituiscono la stima Bluetooth.
