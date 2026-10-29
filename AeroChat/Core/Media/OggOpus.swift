import Foundation

/// Muxer/demuxer **Ogg Opus** (RFC 7845) minimale.
///
/// Scelta di contratto: le note vocali viaggiano come file Ogg Opus standard (`.opus`), che Android
/// riproduce nativamente con `MediaPlayer`/`MediaExtractor` e che iOS legge con questo demuxer +
/// `AVAudioConverter`. Un pacchetto Opus per pagina Ogg sarebbe sprecone: raggruppiamo fino a
/// `packetsPerPage` pacchetti (20 ms ciascuno) per pagina.
enum OggOpus {

    enum Failure: Error, Equatable {
        case notOgg
        case truncated
        case badChecksum
        case missingHead
        case unsupported(String)
    }

    struct Header: Sendable, Equatable {
        var channels: UInt8 = 1
        var preSkip: UInt16 = 312
        var inputSampleRate: UInt32 = 16_000
        var outputGain: Int16 = 0
    }

    struct Stream: Sendable, Equatable {
        let header: Header
        /// Pacchetti Opus (frame da 20 ms a 16 kHz = 320 campioni ciascuno).
        let packets: [Data]
    }

    static let samplesPerPacket = 320          // 20 ms @ 16 kHz
    static let granuleRate: UInt64 = 48_000    // il granule position Opus e' sempre a 48 kHz
    static let granulePerPacket: UInt64 = 960  // 20 ms @ 48 kHz
    static let packetsPerPage = 50             // 1 s di audio per pagina

    // MARK: - Mux

    static func encode(packets: [Data], header: Header = Header(), serial: UInt32 = UInt32.random(in: 1...UInt32.max)) -> Data {
        var out = Data()
        var pageSequence: UInt32 = 0

        // Pagina 0: OpusHead (BOS).
        out.append(page(segments: [opusHead(header)], granule: 0, serial: serial, sequence: &pageSequence, flags: 0x02))
        // Pagina 1: OpusTags.
        out.append(page(segments: [opusTags()], granule: 0, serial: serial, sequence: &pageSequence, flags: 0x00))

        // Pagine audio.
        var granule: UInt64 = UInt64(header.preSkip)
        var index = 0
        while index < packets.count {
            let end = min(index + packetsPerPage, packets.count)
            let slice = Array(packets[index..<end])
            granule += UInt64(slice.count) * granulePerPacket
            let isLast = end == packets.count
            out.append(page(segments: slice, granule: granule, serial: serial, sequence: &pageSequence, flags: isLast ? 0x04 : 0x00))
            index = end
        }
        if packets.isEmpty {
            out.append(page(segments: [], granule: granule, serial: serial, sequence: &pageSequence, flags: 0x04))
        }
        return out
    }

    private static func opusHead(_ header: Header) -> Data {
        var data = Data("OpusHead".utf8)
        data.append(1)                                   // version
        data.append(header.channels)
        data.append(header.preSkip.littleEndianBytes)
        data.append(header.inputSampleRate.littleEndianBytes)
        data.append(UInt16(bitPattern: header.outputGain).littleEndianBytes)
        data.append(0)                                   // channel mapping family 0 (mono/stereo)
        return data
    }

    private static func opusTags() -> Data {
        let vendor = Data("AeroMesh iOS".utf8)
        var data = Data("OpusTags".utf8)
        data.append(UInt32(vendor.count).littleEndianBytes)
        data.append(vendor)
        data.append(UInt32(0).littleEndianBytes)        // nessun commento utente
        return data
    }

