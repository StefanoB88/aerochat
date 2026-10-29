import Foundation

// MARK: - Data helpers

extension Data {

    /// Rappresentazione esadecimale minuscola (usata per log, UI node_id e test).
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    /// Parsing di una stringa esadecimale (lunghezza pari, case-insensitive).
    init?(hexString: String) {
        let chars = Array(hexString.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(chars.count / 2)
        var index = 0
        while index < chars.count {
            guard let high = Data.hexNibble(chars[index]), let low = Data.hexNibble(chars[index + 1]) else {
                return nil
            }
            bytes.append(high << 4 | low)
            index += 2
        }
        self.init(bytes)
    }

    private static func hexNibble(_ c: UInt8) -> UInt8? {
        switch c {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return c - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return c - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): return c - UInt8(ascii: "A") + 10
        default: return nil
        }
    }

    /// Base64 URL-safe senza padding (RFC 4648 §5), come `Base64.getUrlEncoder().withoutPadding()` su Android.
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Decodifica Base64 URL-safe, tollerante alla presenza o assenza di padding.
    init?(base64URLEncoded string: String) {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder != 0 {
            base64.append(String(repeating: "=", count: 4 - remainder))
        }
        self.init(base64Encoded: base64)
    }
}

// MARK: - Integer helpers

extension FixedWidthInteger {

    /// Byte in ordine Big-Endian (network order).
    var bigEndianBytes: Data {
        withUnsafeBytes(of: self.bigEndian) { Data($0) }
    }

    /// Byte in ordine Little-Endian (usato per il PSM nella characteristic BLE).
    var littleEndianBytes: Data {
        withUnsafeBytes(of: self.littleEndian) { Data($0) }
    }

    /// Inizializza da esattamente `MemoryLayout<Self>.size` byte Big-Endian.
    init?(bigEndianBytes data: Data) {
        guard data.count == MemoryLayout<Self>.size else { return nil }
        var value: Self = 0
        withUnsafeMutableBytes(of: &value) { buffer in
            buffer.copyBytes(from: data)
        }
        self.init(bigEndian: value)
    }

    /// Inizializza da esattamente `MemoryLayout<Self>.size` byte Little-Endian.
    init?(littleEndianBytes data: Data) {
        guard data.count == MemoryLayout<Self>.size else { return nil }
        var value: Self = 0
        withUnsafeMutableBytes(of: &value) { buffer in
            buffer.copyBytes(from: data)
        }
        self.init(littleEndian: value)
    }
}

// MARK: - UUID helpers

extension UUID {

    /// I 16 byte RFC 4122 del UUID, nello stesso ordine di `ByteBuffer.putLong(msb).putLong(lsb)` su Android.
    var rawBytes: Data {
        withUnsafeBytes(of: uuid) { Data($0) }
    }

    /// Ricostruisce un UUID da 16 byte raw.
    init?(rawBytes data: Data) {
        guard data.count == 16 else { return nil }
        var tuple: uuid_t = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        withUnsafeMutableBytes(of: &tuple) { buffer in
            buffer.copyBytes(from: data)
        }
        self.init(uuid: tuple)
    }
}

// MARK: - Date helpers

extension Date {

    /// Unix timestamp in millisecondi (`Int64`), formato di `created_at_ms`.
    var unixMilliseconds: Int64 {
        Int64((timeIntervalSince1970 * 1_000).rounded())
    }

    /// Ora corrente in millisecondi Unix.
    static var nowUnixMilliseconds: Int64 {
        Date().unixMilliseconds
    }

    init(unixMilliseconds: Int64) {
        self.init(timeIntervalSince1970: TimeInterval(unixMilliseconds) / 1_000)
    }
}
