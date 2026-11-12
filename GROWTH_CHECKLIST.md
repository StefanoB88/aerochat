# AeroChat – Checklist pre-lancio e crescita

> Da spuntare prima dell'uscita della 1.4. Riferimenti: `ios/APP_REVIEW_NOTES.md`, `RELEASE_1_4_SPEC.md`,
> `CREATOR_KIT.md`, `web/README.md`.

---

## 1. Test sul campo con 15-30 telefoni

### 1.1 Parco dispositivi

- [ ] Almeno 15 telefoni (ideale 30), circa metà iPhone e metà Android.
- [ ] iPhone: almeno un modello senza UWB (SE), un iPhone 11-13, un 15-17; la versione minima di iOS supportata
      (17) e l'ultima.
- [ ] Android: Samsung (One UI, gestione batteria aggressiva), Pixel, Xiaomi/Redmi o Oppo/Realme (kill delle app
      in background), un dispositivo economico con Bluetooth 4.2, la versione minima (Android 10) e l'ultima.
- [ ] Tutti con la stessa build (TestFlight / test interno Play), batteria carica al 100 % all'inizio, luminosità
      e impostazioni annotate.
- [ ] Un foglio di calcolo condiviso: una riga per telefono (modello, OS, build, ruolo nello scenario).

### 1.2 Scenari

| # | Scenario | Disposizione | Cosa verificare |
|---|---|---|---|
| S1 | Coppia vicina | 2 telefoni a 1, 5, 10, 20, 30 m, linea di vista | Tempo di scoperta, consegna testo/foto/vocale, chiamata |
| S2 | "Corridoio d'aereo" | 20-30 telefoni in fila ogni 0,8-1 m (simula 30 file), corpi in mezzo | Messaggio da testa a coda: hop, latenza, consegna; Cabina del volo; quiz |
| S3 | Folla da festival | 15-30 persone che si muovono in un'area di 50x50 m | Radar e freccia UWB, ritrovarsi, avviso di emergenza, consegna differita |
| S4 | Store-and-forward | A scrive a B fuori portata; C passa vicino ad A e poi a B | Consegna differita entro quanto tempo, nessun duplicato |
| S5 | Schermo bloccato / background | Metà dei telefoni bloccati in tasca per 30 min | Ricezione notifiche, relay attivo, riconnessione allo sblocco |
| S6 | Misto iOS ↔ Android | Gruppo di 6 (3+3) | Gruppi, foto WebP, vocali Opus, giochi (Scopa, Briscola, Scopone, Tris, Battaglia), Pass del gruppo (PRO_PASS_GRANT) |
| S7 | Cabina sociale 1.4 | 10+ telefoni con lo stesso volo | Persone vicine, posto condiviso, mappa, "Proponi scambio posto", bacheca (max 3 annunci, chiusura, scadenza) |
| S8 | Lunga durata | 10 telefoni, 3 h di uso realistico (volo medio) | Consumo batteria, temperatura, crash, memoria |
| S9 | Modalità aereo reale | Tutti in modalità aereo + Bluetooth | Nessuna dipendenza dalla rete; diagnosi "Perché non vedo nessuno?" |
| S10 | Installazione e invito | 5 persone nuove senza app | QR → `stefanob88.github.io/aerochatwebsite/get` → store → pairing: tempo totale e punti di attrito |
| S11 | Prova in volo vera | 3-6 persone su un volo commerciale (rispettando le regole della compagnia) | Tutto quanto sopra in condizioni reali; video per l'App Review e i social |

### 1.3 Metriche da registrare

| Metrica | Come si misura | Obiettivo indicativo |
|---|---|---|
| Tempo di scoperta | Da app aperta su entrambi a "vicino" visibile (cronometro o log) | Mediana < 10 s, p90 < 30 s a 10 m |
| Tasso di consegna | Messaggi con conferma di consegna / inviati (script: 50 messaggi numerati per telefono) | ≥ 98 % a 1 hop, ≥ 90 % a 3+ hop |
| Latenza | Invio → conferma di consegna | < 2 s a 1 hop, < 10 s a 5 hop |
| Numero di hop | Indicatore della consegna / log del router (`hop_count`) | Funzionante fino a 7 |
| Consegna differita | Tempo da "di nuovo in portata" a consegnato | < 60 s |
| Batteria | % consumata all'ora per ruolo (idle in tasca, chat attiva, relay in folla, chiamata) | Idle < 3 %/h, uso attivo < 8 %/h (da tarare) |
| Foto / vocali | Tempo di trasferimento di una foto e di un vocale da 30 s, tasso di successo | Foto < 15 s a 1 hop |
| Chiamate | Durata senza interruzioni, qualità percepita 1-5 | 5 min senza cadute a 5 m |
| Stabilità | Crash (Xcode Organizer / Play Console Vitals), disconnessioni BLE | 0 crash in 3 h |
| Calore | Temperatura percepita / stato termico dopo 1 h | Nessun avviso termico |

