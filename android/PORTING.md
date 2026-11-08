# AeroChat Android — porting conventions (iOS → Android)

Source of truth: the iOS app at `/Users/stefano/Documents/iOS/AeroChat/AeroChat` (Swift, comments in Italian).
This Android app is a 1:1 port and **must stay wire-compatible with iOS** (an Android phone and an iPhone
talk to each other over the BLE mesh). Every byte on the wire / in QR codes / in signatures must match.

## Build
- `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Compile: `./gradlew :app:compileDebugKotlin -q` — unit tests: `./gradlew :app:testDebugUnitTest --tests 'com.example.aerochat.<pkg>.*'`
- Other agents may be editing other packages concurrently. If the build fails **only** because of files
  outside your packages, that is not your problem: note it and continue (re-run later). Never edit files
  owned by another phase unless told to; if you need a tiny API addition there, prefer adding an extension
  function in your own package.
- Dependencies are already declared in `app/build.gradle.kts` / `gradle/libs.versions.toml` (Compose, Material3,
  Room+KSP, protobuf-kotlin-lite, BouncyCastle `bcprov-jdk18on`, lz4-java, CameraX, ML Kit barcode, ZXing,
  Play Billing, coroutines, Robolectric). Add a dependency only if really necessary.
- minSdk 29, targetSdk/compileSdk 35, Kotlin 2.0, JVM 17.

## Protobuf
- `app/src/main/proto/aeromesh.proto` and `aeromesh_ext.proto` are copies of the iOS `.proto` files, generated
  as Java-lite + Kotlin DSL in package `com.example.aerochat.proto` (`MeshEnvelope`, `InnerPayload`,
  `PacketType`, `GroupKeyGossip`, `GroupMember`, `ProPassGrant`, `GameMove`, `GameKind`, `GameAction`,
  `ShotReport`, `ProfileUpdate`, `CallSignal`, `CallAudio`, `CallAction`).
- **Difference**: `CallAction` values are named `CALL_OFFER`, `CALL_ACCEPT`, `CALL_REJECT`, `CALL_HANGUP`,
  `CALL_BUSY` (protoc forbids the duplicate `ACCEPT` in the package). Numbers are unchanged → wire identical.
- The Swift files `Aeromesh.pb.swift` / `AeromeshExt.pb.swift` are NOT ported (replaced by generated code).
  `bytes` fields are `com.google.protobuf.ByteString` (`ByteString.copyFrom(ba)`, `.toByteArray()`).

## Package layout (mirrors the iOS folders)
Root package `com.example.aerochat`:
| iOS folder | Kotlin package |
|---|---|
| App/ | `app` |
| Core/Calls | `core.calls` |
| Core/Crypto | `core.crypto` |
| Core/Entitlements | `core.entitlements` |
| Core/Games | `core.games` |
| Core/Media | `core.media` |
| Core/Mesh | `core.mesh` |
| Core/Protocol | `core.protocol` |
| Core/QR | `core.qr` |
| Core/Transport | `core.transport` |
| Data, Data/Records, Data/Repositories | `data`, `data.records`, `data.repositories` |
| UI/... | `ui`, `ui.chat`, `ui.components`, `ui.groups`, `ui.lounge`, `ui.onboarding`, `ui.pairing`, `ui.paywall` |
| AeroMeshTests | `app/src/test/java/com/example/aerochat/<same package as code under test>` |

One Kotlin file per Swift file, same base name (`MeshRouterActor.swift` → `MeshRouterActor.kt`).

## Naming (deterministic, so parallel agents agree without talking)
- Keep Swift type, property, method and case names **exactly** (including `ID` capitalisation:
  `nodeID`, `msgID`, `conversationID`, `x25519PublicKeyRaw`). Swift argument labels become Kotlin parameter
  names (`send(frame, toNode = nodeID)` → `suspend fun send(frame: ByteArray, toNode: ByteArray): Boolean`).
  When two Swift overloads would clash in Kotlin, suffix the second with the label (`sendToNode`).
- Swift `enum` without payloads → Kotlin `enum class` with the same case names (camelCase kept, e.g. `RadioState.poweredOn`).
- Swift `enum` with associated values → `sealed interface` with `data class`/`data object` subclasses named
  like the Swift case, capitalised (`RouterEvent.MessageReceived(conversationID, msgID)`).
- Swift `static` members → `companion object` members (or top-level `object` for caseless enums like
  `MeshProtocol`, `CryptoEngine`, `LZ4Codec`, `FrameCodec`).
- Errors: Swift `Error` enums → `sealed class XxxError : Exception()` (or `Failure`) with the same case names.

## Type mapping
- `Data` → `ByteArray`. ByteArray has **reference equality**: never use it as a `Map` key / `Set` element
  or rely on `data class` equality with ByteArray fields. Use the shared helpers in
  `core.protocol.ByteEncoding.kt` (`ByteArray.toHex()`, `hexToByteArray()`, `ByteArray.contentEquals`) and key
  maps by hex `String`. `data class`es with ByteArray fields must override `equals`/`hashCode` with
  `contentEquals`/`contentHashCode`.
- `UUID` → `java.util.UUID`; raw 16 bytes via helpers `UUID.rawBytes()` / `uuidFromRawBytes(ByteArray)` in ByteEncoding.kt.
- `Date` / `TimeInterval` → `Long` epoch milliseconds / `Long` ms durations (constant names keep their Swift
  name; document unit in KDoc. E.g. `MeshProtocol.relayQueueLifetime` becomes ms: `6 * 3_600_000L`).
- `Int64` → `Long`, `UInt32` → `Int` (or `Long` when the value can exceed 2^31), `UInt8` → `Byte`/`Int`.
- `String(localized:)` → Android string resources (`R.string.xxx`) in UI; in non-UI code use a plain Italian
  string constant (keep the iOS Italian text).

## Concurrency mapping
- Swift `actor` → Kotlin `class` whose public API is `suspend fun`s, state confined with a
  `kotlinx.coroutines.sync.Mutex` (or a single-threaded `limitedParallelism(1)` dispatcher). Long-running loops
  use a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` owned by the class and cancelled in `stop()`.
