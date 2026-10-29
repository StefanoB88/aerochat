import Foundation
import AVFoundation

/// Codec Opus 16 kHz mono via `AVAudioConverter` (encoder/decoder di sistema, iOS 17+).
/// 12 kbps VBR => circa 1.5 KB/s: una nota vocale di 10 s pesa ~15 KB.
enum OpusCodec {

    enum Failure: Error, Equatable {
        case formatUnavailable
        case converterUnavailable
        case encodeFailed(String)
        case decodeFailed(String)
    }

    static let sampleRate: Double = 16_000
    static let channels: AVAudioChannelCount = 1
    static let frameSamples: AVAudioFrameCount = 320      // 20 ms
    static let bitRate = 12_000

    /// Formato PCM interno (Float32, mono, 16 kHz, non interleaved).
    static var pcmFormat: AVAudioFormat? {
        AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: channels, interleaved: false)
    }

    static var opusFormat: AVAudioFormat? {
        var description = AudioStreamBasicDescription(mSampleRate: sampleRate,
                                                      mFormatID: kAudioFormatOpus,
                                                      mFormatFlags: 0,
                                                      mBytesPerPacket: 0,
                                                      mFramesPerPacket: UInt32(frameSamples),
                                                      mBytesPerFrame: 0,
                                                      mChannelsPerFrame: UInt32(channels),
                                                      mBitsPerChannel: 0,
                                                      mReserved: 0)
        return AVAudioFormat(streamDescription: &description)
    }

    // MARK: - Encode

    /// Codifica campioni PCM Float32 mono 16 kHz in pacchetti Opus da 20 ms.
    static func encode(samples: [Float]) throws -> [Data] {
        guard let pcm = pcmFormat, let opus = opusFormat else { throw Failure.formatUnavailable }
        guard let converter = AVAudioConverter(from: pcm, to: opus) else { throw Failure.converterUnavailable }
        converter.bitRate = bitRate
        converter.bitRateStrategy = AVAudioBitRateStrategy_Variable

        // Padding all'ultimo frame intero da 20 ms.
        var padded = samples
        let remainder = padded.count % Int(frameSamples)
        if remainder != 0 {
            padded.append(contentsOf: [Float](repeating: 0, count: Int(frameSamples) - remainder))
        }
        guard !padded.isEmpty else { return [] }

        guard let input = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(padded.count)) else {
            throw Failure.encodeFailed("buffer")
        }
        input.frameLength = AVAudioFrameCount(padded.count)
        padded.withUnsafeBufferPointer { source in
            input.floatChannelData?[0].update(from: source.baseAddress!, count: padded.count)
        }

        let expectedPackets = padded.count / Int(frameSamples)
        var packets: [Data] = []
        var consumed = false
        var conversionError: Error?

        while packets.count < expectedPackets {
            guard let output = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: converter.maximumOutputPacketSize) as AVAudioCompressedBuffer? else {
                throw Failure.encodeFailed("compressed buffer")
            }
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, outStatus in
                if consumed {
                    outStatus.pointee = .endOfStream
                    return nil
                }
                consumed = true
                outStatus.pointee = .haveData
                return input
            }
            if let error { conversionError = error }
            if status == .error { break }
            if output.packetCount == 0 { break }
            let descriptions = output.packetDescriptions
            for index in 0..<Int(output.packetCount) {
                let offset = Int(descriptions?[index].mStartOffset ?? 0)
                let size = Int(descriptions?[index].mDataByteSize ?? output.byteLength)
                packets.append(Data(bytes: output.data.advanced(by: offset), count: size))
            }
            if status == .endOfStream { break }
        }
        if packets.isEmpty, let conversionError {
            throw Failure.encodeFailed(conversionError.localizedDescription)
        }
        return packets
    }

    // MARK: - Decode

    /// Decodifica pacchetti Opus in campioni PCM Float32 mono 16 kHz.
    static func decode(packets: [Data]) throws -> [Float] {
        guard let pcm = pcmFormat, let opus = opusFormat else { throw Failure.formatUnavailable }
        guard let converter = AVAudioConverter(from: opus, to: pcm) else { throw Failure.converterUnavailable }
        guard !packets.isEmpty else { return [] }

        let maximumPacketSize = packets.map(\.count).max() ?? 0
        var samples: [Float] = []
        samples.reserveCapacity(packets.count * Int(frameSamples))
        var nextPacket = 0

        while nextPacket < packets.count {
            guard let output = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: frameSamples * 4) else {
                throw Failure.decodeFailed("buffer")
            }
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, outStatus in
                guard nextPacket < packets.count else {
                    outStatus.pointee = .endOfStream
                    return nil
                }
                let packet = packets[nextPacket]
                nextPacket += 1
                let buffer = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: maximumPacketSize)
                packet.withUnsafeBytes { bytes in
                    buffer.data.copyMemory(from: bytes.baseAddress!, byteCount: packet.count)
                }
                buffer.byteLength = UInt32(packet.count)
                buffer.packetCount = 1
                buffer.packetDescriptions?.pointee = AudioStreamPacketDescription(mStartOffset: 0,
                                                                                  mVariableFramesInPacket: 0,
                                                                                  mDataByteSize: UInt32(packet.count))
                outStatus.pointee = .haveData
                return buffer
            }
            if status == .error {
                throw Failure.decodeFailed(error?.localizedDescription ?? "convert")
            }
            if output.frameLength > 0, let channel = output.floatChannelData?[0] {
                samples.append(contentsOf: UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
            }
            if status == .endOfStream || (output.frameLength == 0 && nextPacket >= packets.count) { break }
        }
        return samples
    }

    // MARK: - Waveform

    /// 32 campioni `UInt8` di ampiezza RMS normalizzata (header del messaggio, forma d'onda stile WhatsApp).
    static func waveform(samples: [Float], buckets: Int = 32) -> Data {
        guard !samples.isEmpty, buckets > 0 else { return Data(repeating: 0, count: buckets) }
        let bucketSize = max(1, samples.count / buckets)
        var rms = [Float](repeating: 0, count: buckets)
        for bucket in 0..<buckets {
            let start = bucket * bucketSize
            let end = min(samples.count, start + bucketSize)
            guard start < end else { break }
            var sum: Float = 0
            for index in start..<end { sum += samples[index] * samples[index] }
            rms[bucket] = (sum / Float(end - start)).squareRoot()
        }
        let peak = rms.max() ?? 0
        guard peak > 0 else { return Data(repeating: 0, count: buckets) }
        return Data(rms.map { UInt8(max(0, min(255, ($0 / peak) * 255))) })
    }
}
