# AeroChat

Chat offline peer-to-peer via Bluetooth Low Energy (mesh AeroMesh): messaggi, foto, note vocali,
chiamate, gruppi, Cabina Pubblica e giochi (Tris, Battaglia navale, Scopa, Scopone, Briscola).
Le due app parlano lo stesso protocollo e si scambiano messaggi tra iPhone e Android.

| Cartella | Piattaforma | Come aprirla |
|---|---|---|
| [`ios/`](ios) | iOS 17+ (SwiftUI, GRDB, SwiftProtobuf, libwebp) | `ios/AeroChat.xcodeproj` in Xcode |
| [`android/`](android) | Android 10+ (Kotlin, Jetpack Compose, Room, protobuf-lite) | cartella `android/` in Android Studio |

Documenti condivisi:
- [`CARD_GAMES_SPEC.md`](CARD_GAMES_SPEC.md) — specifica comune dei giochi di carte (formato wire e regole).
- [`android/PORTING.md`](android/PORTING.md) — convenzioni del porting iOS → Android.

Il contratto binario (`aeromesh.proto`, `aeromesh_ext.proto`) è condiviso: ogni modifica va fatta su
entrambe le piattaforme mantenendo la compatibilità byte per byte.
