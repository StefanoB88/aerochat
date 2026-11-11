# Screenshot per gli store

Immagini promozionali per App Store (e, in seguito, Google Play) a partire da schermate **reali** dell'app.

## compose.swift

Script Swift (CoreGraphics/CoreText, solo macOS) che compone una singola immagine: sfondo con sfumatura
diagonale `#3D6BFF -> #0A1A4F` (colori dell'icona), titolo bianco in grassetto (font di sistema, adattato a
massimo 2 righe), sottotitolo opzionale e screenshot ridimensionato con angoli arrotondati, cornice e ombra.
Uscita PNG senza canale alfa e senza metadati (solo i blocchi IHDR, sRGB, IDAT, IEND).

```sh
swiftc -O -module-cache-path /tmp/mc tools/store-screenshots/compose.swift -o /tmp/aerochat-compose
/tmp/aerochat-compose --input raw.png --output out.png --width 1320 --height 2868 \
    --title "Chatta anche senza internet" [--subtitle "Testo piu' piccolo"] [--corner 0.125]
```

- `--width/--height`: dimensioni della tela (iPhone 6.9" 1320x2868, iPad 13" 2064x2752; per Google Play
  ad esempio 1080x1920).
- `--corner`: raggio degli angoli in frazione della larghezza dello screenshot (automatico: 0.125 per i
  telefoni, 0.035 per i tablet).

## capture-ios.sh

Pipeline completa per iOS: build Debug, avvio dei simulatori "iPhone 17 Pro Max" e "iPad Pro 13-inch (M4)"
in aspetto scuro con barra di stato 9:41, cattura delle 6 schermate in italiano e inglese e composizione in
`ios/fastlane/screenshots/{it,en-US}/NN_<device>_<schermata>.png` (fastlane deliver riconosce il
dispositivo dalla dimensione). Le catture grezze restano in `/tmp/aerochat-raw`; alla fine i simulatori
vengono spenti.

```sh
tools/store-screenshots/capture-ios.sh                         # tutto
SKIP_BUILD=1 DEVICES="iphone" LANGS="en" tools/store-screenshots/capture-ios.sh
COMPOSE_ONLY=1 tools/store-screenshots/capture-ios.sh          # solo ricomposizione (titoli cambiati)
```

Variabili: `IPHONE_UDID`, `IPAD_UDID`, `DEVICES` (`iphone ipad`), `LANGS` (`it en`), `WAIT` (secondi di
attesa dopo l'avvio, default 8).

I titoli sono nell'intestazione dello script (`TITLE_it`, `TITLE_en`).

## Dati finti nell'app (solo DEBUG)

`ios/AeroChat/Core/Review/StoreScreenshots.swift` (tutto dentro `#if DEBUG`). Argomenti di avvio:

- `-storeScreenshots`: database e media temporanei, onboarding saltato, profilo "Giulia"/"Julia", chat,
  gruppo, Cabina del volo AZ 204 FCO -> JFK, Cabina Pubblica, partita a Scopa, persone vicine e richiesta
  di sfida finte; trasporto radio finto (nessun Bluetooth) e nessuna notifica.
- `-storeScreen <nome>`: `chats`, `conversation`, `cabin` (Cabina del volo), `lounge` (Cabina Pubblica),
  `cards`, `nearby`, `flightcard`.
- La lingua dei testi segue `-AppleLanguages "(it)"` / `"(en)"` (con `-AppleLocale it_IT` / `en_US`).
