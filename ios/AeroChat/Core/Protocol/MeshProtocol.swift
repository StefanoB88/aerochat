import Foundation

/// Single Source of Truth delle costanti del contratto binario AeroMesh v1.
///
/// Ogni valore qui dentro e' condiviso **byte per byte** con la controparte Android.
/// Le decisioni di encoding che il documento di specifica lascia implicite sono
/// fissate qui e documentate per il team Android:
///
/// - `created_at_ms` nella firma Ed25519 e' serializzato come `Int64` **Big-Endian** (8 byte).
/// - I payload LZ4 usano il formato *raw block* (lz4-java `LZ4Compressor.compress`) preceduto da
///   4 byte `UInt32` Big-Endian con la lunghezza originale, necessaria al decompressore.
/// - La chiave simmetrica della Lounge Pubblica e' `SHA256("AeroMesh-Public-Cabin-v1")` (32 byte).
/// - `group_uuid_bytes` sono i 16 byte RFC 4122 del UUID (equivalenti a
///   `ByteBuffer.putLong(msb).putLong(lsb)` su Android).
/// - `READ_RECEIPT` (diretto al mittente, cifrato 1-a-1): `extra_data` = concatenazione di N `msg_id`
///   da 16 byte (max 64 per pacchetto) dei messaggi letti. Nei gruppi ogni lettore viene registrato
///   (`message_reads`) e la spunta blu arriva solo quando hanno letto tutti i membri noti (mittente escluso);
///   la doppia spunta grigia (`DELIVERY_ACK`) resta al primo destinatario che conferma.
/// - `PROFILE_UPDATE` (diretto, cifrato 1-a-1): `extra_data` = `ProfileUpdate` serializzato; inviato a
///   tutti i contatti quando il profilo cambia e a ogni vicino quando si apre il link.
/// - Gli UUID BLE a 128 bit sono scritti nell'advertising cosi' come sono (16 byte): con i 3 byte
///   di Flags il pacchetto legacy pesa 21 byte, entro il limite di 31.
enum MeshProtocol {

    // MARK: - BLE GATT

    /// Primary Service UUID pubblicizzato nel pacchetto Legacy Advertising.
    static let serviceUUIDString = "F1164700-B1E0-4A18-9C0D-000000000001"
    /// Characteristic (Read) che espone il PSM L2CAP come `UInt16` Little-Endian.
    static let psmCharacteristicUUIDString = "F1164700-B1E0-4A18-9C0D-000000000002"
    /// Characteristic (Read / Write Without Response / Notify) con gli 8 byte del `node_id`.
    static let nodeInfoCharacteristicUUIDString = "F1164700-B1E0-4A18-9C0D-000000000003"
    /// Limite del pacchetto Legacy Advertising BLE 4.x.
    static let maxLegacyAdvertisingBytes = 31

    // MARK: - MeshEnvelope

    static let msgIDLength = 16
    static let nodeIDLength = 8
    static let publicKeyLength = 32
    static let signatureLength = 64
    static let aeadNonceLength = 12
    static let aeadTagLength = 16
    static let symmetricKeyLength = 32

    static let defaultTTL: UInt32 = 5
    static let maxTTL: UInt32 = 7
    static let ackTTL: UInt32 = 5

    /// Oltre questa dimensione il payload **non** viene ritrasmesso multi-hop.
    static let maxRelayPayloadBytes = 16_384
    /// Limite superiore di `created_at_ms` accettato (1 gennaio 2100).
    static let maxCreatedAtMs: Int64 = 4_102_444_800_000
    /// Membri massimi annunciati in un singolo Group Key Gossip.
    static let maxGossipMembers = 64
    /// Sopra questa soglia (byte di InnerPayload serializzato) si applica LZ4.
    static let lz4CompressionThreshold = 128
    /// Dimensione massima di un singolo frame sullo stream L2CAP (protezione memoria).
    static let maxFrameBytes = 1_048_576
    /// Dimensione massima accettata dopo decompressione LZ4.
    static let maxDecompressedBytes = 4 * 1_048_576

    // MARK: - Profilo

    /// Lato della foto profilo quadrata (WebP) inviata con `PROFILE_UPDATE`.
    static let avatarPixelSize = 128
    /// Dimensione massima accettata della foto profilo.
    static let maxAvatarBytes = 12_288
    /// Lunghezza massima del nickname (onboarding, profilo e `PROFILE_UPDATE`).
    static let maxNicknameLength = 32

    // MARK: - Chiamate

    /// Sotto questo RSSI (dBm) il link BLE perde troppi pacchetti per l'audio in tempo reale.
    static let minimumCallRSSI = -90
    /// Frame Opus da 20 ms per pacchetto `CALL_AUDIO` (60 ms: ~17 pacchetti/s, ~5 KB/s con l'overhead).
    static let callFramesPerPacket = 3
    /// Squilli senza risposta prima di chiudere la chiamata.
    static let callRingTimeout: TimeInterval = 35
    /// Senza audio dal compagno per cosi' tanto la chiamata viene chiusa (link perso).
    static let callMediaTimeout: TimeInterval = 10

    // MARK: - Store-and-Forward

    static let relayQueueMaxCount = 200
    static let relayQueueMaxBytes = 2 * 1_048_576
    static let relayQueueLifetime: TimeInterval = 6 * 3_600
    static let seenPacketLifetime: TimeInterval = 6 * 3_600

    // MARK: - Prodotto

    /// Durata della Cabina Pubblica e dei suoi messaggi.
    static let loungeLifetime: TimeInterval = 12 * 3_600
    /// Durata del Flight Group Pass propagato via `PRO_PASS_GRANT`.
    static let proPassDuration: TimeInterval = 72 * 3_600
    /// `recipient_id` della Lounge Pubblica: 8 byte a zero.
    static let loungeRecipientID = Data(repeating: 0, count: nodeIDLength)
    /// `sender_node_id` per relay anonimo: 8 byte a zero.
    static let anonymousSenderID = Data(repeating: 0, count: nodeIDLength)

    enum ProductID {
        static let pass72h = "com.aeromesh.pass72h"
        static let lifetime = "com.aeromesh.lifetime"
    }

    // MARK: - Etichette crittografiche

    static let hkdfSalt = Data("AeroMesh-Salt-v1".utf8)
    static let hkdfInfo = Data("AeroMesh-Msg-v1".utf8)
    static let publicCabinLabel = Data("AeroMesh-Public-Cabin-v1".utf8)

    // MARK: - QR Code

    static let qrScheme = "aeromesh"
    static let qrVersion = "v1"
}
