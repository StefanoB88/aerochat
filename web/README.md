# AeroChat – sito statico (GitHub Pages)

Sito di presentazione di AeroChat: HTML + un solo CSS, nessuna dipendenza, nessun build step, nessun
font/CDN esterno, nessun cookie o tracker. Online su **https://stefanob88.github.io/aerochatwebsite/** (GitHub Pages, repo `StefanoB88/aerochatwebsite`).
Questa cartella è la sorgente: si pubblica copiando il contenuto di `web/` nel repo del sito (vedi "Hosting").

Il sito sta nel sottopercorso `/aerochatwebsite/`: i link interni sono assoluti con quel prefisso
(`href="/aerochatwebsite/..."`). Se un giorno si passa a un dominio proprio, sostituire `/aerochatwebsite/`
con `/` e `https://stefanob88.github.io/aerochatwebsite` con il dominio, qui e in `AppLinks` /
`QRCodeGenerator.storeLandingURL` (iOS e Android) e in `ios/fastlane/metadata/*/*_url.txt`.

| Percorso | File | Note |
|---|---|---|
| `/` | `index.html` | Italiano (lingua principale) |
| `/en/` | `en/index.html` | Inglese |
| `/get` | `get/index.html` | Usato dai QR code (app, cartolina di volo, creator). Reindirizza allo store giusto |
| `/privacy` | `privacy.html` | Linkato dall'app (`AppLinks.privacyPolicy`) e da `privacy_url.txt` |
| `/terms` | `terms.html` | Linkato dall'app (`AppLinks.termsOfUse`) |
| `/support` | `support.html` | Richiesto da `support_url.txt` dello store |
| `/en/privacy`, `/en/terms`, `/en/support` | `en/*.html` | Versioni inglesi (store in lingue diverse dall'italiano). Tenerle allineate alle italiane |
| | `404.html`, `robots.txt`, `sitemap.xml`, `icon.svg`, `apple-touch-icon.png` | |

L'app apre `…/aerochatwebsite/privacy`, `/terms` e `/get` **senza** estensione: l'hosting deve servire
`privacy.html` per `/privacy` (GitHub Pages lo fa di default).

## Prima della pubblicazione (segnaposto da compilare)

- `XXXXXXXXX` (id numerico App Store) in `index.html`, `en/index.html` e `get/index.html`
  (variabile `APP_STORE_ID` e link di fallback). Decommentare il meta `apple-itunes-app` (Smart App Banner).
- `APPLE_PROVIDER_TOKEN` in `get/index.html` (facoltativo, App Store Connect › App Analytics › Campagne →
  "Genera un link": è il valore `pt=`). Senza, il `ct=` viene comunque passato.
- `privacy.html` / `terms.html` (e `en/`): titolare Stefano Brandi, Berlino. Entrambe sono **bozze da far revisionare a un
  legale** (c'è un commento HTML in testa) e vanno tenute allineate con `ios/AeroChat/UI/Settings/LegalView.swift`
  e la controparte Android.
- Contatto unico (supporto e segnalazioni): `aerochat.help@gmail.com`.
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
- Esempi: `https://stefanob88.github.io/aerochatwebsite/get`, `…/get?code=GIULIA`, `…/get?ref=cartolina`.
- Prova locale (serve il prefisso): `mkdir -p /tmp/site && ln -sfn "$PWD/web" /tmp/site/aerochatwebsite && python3 -m http.server 8000 -d /tmp/site` → `http://localhost:8000/aerochatwebsite/get/?code=TEST`
  (in locale serve la barra finale; online `/get` funziona anche senza). Per simulare iPhone/Android usare
  l'emulazione dispositivo degli strumenti per sviluppatori del browser.

## Hosting (GitHub Pages)

Repo del sito: `StefanoB88/aerochatwebsite` (clone locale `~/Documents/AeroChatWebsite/aerochatwebsite`).
Settings › Pages › Deploy from a branch › `main` / `(root)`, "Enforce HTTPS". Il file `.nojekyll` evita
l'elaborazione Jekyll (i file sono serviti così come sono).

Per pubblicare una modifica:

```sh
rsync -a --delete --exclude .git --exclude README.md --exclude .DS_Store web/ ~/Documents/AeroChatWebsite/aerochatwebsite/
cd ~/Documents/AeroChatWebsite/aerochatwebsite && git add -A && git commit -m "Aggiorna sito" && git push
```

`/get` viene reindirizzato a `/get/` mantenendo la query string. La `404.html` vale per tutto il sito.

## Universal Links / App Links (futuro)

Oggi i QR puntano a `/get`, che apre lo store. Universal Links e App Links richiedono i file `.well-known` alla **radice del dominio**: con il sottopercorso
`/aerochatwebsite/` non sono possibili (servirebbe il repo `StefanoB88.github.io` o un dominio proprio). Quando l'app gestirà i link
(per esempio inviti o codici aperti direttamente nell'app se installata):

- **iOS**: pubblicare `web/.well-known/apple-app-site-association` (JSON **senza estensione**, servito con
  `Content-Type: application/json`, senza redirect) con `applinks.details[].appIDs = ["<TEAMID>.<bundle id>"]`
  e i percorsi (es. `"/i/*"`, `"/get*"`). Aggiungere l'entitlement Associated Domains `applinks:<dominio>`
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
