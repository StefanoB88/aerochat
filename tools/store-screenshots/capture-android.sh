#!/bin/zsh
# Screenshot di Google Play per Android: build debug, emulatore headless (tema scuro, barra di stato demo
# 09:41, navigazione a gesti), cattura delle 6 schermate reali (dati finti con l'extra storeScreenshots,
# solo debug) in italiano e inglese e composizione in
# android/fastlane/metadata/android/<lingua>/images/phoneScreenshots/, piu' featureGraphic.png e icon.png.
# Le catture grezze restano in /tmp/aerochat-raw-android (fuori da git).
#
# Uso: tools/store-screenshots/capture-android.sh            (tutto)
#      SKIP_BUILD=1 LANGS="it" tools/store-screenshots/capture-android.sh
#      COMPOSE_ONLY=1 tools/store-screenshots/capture-android.sh   (ricompone dalle catture in /tmp)
set -euo pipefail

ROOT=${0:A:h:h:h}
TOOLS=$ROOT/tools/store-screenshots
ANDROID=$ROOT/android
RAW=/tmp/aerochat-raw-android
OUT=$ANDROID/fastlane/metadata/android
PACKAGE=com.stefanobrandi.aerochat
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
# Sempre e solo l'emulatore (mai un telefono collegato via USB o debug wireless).
SERIAL=${SERIAL:-emulator-5554}
[[ $SERIAL == emulator-* ]] || { echo "SERIAL deve essere un emulatore (emulator-NNNN)" >&2; exit 1; }
ADB=($SDK/platform-tools/adb -s $SERIAL)
AVD=${AVD:-Medium_Phone_API_36.0}
WAIT=${WAIT:-14}
# Google Play: lato lungo al massimo il doppio del corto (1080x2340 non e' accettato), 9:16 va sempre bene.
SIZE=(${=SIZE:-1080 1920})
LANGS=(${=LANGS:-it en})
SCREENS=(chats conversation cabin cards nearby flightcard)
export JAVA_HOME=${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home}

typeset -A TITLE_it TITLE_en
TITLE_it=(chats "Chatta anche senza internet" conversation "I messaggi passano da telefono a telefono"
          cabin "La chat del tuo volo" cards "Scopa e briscola con chi è a bordo"
          nearby "Scopri chi è vicino a te" flightcard "Il ricordo di ogni viaggio")
TITLE_en=(chats "Chat even without internet" conversation "Messages hop from phone to phone"
          cabin "Your flight's own chat" cards "Play cards with fellow passengers"
          nearby "See who's nearby" flightcard "A keepsake of every trip")
TAGLINE_it="Chat, giochi e la cabina del tuo volo.\nSenza internet."
TAGLINE_en="Chat, games and your flight's cabin.\nNo internet needed."

COMPOSE_ONLY=${COMPOSE_ONLY:-}
if [[ -z ${SKIP_BUILD:-} && -z $COMPOSE_ONLY ]]; then
    (cd $ANDROID && ./gradlew -q :app:assembleDebug)
fi
APK=$ANDROID/app/build/outputs/apk/debug/app-debug.apk
swiftc -O -module-cache-path /tmp/mc $TOOLS/compose.swift -o /tmp/aerochat-compose

STARTED_EMULATOR=
if [[ -z $COMPOSE_ONLY ]]; then
    if [[ "$($ADB get-state 2>/dev/null)" != device ]]; then
        $SDK/emulator/emulator -avd $AVD -port ${SERIAL#emulator-} -no-window -no-audio -no-boot-anim \
            -gpu swiftshader_indirect -no-snapshot-save >/tmp/aerochat-emulator.log 2>&1 &
        STARTED_EMULATOR=1
        $ADB wait-for-device
    fi
    until [[ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do sleep 2; done
    [[ "$($ADB shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo "$SERIAL non e' un emulatore" >&2; exit 1; }
    # Schermo acceso e sbloccato.
    $ADB shell input keyevent KEYCODE_WAKEUP
    $ADB shell wm dismiss-keyguard
    $ADB shell svc power stayon true
    # Tema scuro, navigazione a gesti, barra di stato demo (09:41, batteria piena, segnale pieno, niente notifiche).
    $ADB shell cmd uimode night yes >/dev/null
    $ADB shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural >/dev/null 2>&1 || true
    $ADB shell settings put global sysui_demo_allowed 1
    demo() { $ADB shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
    demo enter
    demo clock -e hhmm 0941
    demo battery -e level 100 -e plugged false -e powersave false
    demo network -e wifi show -e level 4 -e fully true
    # Solo Wi-Fi: il segnale cellulare dell'emulatore mostra sempre "3G" anche in modalita' demo.
    demo network -e mobile hide
    demo network -e airplane hide
    demo notifications -e visible false
    demo status -e bluetooth hide -e volume hide -e alarm hide -e location hide -e mute hide -e speakerphone hide
    $ADB install -r -g $APK >/dev/null
fi

for lang in $LANGS; do
    if [[ $lang == it ]]; then tag=it-IT; else tag=en-US; fi
    mkdir -p $RAW/$tag $OUT/$tag/images/phoneScreenshots
    if [[ -z $COMPOSE_ONLY ]]; then
        $ADB shell cmd locale set-app-locales $PACKAGE --locales $tag
    fi
    index=0
    for screen in $SCREENS; do
        index=$((index + 1))
        raw=$RAW/$tag/$screen.png
        if [[ -z $COMPOSE_ONLY ]]; then
            $ADB shell am force-stop $PACKAGE
            $ADB shell am start -n $PACKAGE/.MainActivity --ez storeScreenshots true --es storeScreen $screen >/dev/null
            sleep $WAIT
            $ADB exec-out screencap -p > $raw
        fi
        if [[ $lang == it ]]; then title=${TITLE_it[$screen]}; else title=${TITLE_en[$screen]}; fi
        # Angoli piu' stretti che su iOS: l'orologio di Android sta nell'angolo in alto a sinistra.
        /tmp/aerochat-compose --input $raw --output $OUT/$tag/images/phoneScreenshots/${index}_${screen}.png \
            --width $SIZE[1] --height $SIZE[2] --title "$title" --corner 0.06
        echo "$tag/images/phoneScreenshots/${index}_${screen}.png"
    done
    # Feature graphic 1024x500 (icona, nome, slogan, telefono inclinato con la lista chat) e icona 512x512.
    if [[ $lang == it ]]; then tagline=$TAGLINE_it; else tagline=$TAGLINE_en; fi
    /tmp/aerochat-compose --feature --icon $ROOT/design/icona-playstore-512.png --input $RAW/$tag/chats.png \
        --output $OUT/$tag/images/featureGraphic.png --width 1024 --height 500 --title "AeroChat" --subtitle "${(g::)tagline}"
    /tmp/aerochat-compose --reencode --input $ROOT/design/icona-playstore-512.png --output $OUT/$tag/images/icon.png
    echo "$tag/images/featureGraphic.png, $tag/images/icon.png"
done

if [[ -z $COMPOSE_ONLY ]]; then
    $ADB shell am force-stop $PACKAGE
    $ADB shell cmd locale set-app-locales $PACKAGE --locales ""
    $ADB shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
    if [[ -n $STARTED_EMULATOR || -n ${KILL_EMULATOR:-} ]]; then $ADB emu kill >/dev/null 2>&1 || true; fi
fi
