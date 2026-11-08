package com.example.aerochat.core.media

import com.example.aerochat.core.protocol.littleEndianBytes
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Muxer/demuxer **Ogg Opus** (RFC 7845) minimale, porting byte per byte di `OggOpus.swift`.
 *
 * Le note vocali viaggiano come file Ogg Opus standard (`.opus`). Un pacchetto Opus per pagina Ogg
 * sarebbe sprecone: raggruppiamo fino a [packetsPerPage] pacchetti (20 ms ciascuno) per pagina.
 *
 * Unica differenza voluta rispetto a iOS: la stringa vendor di `OpusTags` e' `"AeroMesh Android"`
 * (metadato informativo, ignorato dai demuxer).
 */
object OggOpus {

    sealed class Failure(message: String) : Exception(message) {
        data object notOgg : Failure("notOgg")
        data object truncated : Failure("truncated")
        data object badChecksum : Failure("badChecksum")
        data object missingHead : Failure("missingHead")
        data class unsupported(val reason: String) : Failure("unsupported: $reason")
    }

    /**
     * @property channels `UInt8` su iOS.
     * @property preSkip `UInt16` su iOS (campioni a 48 kHz).
     * @property inputSampleRate `UInt32` su iOS.
     * @property outputGain `Int16` su iOS (Q7.8 dB).
     */
    data class Header(
        var channels: Int = 1,
        var preSkip: Int = 312,
        var inputSampleRate: Long = 16_000,
        var outputGain: Int = 0,
    )

    class Stream(
        val header: Header,
        /** Pacchetti Opus (frame da 20 ms a 16 kHz = 320 campioni ciascuno). */
        val packets: List<ByteArray>,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Stream && header == other.header && packets.size == other.packets.size &&
                packets.indices.all { packets[it].contentEquals(other.packets[it]) }

        override fun hashCode(): Int = packets.fold(header.hashCode()) { acc, p -> acc * 31 + p.contentHashCode() }
    }

    const val samplesPerPacket = 320          // 20 ms @ 16 kHz
    const val granuleRate: Long = 48_000      // il granule position Opus e' sempre a 48 kHz
    const val granulePerPacket: Long = 960    // 20 ms @ 48 kHz
    const val packetsPerPage = 50             // 1 s di audio per pagina

    private const val vendorString = "AeroMesh Android"
    private val oggMagic = "OggS".toByteArray(Charsets.US_ASCII)
    private val opusHeadMagic = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val opusTagsMagic = "OpusTags".toByteArray(Charsets.US_ASCII)

    // MARK: - Mux

    /** @param serial `UInt32` su iOS (bit pattern), casuale e diverso da zero di default. */
    fun encode(packets: List<ByteArray>, header: Header = Header(), serial: Int = randomSerial()): ByteArray =
        encode(packets, header, serial, vendorString)

    /** Con vendor esplicito: i test verificano l'identita' byte per byte con l'output iOS (`"AeroMesh iOS"`). */
    internal fun encode(packets: List<ByteArray>, header: Header, serial: Int, vendor: String): ByteArray {
        val out = ByteArrayOutputStream()
        val pageSequence = intArrayOf(0)

        // Pagina 0: OpusHead (BOS).
        out.write(page(listOf(opusHead(header)), 0, serial, pageSequence, 0x02))
        // Pagina 1: OpusTags.
        out.write(page(listOf(opusTags(vendor)), 0, serial, pageSequence, 0x00))

        // Pagine audio.
        var granule: Long = header.preSkip.toLong() and 0xFFFF
        var index = 0
        while (index < packets.size) {
            val end = minOf(index + packetsPerPage, packets.size)
            val slice = packets.subList(index, end)
            granule += slice.size.toLong() * granulePerPacket
            val isLast = end == packets.size
            out.write(page(slice, granule, serial, pageSequence, if (isLast) 0x04 else 0x00))
            index = end
        }
        if (packets.isEmpty()) {
            out.write(page(emptyList(), granule, serial, pageSequence, 0x04))
        }
        return out.toByteArray()
    }

    private fun randomSerial(): Int {
        var value = 0
        while (value == 0) value = Random.nextInt()
        return value
    }

