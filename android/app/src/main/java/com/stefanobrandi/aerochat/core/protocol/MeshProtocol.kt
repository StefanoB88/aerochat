package com.stefanobrandi.aerochat.core.protocol

/**
 * Single Source of Truth delle costanti del contratto binario AeroMesh v1.
 *
 * Ogni valore qui dentro e' condiviso **byte per byte** con la controparte iOS.
 *
 * - `created_at_ms` nella firma Ed25519 e' serializzato come `Int64` **Big-Endian** (8 byte).
 * - I payload LZ4 usano il formato *raw block* (lz4-java `LZ4Compressor.compress`) preceduto da
 *   4 byte `UInt32` Big-Endian con la lunghezza originale, necessaria al decompressore.
 * - La chiave simmetrica della Lounge Pubblica e' `SHA256("AeroMesh-Public-Cabin-v1")` (32 byte).
 * - `group_uuid_bytes` sono i 16 byte RFC 4122 del UUID (`ByteBuffer.putLong(msb).putLong(lsb)`).
 * - `READ_RECEIPT` (diretto al mittente, cifrato 1-a-1): `extra_data` = concatenazione di N `msg_id`
 *   da 16 byte (max 64 per pacchetto) dei messaggi letti. Nei gruppi ogni lettore viene registrato
 *   (`message_reads`) e la spunta blu arriva solo quando hanno letto tutti i membri noti (mittente escluso);
 *   la doppia spunta grigia (`DELIVERY_ACK`) resta al primo destinatario che conferma.
 * - `PROFILE_UPDATE` (diretto, cifrato 1-a-1): `extra_data` = `ProfileUpdate` serializzato.
 * - Gli UUID BLE a 128 bit sono scritti nell'advertising cosi' come sono (16 byte).
 *
 * Le durate (`TimeInterval` su iOS) sono espresse in **millisecondi** (`Long`).
 */
object MeshProtocol {

    // MARK: - BLE GATT

    /** Primary Service UUID pubblicizzato nel pacchetto Legacy Advertising. */
    const val serviceUUIDString = "F1164700-B1E0-4A18-9C0D-000000000001"
    /** Characteristic (Read) che espone il PSM L2CAP come `UInt16` Little-Endian. */
    const val psmCharacteristicUUIDString = "F1164700-B1E0-4A18-9C0D-000000000002"
    /** Characteristic (Read / Write Without Response / Notify) con gli 8 byte del `node_id`. */
    const val nodeInfoCharacteristicUUIDString = "F1164700-B1E0-4A18-9C0D-000000000003"
    /** Limite del pacchetto Legacy Advertising BLE 4.x. */
    const val maxLegacyAdvertisingBytes = 31

    // MARK: - MeshEnvelope

    const val msgIDLength = 16
    const val nodeIDLength = 8
    const val publicKeyLength = 32
    const val signatureLength = 64
    const val aeadNonceLength = 12
    const val aeadTagLength = 16
    const val symmetricKeyLength = 32

    /** `UInt32` su iOS. */
    const val defaultTTL: Int = 5
    const val maxTTL: Int = 7
    const val ackTTL: Int = 5

    /** Oltre questa dimensione il payload **non** viene ritrasmesso multi-hop. */
    const val maxRelayPayloadBytes = 16_384
    /** Limite superiore di `created_at_ms` accettato (1 gennaio 2100). */
    const val maxCreatedAtMs: Long = 4_102_444_800_000L
    /** Membri massimi annunciati in un singolo Group Key Gossip. */
    const val maxGossipMembers = 64
    /** Sopra questa soglia (byte di InnerPayload serializzato) si applica LZ4. */
    const val lz4CompressionThreshold = 128
    /** Dimensione massima di un singolo frame sullo stream L2CAP (protezione memoria). */
    const val maxFrameBytes = 1_048_576
    /** Dimensione massima accettata dopo decompressione LZ4. */
    const val maxDecompressedBytes = 4 * 1_048_576

    // MARK: - Profilo

    /** Lato della foto profilo quadrata (WebP) inviata con `PROFILE_UPDATE`. */
    const val avatarPixelSize = 128
    /** Dimensione massima accettata della foto profilo. */
    const val maxAvatarBytes = 12_288
    /** Lunghezza massima del nickname (onboarding, profilo e `PROFILE_UPDATE`). */
    const val maxNicknameLength = 32

    // MARK: - Chiamate

