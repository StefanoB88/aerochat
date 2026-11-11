package com.stefanobrandi.aerochat.core.protocol

import net.jpountz.lz4.LZ4Exception
import net.jpountz.lz4.LZ4Factory

/**
 * Compressione LZ4 cross-platform tramite lz4-java.
 *
 * Formato del blob prodotto: `[4 byte UInt32 Big-Endian = lunghezza originale][LZ4 raw block]`.
 * Il *raw block* di lz4-java e' identico a `COMPRESSION_LZ4_RAW` del framework `Compression` su iOS.
 */
object LZ4Codec {

    sealed class Failure(message: String) : Exception(message) {
        data object TruncatedHeader : Failure("truncated header") {
            private fun readResolve(): Any = TruncatedHeader
        }
        data class DeclaredSizeTooLarge(val size: Long) : Failure("declared size too large: $size")
        data object CorruptedBlock : Failure("corrupted block") {
            private fun readResolve(): Any = CorruptedBlock
        }
    }

    const val headerLength = 4

    private val factory: LZ4Factory by lazy { LZ4Factory.fastestInstance() }

    /**
     * Comprime [input]. Restituisce `null` se l'input e' vuoto o se la compressione fallisce
     * (il chiamante lascia allora il payload in chiaro e `is_lz4_compressed = false`).
     */
    fun compress(input: ByteArray): ByteArray? {
        if (input.isEmpty()) return null
        return try {
            val compressor = factory.fastCompressor()
            val maxLength = compressor.maxCompressedLength(input.size)
            val output = ByteArray(headerLength + maxLength)
            input.size.bigEndianBytes.copyInto(output, 0)
            val written = compressor.compress(input, 0, input.size, output, headerLength, maxLength)
            if (written <= 0) null else output.copyOf(headerLength + written)
        } catch (_: LZ4Exception) {
            null
        }
    }

    /**
     * Decomprime un blob prodotto da [compress] (o dall'equivalente iOS).
     *
     * Sicuro con input malevolo: la dimensione dichiarata e' validata prima di allocare e si usa il
     * `safeDecompressor` (controlla i limiti sia in lettura sia in scrittura); il blocco deve
     * produrre esattamente la lunghezza dichiarata.
     */
    @Throws(Failure::class)
    fun decompress(input: ByteArray, maxDecompressedBytes: Int = MeshProtocol.maxDecompressedBytes): ByteArray {
        if (input.size <= headerLength) throw Failure.TruncatedHeader
        val originalSize = uInt32FromBigEndianBytes(input.copyOf(headerLength)) ?: throw Failure.TruncatedHeader
        if (originalSize <= 0L || originalSize > maxDecompressedBytes) {
            throw Failure.DeclaredSizeTooLarge(originalSize)
        }
        val size = originalSize.toInt()
        val destination = ByteArray(size)
        val read = try {
            factory.safeDecompressor().decompress(input, headerLength, input.size - headerLength, destination, 0, size)
        } catch (_: LZ4Exception) {
            throw Failure.CorruptedBlock
        } catch (_: IndexOutOfBoundsException) {
            throw Failure.CorruptedBlock
        }
        if (read != size) throw Failure.CorruptedBlock
        return destination
    }
}