    /** `OpusHead` (19 byte) — usato anche come `csd-0` del decoder `MediaCodec`. */
    internal fun opusHead(header: Header): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(opusHeadMagic)
        data.write(1)                                      // version
        data.write(header.channels and 0xFF)
        data.write(header.preSkip.toShort().littleEndianBytes)
        data.write(header.inputSampleRate.toInt().littleEndianBytes)
        data.write(header.outputGain.toShort().littleEndianBytes)
        data.write(0)                                      // channel mapping family 0 (mono/stereo)
        return data.toByteArray()
    }

    private fun opusTags(vendorName: String): ByteArray {
        val vendor = vendorName.toByteArray(Charsets.UTF_8)
        val data = ByteArrayOutputStream()
        data.write(opusTagsMagic)
        data.write(vendor.size.littleEndianBytes)
        data.write(vendor)
        data.write(0.littleEndianBytes)                    // nessun commento utente
        return data.toByteArray()
    }

    /** Costruisce una pagina Ogg con i segmenti dati (ogni pacchetto puo' occupare piu' lacing value). */
    private fun page(segments: List<ByteArray>, granule: Long, serial: Int, sequence: IntArray, flags: Int): ByteArray {
        val lacing = ByteArrayOutputStream()
        var lacingCount = 0
        val body = ByteArrayOutputStream()
        for (packet in segments) {
            var remaining = packet.size
            while (remaining >= 255) {
                lacing.write(255)
                lacingCount += 1
                remaining -= 255
            }
            lacing.write(remaining)
            lacingCount += 1
            body.write(packet)
        }
        check(lacingCount <= 255) { "troppi segmenti per pagina" }

        val page = ByteArrayOutputStream()
        page.write(oggMagic)
        page.write(0)                                      // stream structure version
        page.write(flags)
        page.write(granule.littleEndianBytes)
        page.write(serial.littleEndianBytes)
        page.write(sequence[0].littleEndianBytes)
        page.write(0.littleEndianBytes)                    // CRC placeholder
        page.write(lacingCount)
        lacing.writeTo(page)
        body.writeTo(page)

        val bytes = page.toByteArray()
        val crc = crc32(bytes)
        System.arraycopy(crc.littleEndianBytes, 0, bytes, 22, 4)
        sequence[0] += 1
        return bytes
    }

    // MARK: - Demux

    fun decode(data: ByteArray): Stream {
        if (data.size < 27 || !matches(data, 0, oggMagic)) throw Failure.notOgg
        var offset = 0
        var header: Header? = null
        val packets = ArrayList<ByteArray>()
        var pending = ByteArrayOutputStream()
        var headerPacketsSeen = 0

        while (offset + 27 <= data.size) {
            if (!matches(data, offset, oggMagic)) throw Failure.notOgg
            val segmentCount = data[offset + 26].toInt() and 0xFF
            val lacingStart = offset + 27
            if (lacingStart + segmentCount > data.size) throw Failure.truncated
            val bodyStart = lacingStart + segmentCount
            var bodyLength = 0
            for (i in 0 until segmentCount) bodyLength += data[lacingStart + i].toInt() and 0xFF
            if (bodyStart + bodyLength > data.size) throw Failure.truncated

            // Verifica CRC (con il campo CRC azzerato).
            val pageCopy = data.copyOfRange(offset, bodyStart + bodyLength)
            val storedCRC = readIntLE(pageCopy, 22)
            pageCopy[22] = 0; pageCopy[23] = 0; pageCopy[24] = 0; pageCopy[25] = 0
            if (crc32(pageCopy) != storedCRC) throw Failure.badChecksum

            var cursor = bodyStart
            for (i in 0 until segmentCount) {
                val length = data[lacingStart + i].toInt() and 0xFF
                pending.write(data, cursor, length)
                cursor += length
                if (length < 255) {
                    // Pacchetto completo.
                    val packet = pending.toByteArray()
                    if (headerPacketsSeen == 0) {
                        header = parseOpusHead(packet)
                    } else if (headerPacketsSeen >= 2 && packet.isNotEmpty()) {
                        // Un segmento di lunghezza 0 non e' un pacchetto audio: il decoder non lo accetta.
                        packets.add(packet)
                    }
                    headerPacketsSeen += 1
                    pending = ByteArrayOutputStream()
                }
            }
            offset = bodyStart + bodyLength
        }
        return Stream(header ?: throw Failure.missingHead, packets)
    }

    private fun parseOpusHead(packet: ByteArray): Header {
        if (packet.size < 19 || !matches(packet, 0, opusHeadMagic)) throw Failure.missingHead
        val version = packet[8].toInt() and 0xFF
        if (version != 1) throw Failure.unsupported("OpusHead version $version")
        val header = Header(
            channels = packet[9].toInt() and 0xFF,
            preSkip = (packet[10].toInt() and 0xFF) or ((packet[11].toInt() and 0xFF) shl 8),
            inputSampleRate = readIntLE(packet, 12).toLong() and 0xFFFF_FFFFL,
            outputGain = ((packet[16].toInt() and 0xFF) or ((packet[17].toInt() and 0xFF) shl 8)).toShort().toInt(),
        )
        if (header.channels != 1 && header.channels != 2) throw Failure.unsupported("${header.channels} canali")
        return header
    }

    private fun matches(data: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset + magic.size > data.size) return false
        for (i in magic.indices) if (data[offset + i] != magic[i]) return false
        return true
    }

    private fun readIntLE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    // MARK: - CRC-32 Ogg (polinomio 0x04C11DB7, senza riflessione, init 0, no xorout)

    private val crcTable: IntArray = IntArray(256) { index ->
        var r = index shl 24
        repeat(8) {
            r = if ((r and 0x8000_0000.toInt()) != 0) (r shl 1) xor 0x04C1_1DB7 else (r shl 1)
        }
        r
    }

    /** `UInt32` su iOS: il valore e' il bit pattern in un `Int`. */
    fun crc32(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = (crc shl 8) xor crcTable[((crc ushr 24) and 0xFF) xor (byte.toInt() and 0xFF)]
        }
        return crc
    }
}
