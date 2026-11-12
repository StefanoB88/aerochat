# aeromesh.app – sito statico

Sito di presentazione di AeroChat: HTML + un solo CSS, nessuna dipendenza, nessun build step, nessun
font/CDN esterno, nessun cookie o tracker. Si pubblica copiando la cartella `web/` così com'è.

| Percorso | File | Note |
|---|---|---|
| `/` | `index.html` | Italiano (lingua principale) |
| `/en/` | `en/index.html` | Inglese |
| `/get` | `get/index.html` | Usato dai QR code (app, cartolina di volo, creator). Reindirizza allo store giusto |
| `/privacy` | `privacy.html` | Linkato dall'app (`AppLinks.privacyPolicy`) e da `privacy_url.txt` |
| `/terms` | `terms.html` | Linkato dall'app (`AppLinks.termsOfUse`) |
| `/support` | `support.html` | Richiesto da `support_url.txt` dello store |
| | `404.html`, `robots.txt`, `sitemap.xml`, `icon.svg`, `apple-touch-icon.png` | |

L'app apre `https://aeromesh.app/privacy`, `/terms` e `/get` **senza** estensione: l'hosting deve servire
`privacy.html` per `/privacy` (tutti e tre gli hosting qui sotto lo fanno di default, "pretty URLs").

## Prima della pubblicazione (segnaposto da compilare)

- `XXXXXXXXX` (id numerico App Store) in `index.html`, `en/index.html` e `get/index.html`
  (variabile `APP_STORE_ID` e link di fallback). Decommentare il meta `apple-itunes-app` (Smart App Banner).
- `APPLE_PROVIDER_TOKEN` in `get/index.html` (facoltativo, App Store Connect › App Analytics › Campagne →
  "Genera un link": è il valore `pt=`). Senza, il `ct=` viene comunque passato.
- `privacy.html` / `terms.html`: `[TITOLARE …]`, `[INDIRIZZO]`. Entrambe sono **bozze da far revisionare a un
  legale** (c'è un commento HTML in testa) e vanno tenute allineate con `ios/AeroChat/UI/Settings/LegalView.swift`
  e la controparte Android.
- Caselle `support@aeromesh.app` e `safety@aeromesh.app` attive.
- I badge degli store sono pulsanti disegnati in CSS. Per la massima fedeltà alle linee guida si possono
  sostituire con i badge ufficiali (Apple Marketing Resources, Google Play badge generator) salvati in locale
  in `web/` come SVG/PNG, senza linkarli da CDN.
- Le card "Scambio posto" e "Bacheca della cabina" sono marcate "In arrivo": togliere il tag quando esce la 1.4.

## Pagina `/get`

- iPhone/iPad (anche iPadOS che si presenta come Mac) → App Store; Android → Google Play; desktop → due pulsanti.
- `?code=CREATOR` oppure `?ref=CREATOR`: il codice (solo `A-Z a-z 0-9 _ -`, max 40) viene mostrato con
  "Copia il codice" e il reindirizzamento parte dopo 4 s (annullabile). Viene passato agli store come campagna:
  - App Store: `https://apps.apple.com/app/id<ID>?pt=<token>&ct=<codice>&mt=8` → App Analytics › Fonti › Campagne.
  - Google Play: `…details?id=com.stefanobrandi.aerochat&referrer=utm_source%3Daeromesh_get%26utm_medium%3Dcode%26utm_campaign%3D<codice>`
    → Play Console › Statistiche › Acquisizione utenti (campagne UTM).
- Esempi: `https://aeromesh.app/get`, `https://aeromesh.app/get?code=GIULIA`, `https://aeromesh.app/get?ref=cartolina`.
- Prova locale: `cd web && python3 -m http.server 8000` → `http://localhost:8000/get/?code=TEST`
  (in locale serve la barra finale; online `/get` funziona anche senza). Per simulare iPhone/Android usare
  l'emulazione dispositivo degli strumenti per sviluppatori del browser.

## Hosting

Qualunque hosting statico va bene. HTTPS obbligatorio (lo forniscono tutti gratis).

### Cloudflare Pages (consigliato: veloce, gratis, nessun log analitico se non lo attivi)
1. Dashboard › Workers & Pages › Create › Pages › collega il repo (o "Direct upload" della cartella `web/`).
2. Build command: vuoto. Output directory: `web`.
3. Custom domains › `aeromesh.app` (e `www.aeromesh.app`, con redirect a `aeromesh.app`). Se il dominio è su
   Cloudflare il DNS si configura da solo. **Non** attivare Web Analytics se vuoi mantenere "nessun tracker".

### Netlify
1. Add new site › Import from Git (o trascina la cartella `web/` su app.netlify.com/drop).
2. Base directory `web`, build command vuoto, publish directory `web`.
3. Domain management › Add custom domain › `aeromesh.app`; DNS: record `A` verso il load balancer Netlify o
   `ALIAS/ANAME`, `www` in `CNAME`. HTTPS con Let's Encrypt automatico.

### GitHub Pages
1. Il sito deve stare alla radice di un branch o in `/docs`: usare una GitHub Action "Deploy static content"
   con `path: web`, oppure un repo dedicato con il contenuto di `web/`.
2. Settings › Pages › Custom domain `aeromesh.app` (crea il file `CNAME`); DNS: 4 record `A`
   185.199.108.153, 185.199.109.153, 185.199.110.153, 185.199.111.153 (+ `AAAA` se vuoi IPv6), `www` in `CNAME`
   verso `<utente>.github.io`. Spunta "Enforce HTTPS".
3. `/get` viene reindirizzato a `/get/` mantenendo la query string.

## Universal Links / App Links (futuro)

Oggi i QR puntano a `/get`, che apre lo store. Quando l'app gestirà i link `https://aeromesh.app/...`
(per esempio inviti o codici aperti direttamente nell'app se installata):

- **iOS**: pubblicare `web/.well-known/apple-app-site-association` (JSON **senza estensione**, servito con
  `Content-Type: application/json`, senza redirect) con `applinks.details[].appIDs = ["<TEAMID>.<bundle id>"]`
  e i percorsi (es. `"/i/*"`, `"/get*"`). Aggiungere l'entitlement Associated Domains `applinks:aeromesh.app`
  all'app. Ricordarsi di usare il bundle id definitivo (non quello del personal team). Su Netlify/Cloudflare
  aggiungere una regola di header per il Content-Type; su GitHub Pages i file in `.well-known` richiedono
  un file `.nojekyll` alla radice.
- **Android**: pubblicare `web/.well-known/assetlinks.json` con il package `com.stefanobrandi.aerochat` e
  l'impronta SHA-256 del certificato di firma di Play (Play Console › Integrità app › Firma dell'app), e
  `android:autoVerify="true"` sugli intent filter.
- Lasciare comunque `/get` funzionante come fallback per chi non ha l'app.

## Regole del sito

- Nessuno script esterno, nessun font esterno, nessun cookie: l'informativa privacy lo dichiara.
- Contrasto AA sul tema scuro (testo #EAF2FF / #A9B6CC su #070B14), focus visibile, `prefers-reduced-motion`
  rispettato, FAQ con `<details>` (funzionano anche senza JavaScript). L'unico JS è in `/get`.