    /** Sotto questo RSSI (dBm) il link BLE perde troppi pacchetti per l'audio in tempo reale. */
    const val minimumCallRSSI = -90
    /** Frame Opus da 20 ms per pacchetto `CALL_AUDIO`. */
    const val callFramesPerPacket = 3
    /** Squilli senza risposta prima di chiudere la chiamata (ms). */
    const val callRingTimeout: Long = 35_000L
    /** Senza audio dal compagno per cosi' tanto la chiamata viene chiusa (ms). */
    const val callMediaTimeout: Long = 10_000L

    // MARK: - Store-and-Forward

    const val relayQueueMaxCount = 200
    const val relayQueueMaxBytes = 2 * 1_048_576
    /** ms. */
    const val relayQueueLifetime: Long = 6 * 3_600_000L
    /** ms. */
    const val seenPacketLifetime: Long = 6 * 3_600_000L

    // MARK: - Prodotto

    /** Durata della Cabina Pubblica e dei suoi messaggi (ms). */
    const val loungeLifetime: Long = 12 * 3_600_000L
    /** Durata del Flight Group Pass propagato via `PRO_PASS_GRANT` (ms). */
    const val proPassDuration: Long = 72 * 3_600_000L

    // MARK: - Modifica e cancellazione (MESSAGE_REVISION)

    /** Finestra entro cui l'autore puo' modificare o cancellare per tutti un proprio messaggio (ms). */
    const val revisionWindow: Long = 15 * 60_000L
    /** Tolleranza in ricezione per orologi non allineati, oltre a [revisionWindow] (ms). */
    const val revisionClockTolerance: Long = 2 * 60_000L
    /** Lunghezza massima del nuovo testo di un EDIT (caratteri, dopo il trim). */
    const val maxRevisionTextLength = 4_000
    /** Revisioni arrivate prima del messaggio originale tenute in memoria. */
    const val pendingRevisionCapacity = 64

    // MARK: - Abbinamento (PAIR_HELLO) e "sta scrivendo" (TYPING)

    /** Lunghezza del token di abbinamento `pt` nel QR (byte casuali). */
    const val pairingTokenLength = 16
    /** Validita' di un token di abbinamento (ms). */
    const val pairingTokenLifetime: Long = 7 * 24 * 3_600_000L
    /** Token di abbinamento validi tenuti al massimo (i piu' vecchi vengono scartati). */
    const val maxPairingTokens = 16
    /** ttl dei pacchetti TYPING (diretto e gruppo): ha senso solo vicino. */
    const val typingTTL: Int = 2
    /** Il ricevente fa scadere il "sta scrivendo" dopo questo tempo dall'ultimo `true` (ms). */
    const val typingExpiry: Long = 6_000L
    /** Il mittente ripete `true` al massimo ogni ... (ms). */
    const val typingRefreshInterval: Long = 4_000L
    /** Senza battiture per questo tempo il mittente invia `false` (ms). */
    const val typingIdleTimeout: Long = 5_000L

    /** `recipient_id` della Lounge Pubblica: 8 byte a zero. Restituisce sempre una copia nuova. */
    val loungeRecipientID: ByteArray get() = ByteArray(nodeIDLength)
    /** `sender_node_id` per relay anonimo: 8 byte a zero. Restituisce sempre una copia nuova. */
    val anonymousSenderID: ByteArray get() = ByteArray(nodeIDLength)

    /** Durata del Pass Volo Singolo (ms). */
    const val singleFlightPassDuration: Long = 24 * 3_600_000L

    object ProductID {
        const val pass24h = "com.aeromesh.pass24h"
        const val pass72h = "com.aeromesh.pass72h"
        const val lifetime = "com.aeromesh.lifetime"
    }

    // MARK: - Etichette crittografiche

    val hkdfSalt: ByteArray get() = "AeroMesh-Salt-v1".toByteArray(Charsets.UTF_8)
    val hkdfInfo: ByteArray get() = "AeroMesh-Msg-v1".toByteArray(Charsets.UTF_8)
    val publicCabinLabel: ByteArray get() = "AeroMesh-Public-Cabin-v1".toByteArray(Charsets.UTF_8)
    /** `info` HKDF della chiave di PAIR_HELLO (IKM = token del QR, salt = [hkdfSalt]). */
    val pairHelloInfo: ByteArray get() = "AeroMesh-PairHello-v1".toByteArray(Charsets.UTF_8)

    // MARK: - QR Code

    const val qrScheme = "aeromesh"
    const val qrVersion = "v1"
}