- `AsyncStream<T>` returned by `events()` etc. → `Flow<T>` (back it with `MutableSharedFlow(extraBufferCapacity = 256)`
  or a `Channel`, matching the iOS buffering semantics: multiple subscribers each get events).
- `@MainActor @Observable` → plain class exposing `StateFlow`s, consumed in Compose with `collectAsStateWithLifecycle()`.
- `Task {}` → `scope.launch {}`; `Task.sleep` → `delay`.
- `Sendable`/`@unchecked Sendable` → nothing.

## Platform mapping
- CryptoKit Curve25519/Ed25519/ChaChaPoly/HKDF/SHA256 → BouncyCastle lightweight API (`X25519Agreement`,
  `Ed25519Signer`, `ChaCha20Poly1305`, `HKDFBytesGenerator`) + `java.security.MessageDigest`. Raw 32-byte keys.
- Keychain → private keys stored in `filesDir` / SharedPreferences encrypted with an AES-GCM key from the Android Keystore.
- GRDB/SQLite → Room (same table/column names as the iOS migrations).
- CoreBluetooth → `android.bluetooth` (GATT server + advertiser, scanner + GATT client, L2CAP CoC sockets).
- UserNotifications → `NotificationManagerCompat` + channels.
- StoreKit 2 → Google Play Billing (same product IDs `com.aeromesh.pass72h`, `com.aeromesh.lifetime`).
- SwiftUI → Jetpack Compose + Material3, dark OLED theme (`AeroTheme`).
- Swift Testing tests → JUnit4 (+ Robolectric where Android classes are needed, `kotlinx-coroutines-test`).
