package com.stefanobrandi.aerochat.core.media

/**
 * Codec "Opus" finto per i test JVM (MediaCodec esiste solo su dispositivo): ogni frame da 320 campioni
 * diventa un pacchetto di 81 byte (TOC 0x08 + 80 campioni a 8 bit), e il decoder lo riespande a 320
 * campioni. Basta per verificare framing, Ogg, forma d'onda e pipeline delle chiamate.
 */
class FakeOpusEncoder : OpusCodec.FrameEncoder {
    private var pending = ShortArray(0)
    var closed = false

    override fun encode(pcm: ShortArray): List<ByteArray> {
        pending += pcm
        val out = ArrayList<ByteArray>()
        while (pending.size >= OpusCodec.frameSamples) {
            val frame = pending.copyOfRange(0, OpusCodec.frameSamples)
            pending = pending.copyOfRange(OpusCodec.frameSamples, pending.size)
            out += ByteArray(81) { i -> if (i == 0) 0x08 else (frame[(i - 1) * 4].toInt() shr 8).toByte() }
        }
        return out
    }

    override fun finish(): List<ByteArray> = emptyList()

    override fun close() {
        closed = true
    }
}

class FakeOpusDecoder : OpusCodec.FrameDecoder {
    override fun decode(packet: ByteArray): ShortArray {
        require(packet.isNotEmpty() && packet[0] == 0x08.toByte()) { "pacchetto non valido" }
        return ShortArray(OpusCodec.frameSamples) { i -> (packet[1 + i / 4].toInt() shl 8).toShort() }
    }

    override fun finish(): ShortArray = ShortArray(0)

    override fun close() {}
}

object FakeOpus {
    fun install() {
        OpusCodec.encoderFactory = { FakeOpusEncoder() }
        OpusCodec.decoderFactory = { FakeOpusDecoder() }
    }
}
