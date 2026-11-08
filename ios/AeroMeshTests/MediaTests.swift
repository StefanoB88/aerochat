import Testing
import Foundation
import CoreGraphics
import ImageIO
@testable import AeroChat

@Suite("Media – BlurHash, WebP, Ogg Opus, trasferimento sulla mesh")
struct MediaTests {

    /// Immagine sintetica "fotografica": gradienti morbidi + onde, senza bordi netti.
    /// Con `noisy: true` aggiunge una scacchiera a bordi netti (caso patologico per la compressione lossy).
    private func makeImage(width: Int, height: Int, solid: (UInt8, UInt8, UInt8)? = nil, noisy: Bool = false) throws -> CGImage {
        var pixels = [UInt8](repeating: 255, count: width * height * 4)
        for y in 0..<height {
            for x in 0..<width {
                let index = (y * width + x) * 4
                if let solid {
                    pixels[index] = solid.0; pixels[index + 1] = solid.1; pixels[index + 2] = solid.2
                } else {
                    let fx = Double(x) / Double(max(1, width - 1))
                    let fy = Double(y) / Double(max(1, height - 1))
                    pixels[index] = UInt8(fx * 255)
                    pixels[index + 1] = UInt8(fy * 255)
                    let wave = (sin(fx * 12) * cos(fy * 9) + 1) / 2
                    let checker = noisy ? Double(((x / 8 + y / 8) % 2) * 120) : 0
                    pixels[index + 2] = UInt8(min(255, wave * 135 + checker))
                }
            }
        }
        return try #require(RGBAPixels.makeImage(from: pixels, width: width, height: height))
    }

    // MARK: BlurHash

    @Test("BlurHash 4x3: 28 caratteri, prefisso 'L', colore medio coerente, decodifica valida")
    func blurHashEncodeDecode() throws {
        let red = try makeImage(width: 48, height: 32, solid: (220, 30, 30))
        let hash = try BlurHash.encode(red)
        #expect(hash.count == 28)
        #expect(hash.hasPrefix("L"), "4x3 componenti => flag (3-1)*9+(4-1)=21 => 'L'")
        #expect(BlurHash.isValid(hash))

        let average = try BlurHash.averageColor(hash)
        #expect(average.red > 0.75 && average.green < 0.25 && average.blue < 0.25)

        let pixels = try BlurHash.decode(hash, width: 4, height: 4)
        #expect(pixels.count == 4 * 4 * 4)
        #expect(pixels[0] > 180 && pixels[1] < 70)
        #expect(BlurHash.image(hash, width: 8, height: 8) != nil)
        #expect(throws: BlurHash.Failure.invalidHash) { try BlurHash.decode("LEHV6nWB2yk8", width: 2, height: 2) }
    }

    // MARK: WebP

    @Test("ImageProcessor: lato lungo 1280, WebP RIFF valido entro 80 KB, BlurHash allegato, decodifica ImageIO")
    func imageProcessorProducesMeshReadyWebP() throws {
        let source = try makeImage(width: 2_400, height: 1_800)
        let result = try ImageProcessor.prepareForMesh(image: source)
        #expect(result.width == 1_280)
        #expect(result.height == 960)
        #expect(WebPEncoder.isWebP(result.webp))
        #expect(result.webp.count > 1_000)
        #expect(result.webp.count <= ImageProcessor.targetMaxBytes)
        #expect(result.quality <= ImageProcessor.initialQuality)
        #expect(result.blurhash.count == 28)

        let decoded = try WebPEncoder.decode(result.webp)
        #expect(decoded.width == 1_280 && decoded.height == 960)
        #expect(WebPEncoder.dimensions(of: result.webp)?.width == 1_280)
    }

