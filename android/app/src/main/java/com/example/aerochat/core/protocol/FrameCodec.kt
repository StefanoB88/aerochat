package com.example.aerochat.core.protocol

/** Framing sullo stream L2CAP: `[4 byte UInt32 Big-Endian = N][N byte MeshEnvelope serializzato]`. */
object FrameCodec {

    sealed class Failure(message: String) : Exception(message) {
        data class FrameTooLarge(val size: Long) : Failure("frame too large: $size")
        data object EmptyFrame : Failure("empty frame") {
            private fun readResolve(): Any = EmptyFrame
        }
    }

    const val headerLength = 4

    /** Avvolge un envelope serializzato nel frame con prefisso di lunghezza. */
    @Throws(Failure::class)
    fun frame(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) throw Failure.EmptyFrame
        if (payload.size > MeshProtocol.maxFrameBytes) throw Failure.FrameTooLarge(payload.size.toLong())
        val out = ByteArray(headerLength + payload.size)
        payload.size.bigEndianBytes.copyInto(out, 0)
        payload.copyInto(out, headerLength)
        return out
    }

    /**
     * Parser incrementale: accumula i byte che arrivano dallo stream (anche a pezzi
     * arbitrari) ed emette i frame completi appena disponibili. Non thread-safe (come la struct Swift).
     */
    class Parser {
        private var buffer = ByteArray(0)
        private var count = 0

        /** Byte in attesa di completare un frame (per diagnostica). */
        val pendingByteCount: Int get() = count

        /** Aggiunge [chunk] al buffer e restituisce tutti i payload completi estratti. */
        @Throws(Failure::class)
        fun append(chunk: ByteArray): List<ByteArray> {
            ensureCapacity(count + chunk.size)
            chunk.copyInto(buffer, count)
            count += chunk.size

            val frames = mutableListOf<ByteArray>()
            var offset = 0
            while (count - offset >= headerLength) {
                val payloadLength = uInt32FromBigEndianBytes(buffer.copyOfRange(offset, offset + headerLength)) ?: break
                if (payloadLength <= 0L) {
                    reset()
                    throw Failure.EmptyFrame
                }
                if (payloadLength > MeshProtocol.maxFrameBytes) {
                    reset()
                    throw Failure.FrameTooLarge(payloadLength)
                }
                val total = headerLength + payloadLength.toInt()
                if (count - offset < total) break
                frames.add(buffer.copyOfRange(offset + headerLength, offset + total))
                offset += total
            }
            if (offset > 0) {
                buffer.copyInto(buffer, 0, offset, count)
                count -= offset
            }
            return frames
        }

        fun reset() {
            buffer = ByteArray(0)
            count = 0
        }

        private fun ensureCapacity(required: Int) {
            if (required <= buffer.size) return
            var newSize = maxOf(buffer.size * 2, 256)
            while (newSize < required) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }
    }
}