- [ ] Report finale con mediana, p90, peggior caso per scenario e per piattaforma.
- [ ] Bug aperti con modello/OS e log allegati; ripetere S2, S6 e S8 dopo le correzioni.

---

## 2. App Store Connect

- [ ] Bundle id definitivo (non `com.stefanobrandipersonalteam.AeroChat`) per app, widget e URL scheme.
- [ ] **Prodotti in-app** (nomi e descrizioni localizzati in 10 lingue + italiano, screenshot di revisione):
  - [ ] `com.aeromesh.pass24h` – Single Flight Pass 24 h (consumabile o non-renewing)
  - [ ] `com.aeromesh.pass72h` – Travel Pass 72 h
  - [ ] `com.aeromesh.lifetime` – Lifetime Pass (non consumabile)
  - [ ] Descrizione che spiega la condivisione al gruppo ("1 paga, il gruppo usa") come funzione del prodotto.
  - [ ] Prezzi per paese verificati; prodotti inviati insieme alla build.
- [ ] **Offer codes**: una campagna per i creator (codici personalizzati, es. `GIULIA`) e una per stampa/recensori,
      con scadenza e limite di riscatti. Verificare il foglio di riscatto dal paywall ("Hai un codice?").
- [ ] **Custom Product Pages**: almeno "Volo", "Festival", "Crociera", più una per ogni creator principale; annotare i
      `ppid`.
