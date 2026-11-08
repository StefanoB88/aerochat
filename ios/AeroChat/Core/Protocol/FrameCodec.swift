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

        init() {}

        /// Byte in attesa di completare un frame (per diagnostica).
        var pendingByteCount: Int { buffer.count }

        /// Aggiunge `chunk` al buffer e restituisce tutti i payload completi estratti.
        mutating func append(_ chunk: Data) throws -> [Data] {
            buffer.append(chunk)
            var frames: [Data] = []
            while buffer.count >= FrameCodec.headerLength {
                guard let length = UInt32(bigEndianBytes: buffer.prefix(FrameCodec.headerLength)) else { break }
                let payloadLength = Int(length)
                guard payloadLength > 0 else {
                    buffer.removeAll()
                    throw Failure.emptyFrame
                }
                guard payloadLength <= MeshProtocol.maxFrameBytes else {
                    buffer.removeAll()
                    throw Failure.frameTooLarge(payloadLength)
                }
                let total = FrameCodec.headerLength + payloadLength
                guard buffer.count >= total else { break }
                let start = buffer.startIndex + FrameCodec.headerLength
                let end = buffer.startIndex + total
                frames.append(Data(buffer[start..<end]))
                buffer.removeSubrange(buffer.startIndex..<end)
            }
            return frames
        }

        mutating func reset() {
            buffer.removeAll()
        }
    }
}
