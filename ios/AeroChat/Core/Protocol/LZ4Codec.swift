import Foundation
import Compression

/// Compressione LZ4 cross-platform tramite il framework nativo `Compression`.
///
/// Formato del blob prodotto: `[4 byte UInt32 Big-Endian = lunghezza originale][LZ4 raw block]`.
/// Il *raw block* (`COMPRESSION_LZ4_RAW`) e' identico a quello di lz4-java su Android
/// (`LZ4Factory.fastestInstance().fastCompressor().compress(...)`), che per decomprimere
/// richiede la lunghezza originale: da qui il prefisso di 4 byte.
enum LZ4Codec {

    enum Failure: Error, Equatable {
        case truncatedHeader
        case declaredSizeTooLarge(Int)
        case corruptedBlock
    }

    static let headerLength = 4

    /// Comprime `input`. Restituisce `nil` se l'input e' vuoto o se la compressione non conviene
    /// (il chiamante lascia allora il payload in chiaro e `is_lz4_compressed = false`).
    static func compress(_ input: Data) -> Data? {
        guard !input.isEmpty, input.count <= Int(UInt32.max) else { return nil }
        // Limite superiore teorico LZ4: n + n/255 + 16.
        let capacity = input.count + input.count / 255 + 16
        var destination = [UInt8](repeating: 0, count: capacity)
        let written = input.withUnsafeBytes { (source: UnsafeRawBufferPointer) -> Int in
            guard let base = source.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return compression_encode_buffer(&destination, capacity, base, input.count, nil, COMPRESSION_LZ4_RAW)
        }
        guard written > 0 else { return nil }
        var output = Data(capacity: headerLength + written)
        output.append(UInt32(input.count).bigEndianBytes)
        output.append(contentsOf: destination[0..<written])
        return output
    }

    /// Decomprime un blob prodotto da `compress` (o dall'equivalente Android).
    static func decompress(_ input: Data, maxDecompressedBytes: Int = MeshProtocol.maxDecompressedBytes) throws -> Data {
        guard input.count > headerLength else { throw Failure.truncatedHeader }
        guard let declared = UInt32(bigEndianBytes: input.prefix(headerLength)) else { throw Failure.truncatedHeader }
        let originalSize = Int(declared)
        guard originalSize > 0, originalSize <= maxDecompressedBytes else {
            throw Failure.declaredSizeTooLarge(originalSize)
        }
        let body = input.dropFirst(headerLength)
        var destination = [UInt8](repeating: 0, count: originalSize)
        let read = body.withUnsafeBytes { (source: UnsafeRawBufferPointer) -> Int in
            guard let base = source.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return compression_decode_buffer(&destination, originalSize, base, body.count, nil, COMPRESSION_LZ4_RAW)
        }
        guard read == originalSize else { throw Failure.corruptedBlock }
        return Data(destination)
    }
}