    @Test("WebP: un header che dichiara 16384x16384 in pochi byte viene rifiutato senza decodificare")
    func webPDecodeRejectsDecompressionBomb() {
        // Header VP8L (lossless) minimale: firma 0x2F, poi 14 bit larghezza-1 e 14 bit altezza-1.
        let chunk = Data([0x2F, 0xFF, 0xFF, 0xFF, 0x0F, 0x00, 0x00, 0x00])
        var riff = Data("WEBP".utf8)
        riff.append(Data("VP8L".utf8))
        riff.append(UInt32(chunk.count).littleEndianBytes)
        riff.append(chunk)
        var data = Data("RIFF".utf8)
        data.append(UInt32(riff.count).littleEndianBytes)
        data.append(riff)
        #expect(WebPEncoder.isWebP(data))
        let declared = WebPEncoder.dimensions(of: data)
        #expect(declared?.width == 16_384 && declared?.height == 16_384)
        #expect(throws: WebPEncoder.Failure.decodeFailed) { try WebPEncoder.decode(data) }
    }

    @Test("ImageProcessor: immagini ad altissimo dettaglio rientrano comunque negli 80 KB riducendo il lato lungo")
    func imageProcessorCapsPathologicalImages() throws {
        let noisy = try makeImage(width: 2_000, height: 1_500, noisy: true)
        let result = try ImageProcessor.prepareForMesh(image: noisy)
        #expect(result.webp.count <= ImageProcessor.targetMaxBytes)
        #expect(result.width < 1_280)
        #expect(result.width >= ImageProcessor.minimumLongSide)
        #expect(result.quality == ImageProcessor.minimumQuality)
    }

    @Test("ImageProcessor: da dati PNG applica thumbnail ImageIO e non ingrandisce immagini piccole")
    func imageProcessorFromEncodedData() throws {
        let small = try makeImage(width: 400, height: 300)
        let png = try #require(pngData(small))
        let result = try ImageProcessor.prepareForMesh(imageData: png)
        #expect(result.width == 400 && result.height == 300)
        #expect(WebPEncoder.isWebP(result.webp))
    }

