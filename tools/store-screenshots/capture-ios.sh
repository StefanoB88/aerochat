#!/bin/zsh
# Screenshot dell'App Store per iOS: build Debug, cattura delle schermate reali dal Simulatore (dati
# finti con -storeScreenshots, solo DEBUG) e composizione delle immagini promozionali in
# ios/fastlane/screenshots/<lingua>/. Le catture grezze restano in /tmp/aerochat-raw (fuori da git).
#
# Uso: tools/store-screenshots/capture-ios.sh            (tutto)
#      SKIP_BUILD=1 LANGS="it" DEVICES="iphone" tools/store-screenshots/capture-ios.sh
#      COMPOSE_ONLY=1 tools/store-screenshots/capture-ios.sh   (ricompone dalle catture in /tmp)
set -euo pipefail

ROOT=${0:A:h:h:h}
TOOLS=$ROOT/tools/store-screenshots
IOS=$ROOT/ios
DERIVED=/tmp/aerochat-shots
RAW=/tmp/aerochat-raw
OUT=$IOS/fastlane/screenshots
BUNDLE=com.stefanobrandipersonalteam.AeroChat
WAIT=${WAIT:-8}

IPHONE_UDID=${IPHONE_UDID:-6880DB98-35C0-4F73-A267-517B8EB6CAFD}   # iPhone 17 Pro Max (6.9")
IPAD_UDID=${IPAD_UDID:-562138DA-E458-4E41-BF38-7145D8905115}       # iPad Pro 13-inch (M4)
DEVICES=(${=DEVICES:-iphone ipad})
LANGS=(${=LANGS:-it en})
SCREENS=(chats conversation cabin cards nearby flightcard)

typeset -A TITLE_it TITLE_en
TITLE_it=(chats "Chatta anche senza internet" conversation "I messaggi passano da telefono a telefono"
          cabin "La chat del tuo volo" cards "Scopa e briscola con chi è a bordo"
          nearby "Scopri chi è vicino a te" flightcard "Il ricordo di ogni viaggio")
TITLE_en=(chats "Chat even without internet" conversation "Messages hop from phone to phone"
          cabin "Your flight's own chat" cards "Play cards with fellow passengers"
          nearby "See who's nearby" flightcard "A keepsake of every trip")

COMPOSE_ONLY=${COMPOSE_ONLY:-}
if [[ -z ${SKIP_BUILD:-} && -z $COMPOSE_ONLY ]]; then
    xcodebuild -project $IOS/AeroChat.xcodeproj -scheme AeroChat -configuration Debug \
        -destination "platform=iOS Simulator,id=$IPHONE_UDID" -derivedDataPath $DERIVED build -quiet
fi
APP=$DERIVED/Build/Products/Debug-iphonesimulator/AeroChat.app
swiftc -O -module-cache-path /tmp/mc $TOOLS/compose.swift -o /tmp/aerochat-compose

for device in $DEVICES; do
    if [[ $device == iphone ]]; then
        udid=$IPHONE_UDID; label="iPhone_6.9"; size=(1320 2868)
    else
        udid=$IPAD_UDID; label="iPad_13"; size=(2064 2752)
    fi
    if [[ -z $COMPOSE_ONLY ]]; then
        xcrun simctl boot $udid 2>/dev/null || true
        xcrun simctl bootstatus $udid -b >/dev/null
        xcrun simctl ui $udid appearance dark
        xcrun simctl status_bar $udid override --time 9:41 --batteryState charged --batteryLevel 100 \
            --cellularBars 4 --wifiBars 3
        xcrun simctl install $udid $APP
    fi
    for lang in $LANGS; do
        if [[ $lang == it ]]; then locale=it_IT; folder=it; else locale=en_US; folder=en-US; fi
        mkdir -p $RAW/$folder $OUT/$folder
        index=0
        for screen in $SCREENS; do
            index=$((index + 1))
            raw=$RAW/$folder/${label}_${screen}.png
            if [[ -z $COMPOSE_ONLY ]]; then
                xcrun simctl terminate $udid $BUNDLE 2>/dev/null || true
                xcrun simctl launch $udid $BUNDLE -storeScreenshots -storeScreen $screen \
                    -AppleLanguages "($lang)" -AppleLocale $locale >/dev/null
                sleep $WAIT
                xcrun simctl io $udid screenshot $raw >/dev/null 2>&1
            fi
            if [[ $lang == it ]]; then title=${TITLE_it[$screen]}; else title=${TITLE_en[$screen]}; fi
            /tmp/aerochat-compose --input $raw --output $OUT/$folder/0${index}_${label}_${screen}.png \
                --width $size[1] --height $size[2] --title "$title"
            echo "$folder/0${index}_${label}_${screen}.png"
        done
    done
    if [[ -z $COMPOSE_ONLY ]]; then
        xcrun simctl terminate $udid $BUNDLE 2>/dev/null || true
        xcrun simctl status_bar $udid clear
        xcrun simctl shutdown $udid
    fi
done
