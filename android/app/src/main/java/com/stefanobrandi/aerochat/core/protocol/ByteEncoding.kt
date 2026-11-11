package com.stefanobrandi.aerochat.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID

// MARK: - ByteArray helpers

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Rappresentazione esadecimale minuscola (usata per log, UI node_id, chiavi di Map e test). Swift: `Data.hexString`. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}

/** Alias di [toHex] con il nome Swift. */
val ByteArray.hexString: String get() = toHex()

/** Parsing di una stringa esadecimale (lunghezza pari, case-insensitive). `null` se non valida. Swift: `Data(hexString:)`. */
fun String.hexToByteArray(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    var i = 0
    while (i < length) {
        val high = hexNibble(this[i]) ?: return null
        val low = hexNibble(this[i + 1]) ?: return null
        out[i / 2] = ((high shl 4) or low).toByte()
        i += 2
    }
    return out
}

private fun hexNibble(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> null
}

/** Base64 URL-safe senza padding (RFC 4648 §5). Identico a `Data.base64URLEncodedString()` su iOS. */
fun ByteArray.base64URLEncodedString(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

/** Base64 standard con padding (`Data.base64EncodedString()` su iOS). */
fun ByteArray.base64EncodedString(): String = Base64.getEncoder().encodeToString(this)

/**
 * Decodifica Base64 URL-safe, tollerante alla presenza o assenza di padding (`Data(base64URLEncoded:)`).
 * Come su iOS accetta anche l'alfabeto standard (`+`/`/`), perche' la stringa viene normalizzata
 * a Base64 standard prima della decodifica stretta.
 */
fun String.base64URLDecoded(): ByteArray? {
    var base64 = replace('-', '+').replace('_', '/')
    val remainder = base64.length % 4
    if (remainder != 0) base64 += "=".repeat(4 - remainder)
    return base64.base64Decoded()
}

/** Decodifica Base64 standard stretta (`Data(base64Encoded:)`): `null` se non valida. */
fun String.base64Decoded(): ByteArray? =
    try {
        Base64.getDecoder().decode(this)
    } catch (_: IllegalArgumentException) {
        null
    }

// MARK: - Integer helpers (Big-Endian = network order)

val Short.bigEndianBytes: ByteArray
    get() = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(this).array()
val Short.littleEndianBytes: ByteArray
    get() = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(this).array()
val Int.bigEndianBytes: ByteArray
    get() = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(this).array()
val Int.littleEndianBytes: ByteArray
    get() = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(this).array()
val Long.bigEndianBytes: ByteArray
    get() = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(this).array()
val Long.littleEndianBytes: ByteArray
    get() = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(this).array()

/** `UInt16(...).bigEndianBytes` / `littleEndianBytes`: 2 byte dai 16 bit bassi di [value]. */
fun uInt16BigEndianBytes(value: Int): ByteArray = value.toShort().bigEndianBytes
fun uInt16LittleEndianBytes(value: Int): ByteArray = value.toShort().littleEndianBytes

/** Decodifica da esattamente N byte (come `init?(bigEndianBytes:)`): `null` se la lunghezza e' sbagliata. */
fun shortFromBigEndianBytes(data: ByteArray): Short? =
    if (data.size == 2) ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).short else null
fun shortFromLittleEndianBytes(data: ByteArray): Short? =
    if (data.size == 2) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short else null
fun intFromBigEndianBytes(data: ByteArray): Int? =
    if (data.size == 4) ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).int else null
fun intFromLittleEndianBytes(data: ByteArray): Int? =
    if (data.size == 4) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int else null
fun longFromBigEndianBytes(data: ByteArray): Long? =
    if (data.size == 8) ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).long else null
fun longFromLittleEndianBytes(data: ByteArray): Long? =
    if (data.size == 8) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).long else null

/** `UInt16(littleEndianBytes:)` / `UInt16(bigEndianBytes:)` come Int senza segno (0...65535). */
fun uInt16FromLittleEndianBytes(data: ByteArray): Int? = shortFromLittleEndianBytes(data)?.let { it.toInt() and 0xFFFF }
fun uInt16FromBigEndianBytes(data: ByteArray): Int? = shortFromBigEndianBytes(data)?.let { it.toInt() and 0xFFFF }

/** `UInt32(bigEndianBytes:)` / `UInt32(littleEndianBytes:)` come Long senza segno (0...2^32-1). */
fun uInt32FromBigEndianBytes(data: ByteArray): Long? = intFromBigEndianBytes(data)?.let { it.toLong() and 0xFFFF_FFFFL }
fun uInt32FromLittleEndianBytes(data: ByteArray): Long? = intFromLittleEndianBytes(data)?.let { it.toLong() and 0xFFFF_FFFFL }

// MARK: - UUID helpers

/** I 16 byte RFC 4122 del UUID (`ByteBuffer.putLong(msb).putLong(lsb)`), identici a `UUID.rawBytes` su iOS. */
fun UUID.rawBytes(): ByteArray =
    ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        .putLong(mostSignificantBits)
        .putLong(leastSignificantBits)
        .array()

/** Ricostruisce un UUID da 16 byte raw (`UUID(rawBytes:)`): `null` se la lunghezza non e' 16. */
fun uuidFromRawBytes(data: ByteArray): UUID? {
    if (data.size != 16) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
    return UUID(buffer.long, buffer.long)
}

private val CANONICAL_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Parsing stretto come `UUID(uuidString:)` di Foundation: solo il formato canonico 8-4-4-4-12
 * (case-insensitive). `java.util.UUID.fromString` e' piu' permissivo, quindi non va usato da solo.
 */
fun uuidFromString(string: String): UUID? =
    if (CANONICAL_UUID.matches(string)) UUID.fromString(string) else null

// MARK: - Date helpers

/** Ora corrente in millisecondi Unix (`Date.nowUnixMilliseconds`). */
val nowUnixMilliseconds: Long get() = System.currentTimeMillis()