- [ ] **Campagne App Analytics**: provider token (`pt`) copiato in `web/get/index.html`.
- [ ] **Privacy nutrition labels**: "Dati non raccolti" (nessun dato raccolto dallo sviluppatore; la segnalazione via
      email è iniziativa dell'utente). Verificare la coerenza con `PrivacyInfo.xcprivacy` e con
      `stefanob88.github.io/aerochatwebsite/privacy`. Nessun tracciamento.
- [ ] URL: privacy `https://stefanob88.github.io/aerochatwebsite/privacy`, supporto `https://stefanob88.github.io/aerochatwebsite/support`, marketing
      `https://stefanob88.github.io/aerochatwebsite` (tutti online e raggiungibili prima dell'invio).
- [ ] Metadati fastlane (`ios/fastlane/metadata/*`): nome ≤ 30, sottotitolo ≤ 30, keywords ≤ 100, testo promozionale
      ≤ 170 per ogni lingua; release notes 1.4 aggiornate.
- [ ] Screenshot 6.9", 6.5", iPad 13" in tutte le lingue; anteprima video (facoltativa, consigliata: scena radar o Scopa).
- [ ] Classificazione età 12+; conformità crittografia (algoritmi standard); export compliance.
- [ ] Note per la revisione aggiornate (`ios/APP_REVIEW_NOTES.md`) con il passeggero virtuale e "Prova subito";
      video a due dispositivi allegato.
- [ ] Richiesta di recensione in-app (B5) testata: nessuna richiesta prima di 3 giorni / 5 aperture.
- [ ] Rilascio graduale (phased release) attivo per l'aggiornamento 1.4.
- [ ] Dopo la pubblicazione: sostituire `idXXXXXXXXX` nel sito e attivare lo Smart App Banner.

## 3. Google Play Console

- [ ] Package `com.stefanobrandi.aerochat`, firma dell'app di Play attiva; impronta SHA-256 annotata (servirà per
      `assetlinks.json`).
- [ ] **Prodotti in-app**: Pass 24 h, Pass 72 h, Lifetime con gli stessi nomi e la stessa logica di iOS; prezzi per paese.
- [ ] **Codici promozionali**: una promozione per i creator e una per la stampa.
- [ ] **Custom store listings** con URL univoco (`&listing=`) per tema e per creator principali.
- [ ] **Sicurezza dei dati**: "Nessun dato raccolto / condiviso"; coerente con l'informativa.
- [ ] **Autorizzazioni**: dichiarazione dei servizi in primo piano `connectedDevice` e `microphone`, Bluetooth/
      dispositivi nelle vicinanze, posizione (solo su richiesta dell'utente), notifiche. Video dimostrativo se richiesto.
- [ ] Classificazione dei contenuti (questionario IARC): interazione tra utenti, contenuti generati dagli utenti con
      segnalazione e blocco.
- [ ] Pubblico di destinazione: 13+; nessun annuncio.
- [ ] Scheda dello store in 10 lingue + italiano, feature graphic, screenshot (già in repo).
- [ ] Test chiuso con almeno 12 tester per 14 giorni (obbligatorio per i nuovi account sviluppatore personali).
- [ ] Android vitals: ANR e crash sotto le soglie su almeno una settimana di test.
- [ ] Play In-App Review testata (app interna di condivisione); Quick Share spiegato nel foglio "Gia' in volo?".
- [ ] Rilascio a fasi (staged rollout) 10 % → 50 % → 100 %.

## 4. Sito e dominio

- [x] Sito su GitHub Pages (https://stefanob88.github.io/aerochatwebsite/, repo `StefanoB88/aerochatwebsite`), nessun dominio da rinnovare.
- [ ] `web/` pubblicato (Cloudflare Pages / Netlify / GitHub Pages, vedi `web/README.md`), HTTPS forzato,
      `www` → dominio principale.
- [ ] Controllati: `/`, `/en/`, `/get`, `/get?code=TEST` (iPhone, Android, desktop, browser interni di Instagram e
      TikTok), `/privacy`, `/terms`, `/support`, pagina 404.
- [ ] Segnaposto compilati: id App Store, provider token, titolare e indirizzo in privacy/termini.
- [ ] **Privacy e termini revisionati da un legale** e allineati al testo in-app (`LegalView.swift` e Android).
- [ ] QR della cartolina e del foglio "Invita" puntano a `https://stefanob88.github.io/aerochatwebsite/get` e funzionano.
- [ ] (Futuro) `apple-app-site-association` e `assetlinks.json` per i link universali.

## 5. Email e supporto

- [ ] Caselle `aerochat.help@gmail.com` e `aerochat.help@gmail.com` attive (inoltro o casella dedicata), con SPF, DKIM, DMARC.
- [ ] Risposta automatica con link alle FAQ e tempi di risposta (48 h supporto, 24 h segnalazioni come dichiarato
      nei termini).
- [ ] Procedura segnalazioni: registro, decisione, cancellazione dei dati entro 90 giorni (come da informativa).
- [ ] Modelli di risposta per il supporto: "non vedo nessuno", rimborsi (Apple/Google), ripristino acquisti,
      Pass del gruppo non ricevuto, installazione in volo.

## 6. Modelli di risposta alle recensioni

Regole: rispondere entro 48 h, ringraziare, una soluzione concreta, niente polemiche, mai chiedere di cambiare
voto in cambio di qualcosa. Firmare "Il team AeroChat".

**Non trova nessuno / "non funziona"**
> Ciao, grazie per averla provata! AeroChat ha bisogno del Bluetooth acceso (in modalità aereo va riacceso a
> mano) e di almeno un altro telefono con l'app aperta entro qualche decina di metri. Nella schermata Persone
> vicine trovi "Perché non vedo nessuno?" con i controlli passo passo; puoi anche provarla subito da solo con
> "Prova subito". Se il problema resta scrivici a aerochat.help@gmail.com con il modello del telefono: lo risolviamo
> insieme.

**Recensione positiva**
> Grazie mille! Ci fa piacere che AeroChat ti abbia tenuto compagnia in viaggio. Se hai un'idea per una nuova
> funzione o un gioco, scrivici a aerochat.help@gmail.com. Buon prossimo viaggio!

**Prezzo / paywall**
> Grazie per il feedback. La chat di testo resta gratuita per sempre; il Pass è un acquisto una tantum, senza
> abbonamento, e basta che lo compri una sola persona per sbloccarlo a tutto il gruppo, anche su Android.
> Dalla 1.4 c'è anche una partita gratis al giorno e per ogni volo.

**Pass non ricevuto dal gruppo / acquisto non visibile**
> Ci dispiace! Apri la schermata del Pass e tocca "Ripristina acquisti"; per il Pass del gruppo, chi ha
> acquistato deve essere vicino agli altri membri con l'app aperta. Se non basta scrivici a
> aerochat.help@gmail.com (senza dati di pagamento): ti aiutiamo subito. I rimborsi sono gestiti da Apple/Google.

**Batteria**
> Grazie della segnalazione. AeroChat usa Bluetooth Low Energy, ma con tanti telefoni intorno il lavoro da
> "ponte" aumenta. Quando non ti serve puoi chiudere l'app o disattivare "Visibile alle persone vicine".
> Ci aiuterebbe sapere modello e durata d'uso: aerochat.help@gmail.com.

**Comportamento scorretto di un altro utente**
> Ci dispiace per quanto successo. Tieni premuto il messaggio e scegli "Blocca": l'utente sparisce subito dal
> tuo telefono. Puoi inoltrarci la segnalazione a aerochat.help@gmail.com, la esaminiamo entro 24 ore.

**Bug / crash**
> Grazie e scusa per il disagio. Ci scrivi a aerochat.help@gmail.com con modello, versione del sistema e cosa
> stavi facendo? Correggiamo i crash con priorità nel prossimo aggiornamento.

English (short):
> Thanks for trying AeroChat! It needs Bluetooth on (turn it back on after enabling airplane mode) and another
> phone with the app open within a few dozen meters. Check "Why can't I see anyone?" in Nearby people, or try
> it solo with "Try it now". Still stuck? Email aerochat.help@gmail.com with your phone model and we'll help.

## 7. Lancio

- [ ] Kit creator inviato a 10-20 creator di viaggio con codici personali (`CREATOR_KIT.md`).
- [ ] Post e video pronti per i periodi di picco (ponti, estate, stagione dei festival e delle crociere).
- [ ] Comunicato breve per stampa tech/viaggi italiana con il video in volo.
- [ ] Monitoraggio settimanale: download per campagna, conversione Pass, recensioni, crash.