    /// Costruisce una pagina Ogg con i segmenti dati (ogni pacchetto puo' occupare piu' lacing value).
    private static func page(segments: [Data], granule: UInt64, serial: UInt32, sequence: inout UInt32, flags: UInt8) -> Data {
        var lacing: [UInt8] = []
        var body = Data()
        for packet in segments {
            var remaining = packet.count
            while remaining >= 255 {
                lacing.append(255)
                remaining -= 255
            }
            lacing.append(UInt8(remaining))
            body.append(packet)
        }
        precondition(lacing.count <= 255, "troppi segmenti per pagina")

        var header = Data("OggS".utf8)
        header.append(0)                                 // stream structure version
        header.append(flags)
        header.append(granule.littleEndianBytes)
        header.append(serial.littleEndianBytes)
        header.append(sequence.littleEndianBytes)
        header.append(UInt32(0).littleEndianBytes)      // CRC placeholder
        header.append(UInt8(lacing.count))
        header.append(contentsOf: lacing)

        var page = header + body
        let crc = crc32(page)
        page.replaceSubrange(22..<26, with: crc.littleEndianBytes)
        sequence += 1
        return page
    }

    // MARK: - Demux

    static func decode(_ data: Data) throws -> Stream {
        guard data.count >= 27, data.prefix(4) == Data("OggS".utf8) else { throw Failure.notOgg }
        var offset = data.startIndex
        var header: Header?
        var packets: [Data] = []
        var pending = Data()
        var headerPacketsSeen = 0

        while offset + 27 <= data.endIndex {
            guard data[offset..<offset + 4] == Data("OggS".utf8) else { throw Failure.notOgg }
            let segmentCount = Int(data[offset + 26])
            let lacingStart = offset + 27
            guard lacingStart + segmentCount <= data.endIndex else { throw Failure.truncated }
            let lacing = Array(data[lacingStart..<lacingStart + segmentCount])
            let bodyStart = lacingStart + segmentCount
            let bodyLength = lacing.reduce(0) { $0 + Int($1) }
            guard bodyStart + bodyLength <= data.endIndex else { throw Failure.truncated }

            // Verifica CRC (con il campo CRC azzerato).
            var pageCopy = Data(data[offset..<bodyStart + bodyLength])
            let storedCRC = UInt32(littleEndianBytes: Data(pageCopy[22..<26])) ?? 0
            pageCopy.replaceSubrange(22..<26, with: UInt32(0).littleEndianBytes)
            guard crc32(pageCopy) == storedCRC else { throw Failure.badChecksum }

            var cursor = bodyStart
            for value in lacing {
                let length = Int(value)
                pending.append(data[cursor..<cursor + length])
                cursor += length
                if value < 255 {
                    // Pacchetto completo.
                    if headerPacketsSeen == 0 {
                        header = try parseOpusHead(pending)
                    } else if headerPacketsSeen >= 2 {
                        packets.append(pending)
                    }
                    headerPacketsSeen += 1
                    pending = Data()
                }
            }
            offset = bodyStart + bodyLength
        }
        guard let header else { throw Failure.missingHead }
        return Stream(header: header, packets: packets)
    }

    private static func parseOpusHead(_ packet: Data) throws -> Header {
        guard packet.count >= 19, packet.prefix(8) == Data("OpusHead".utf8) else { throw Failure.missingHead }
        let bytes = Array(packet)
        guard bytes[8] == 1 else { throw Failure.unsupported("OpusHead version \(bytes[8])") }
        var header = Header()
        header.channels = bytes[9]
        header.preSkip = UInt16(littleEndianBytes: Data(bytes[10..<12])) ?? 0
        header.inputSampleRate = UInt32(littleEndianBytes: Data(bytes[12..<16])) ?? 48_000
        header.outputGain = Int16(bitPattern: UInt16(littleEndianBytes: Data(bytes[16..<18])) ?? 0)
        guard header.channels == 1 || header.channels == 2 else { throw Failure.unsupported("\(header.channels) canali") }
        return header
    }

    // MARK: - CRC-32 Ogg (polinomio 0x04C11DB7, senza riflessione, init 0, no xorout)

    private static let crcTable: [UInt32] = {
        (0..<256).map { index -> UInt32 in
            var r = UInt32(index) << 24
            for _ in 0..<8 {
                r = (r & 0x8000_0000) != 0 ? (r << 1) ^ 0x04C1_1DB7 : (r << 1)
            }
            return r
        }
    }()

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0
        for byte in data {
            crc = (crc << 8) ^ crcTable[Int(((crc >> 24) & 0xFF) ^ UInt32(byte))]
        }
        return crc
    }
}