    private func pngData(_ image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    // MARK: Ogg Opus

    @Test("OggOpus: mux/demux roundtrip con pacchetti > 255 byte, CRC verificato, OpusHead corretto")
    func oggOpusRoundTrip() throws {
        var packets: [Data] = []
        for index in 0..<120 {
            let length = index % 7 == 0 ? 300 : 20 + index % 50
            var packet = Data(count: length)
            for offset in 0..<length {
                packet[offset] = UInt8((offset + index) & 0xFF)
            }
            packets.append(packet)
        }
        var header = OggOpus.Header()
        header.preSkip = 312
        let ogg = OggOpus.encode(packets: packets, header: header, serial: 0x1234_5678)
        #expect(ogg.prefix(4) == Data("OggS".utf8))
        #expect(ogg.range(of: Data("OpusHead".utf8)) != nil)
        #expect(ogg.range(of: Data("OpusTags".utf8)) != nil)

        let decoded = try OggOpus.decode(ogg)
        #expect(decoded.packets == packets)
        #expect(decoded.header == header)

        var corrupted = ogg
        corrupted[corrupted.index(corrupted.startIndex, offsetBy: 200)] ^= 0xFF
        #expect(throws: OggOpus.Failure.badChecksum) { try OggOpus.decode(corrupted) }
        #expect(throws: OggOpus.Failure.notOgg) { try OggOpus.decode(Data(repeating: 0, count: 64)) }
    }

    @Test("OpusCodec: 1 s di sinusoide => 50 pacchetti da 20 ms, ~1.5 KB/s a 12 kbps, decodifica e forma d'onda")
    func opusEncodeDecode() throws {
        let sampleCount = 16_000
        let samples = (0..<sampleCount).map { Float(sin(2 * Double.pi * 440 * Double($0) / 16_000)) * 0.5 }
        let packets = try OpusCodec.encode(samples: samples)
        #expect(packets.count == 50)
        let totalBytes = packets.reduce(0) { $0 + $1.count }
        #expect(totalBytes > 500 && totalBytes < 4_000, "12 kbps VBR => ~1.5 KB/s (bytes: \(totalBytes))")

        let decoded = try OpusCodec.decode(packets: packets)
        #expect(decoded.count >= sampleCount - 1_000 && decoded.count <= sampleCount + 1_000)
        let energy = decoded.suffix(8_000).reduce(Float(0)) { $0 + $1 * $1 } / 8_000
        #expect(energy > 0.01, "il segnale decodificato non e' silenzio")

        let waveform = OpusCodec.waveform(samples: samples)
        #expect(waveform.count == 32)
        #expect(waveform.max() == 255)

        let recording = try VoiceNoteRecorder.makeRecording(samples: samples)
        #expect(recording.durationMs == 1_000)
        #expect(recording.waveform.count == 32)
        #expect(try OggOpus.decode(recording.oggOpus).packets.count == 50)
    }

    // MARK: Trasferimento sulla mesh

    private func makeMediaService(for node: TestNode) async throws -> MediaTransferService {
        let service = MediaTransferService(router: node.router, database: node.database, store: try MediaStore.makeTemporary(),
                                           identity: node.identity, nickname: node.nickname)
        await service.start()
        return service
    }

    @Test("Foto: header con BlurHash => placeholder immediato, chunk => file WebP ricostruito e ACK al mittente")
    func imageTransferOverMesh() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let aliceMedia = try await makeMediaService(for: alice)
        let bobMedia = try await makeMediaService(for: bob)
        let bobEvents = await bobMedia.events()

        let image = try ImageProcessor.prepareForMesh(image: try makeImage(width: 1_600, height: 1_200))
        let conversation = try await alice.directConversation(with: bob)
        let sent = try await aliceMedia.sendImage(image, in: conversation)
        #expect(sent.contentType == .image)
        #expect(sent.mediaLocalPath != nil)
        #expect(sent.deliveryStatus == .sentMesh)

        var completed = false
        for await event in bobEvents {
            if case .completed(let msgID) = event, msgID == sent.msgID { completed = true; break }
        }
        #expect(completed)

        let received = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(received.contentType == .image)
        #expect(received.blurhash == image.blurhash)
        let path = try #require(received.mediaLocalPath)
        let bytes = try await bobMedia.store.read(relativePath: path)
        #expect(bytes == image.webp)
        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .deliveredAck })
    }

    @Test("Nota vocale: Ogg Opus + forma d'onda arrivano integri")
    func voiceNoteTransferOverMesh() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let aliceMedia = try await makeMediaService(for: alice)
        let bobMedia = try await makeMediaService(for: bob)

        let samples = (0..<24_000).map { Float(sin(2 * Double.pi * 300 * Double($0) / 16_000)) * 0.4 }
        let recording = try VoiceNoteRecorder.makeRecording(samples: samples)
        let conversation = try await alice.directConversation(with: bob)
        let sent = try await aliceMedia.sendVoiceNote(recording, in: conversation)

        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID)?.mediaLocalPath != nil })
        let received = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(received.contentType == .audio)
        #expect(received.durationMs == 1_500)
        #expect(received.audioWaveform == recording.waveform)
        let bytes = try await bobMedia.store.read(relativePath: try #require(received.mediaLocalPath))
        #expect(bytes == recording.oggOpus)
        #expect(try OggOpus.decode(bytes).packets.count == 75)
    }

    @Test("Media fuori portata: rifiutati subito (niente store-and-forward per foto/vocali)")
    func mediaRequiresReachablePeer() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        let aliceMedia = try await makeMediaService(for: alice)
        let image = try ImageProcessor.prepareForMesh(image: try makeImage(width: 200, height: 100))
        let conversation = try await alice.directConversation(with: bob)
        await #expect(throws: MediaTransferService.Failure.recipientOutOfRange) {
            try await aliceMedia.sendImage(image, in: conversation)
        }
    }
}
