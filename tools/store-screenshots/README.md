# Screenshot per gli store

Immagini promozionali per App Store e Google Play a partire da schermate **reali** dell'app.

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
  telefoni, 0.035 per i tablet; Android usa 0.06 perche' l'orologio sta nell'angolo in alto a sinistra).

Modalita' aggiuntive (l'uscita iOS non cambia):

```sh
# Feature graphic di Google Play 1024x500: icona, nome, slogan e telefono inclinato con lo screenshot.
/tmp/aerochat-compose --feature --icon design/icona-playstore-512.png --input chats.png --output featureGraphic.png \
    --width 1024 --height 500 --title "AeroChat" --subtitle "Slogan"
# Ricodifica senza alfa ne' metadati (icona 512x512 dello store).
/tmp/aerochat-compose --reencode --input design/icona-playstore-512.png --output icon.png
```

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

## capture-android.sh

Pipeline per Google Play: build `:app:assembleDebug`, emulatore headless `Medium_Phone_API_36.0`
(avviato se non c'e' gia'), tema scuro, navigazione a gesti, barra di stato demo (09:41, batteria piena,
Wi-Fi pieno, niente notifiche), installazione con tutti i permessi (`install -g`), lingua per app
(`cmd locale set-app-locales`), cattura delle 6 schermate in it-IT e en-US e composizione nel formato di
fastlane supply:

```
android/fastlane/metadata/android/{it-IT,en-US}/images/
    phoneScreenshots/1_chats.png ... 6_flightcard.png   (1080x1920)
    featureGraphic.png                                   (1024x500)
    icon.png                                             (512x512)
```

```sh
tools/store-screenshots/capture-android.sh                       # tutto
SKIP_BUILD=1 LANGS="en" tools/store-screenshots/capture-android.sh
COMPOSE_ONLY=1 tools/store-screenshots/capture-android.sh        # solo ricomposizione da /tmp/aerochat-raw-android
KILL_EMULATOR=1 tools/store-screenshots/capture-android.sh       # spegne l'emulatore anche se era gia' acceso
```

Variabili: `SERIAL` (default `emulator-5554`; deve essere un emulatore: lo script si rifiuta di toccare
telefoni collegati via USB o debug wireless), `AVD`, `LANGS` (`it en`), `WAIT` (secondi dopo l'avvio,
default 14: l'emulatore software e' lento), `SIZE` (default `1080 1920`; Google Play non accetta un lato
lungo piu' del doppio del corto, quindi niente 1080x2340). Se lo script ha avviato l'emulatore, alla fine
lo spegne. Gradle e l'emulatore possono richiedere l'esecuzione fuori dalla sandbox.

## Dati finti nell'app (solo DEBUG)

`ios/AeroChat/Core/Review/StoreScreenshots.swift` (tutto dentro `#if DEBUG`). Argomenti di avvio:

- `-storeScreenshots`: database e media temporanei, onboarding saltato, profilo "Giulia"/"Julia", chat,
  gruppo, Cabina del volo AZ 204 FCO -> JFK, Cabina Pubblica, partita a Scopa, persone vicine e richiesta
  di sfida finte; trasporto radio finto (nessun Bluetooth) e nessuna notifica.
- `-storeScreen <nome>`: `chats`, `conversation`, `cabin` (Cabina del volo), `lounge` (Cabina Pubblica),
  `cards`, `nearby`, `flightcard`.
- La lingua dei testi segue `-AppleLanguages "(it)"` / `"(en)"` (con `-AppleLocale it_IT` / `en_US`).

Android: `android/app/src/debug/java/com/stefanobrandi/aerochat/screenshots/StoreScreenshots.kt` (solo
build debug; la build release compila lo stub vuoto in `src/release`). Stessi dati di iOS, con gli extra
dell'intent:

```sh
adb -s emulator-5554 shell am start -n com.stefanobrandi.aerochat/.MainActivity \
    --ez storeScreenshots true --es storeScreen <chats|conversation|cabin|lounge|cards|nearby|flightcard>
```

Database Room in memoria gia' popolato, chiavi deterministiche in memoria (il Keystore non viene
toccato), trasporto radio finto con 5 vicini, nessuna notifica, niente richiesta di permessi. La lingua
dei testi finti segue la lingua dell'app (italiano se `it`, altrimenti inglese).
