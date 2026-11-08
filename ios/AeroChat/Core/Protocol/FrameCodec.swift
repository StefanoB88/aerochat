import Foundation

/// Framing sullo stream L2CAP: `[4 byte UInt32 Big-Endian = N][N byte MeshEnvelope serializzato]`.
enum FrameCodec {

    enum Failure: Error, Equatable {
        case frameTooLarge(Int)
        case emptyFrame
    }

    static let headerLength = 4

    /// Avvolge un envelope serializzato nel frame con prefisso di lunghezza.
    static func frame(_ payload: Data) throws -> Data {
        guard !payload.isEmpty else { throw Failure.emptyFrame }
        guard payload.count <= MeshProtocol.maxFrameBytes else { throw Failure.frameTooLarge(payload.count) }
        var out = Data(capacity: headerLength + payload.count)
        out.append(UInt32(payload.count).bigEndianBytes)
        out.append(payload)
        return out
    }

    /// Parser incrementale: accumula i byte che arrivano dall'`InputStream` (anche a pezzi
    /// arbitrari) ed emette i frame completi appena disponibili.
    struct Parser: Sendable {
        private var buffer = Data()
        /// Inizio dei byte non ancora consumati in `buffer` (offset relativo a `startIndex`).
        /// Si avanza il cursore invece di `removeSubrange` a ogni frame: con molti frame piccoli in un
        /// solo chunk la rimozione in testa ricopiava ogni volta il resto del buffer (O(n^2)).
        private var readOffset = 0

        init() {}

        /// Byte in attesa di completare un frame (per diagnostica).
        var pendingByteCount: Int { buffer.count - readOffset }

        /// Aggiunge `chunk` al buffer e restituisce tutti i payload completi estratti.
        mutating func append(_ chunk: Data) throws -> [Data] {
            buffer.append(chunk)
            var frames: [Data] = []
            defer { compact() }
            while buffer.count - readOffset >= FrameCodec.headerLength {
                let headerStart = buffer.startIndex + readOffset
                let length = buffer[headerStart..<headerStart + FrameCodec.headerLength]
                    .reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
                let payloadLength = Int(length)
                guard payloadLength > 0 else {
                    reset()
                    throw Failure.emptyFrame
                }
                guard payloadLength <= MeshProtocol.maxFrameBytes else {
                    reset()
                    throw Failure.frameTooLarge(payloadLength)
                }
                let total = FrameCodec.headerLength + payloadLength
                guard buffer.count - readOffset >= total else { break }
                let start = headerStart + FrameCodec.headerLength
                frames.append(Data(buffer[start..<headerStart + total]))
                readOffset += total
            }
            return frames
        }

        /// Una sola rimozione in testa per chiamata (non per frame).
        private mutating func compact() {
            guard readOffset > 0 else { return }
            if readOffset >= buffer.count {
                buffer.removeAll(keepingCapacity: true)
            } else {
                buffer.removeSubrange(buffer.startIndex..<buffer.startIndex + readOffset)
            }
            readOffset = 0
        }

        mutating func reset() {
            buffer.removeAll()
            readOffset = 0
        }
    }
}
