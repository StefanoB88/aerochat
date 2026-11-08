import Foundation
import Testing
@testable import AeroChat

@Suite("Robustezza contro input malevoli")
struct StabilityTests {

    @Test("created_at_ms estremo: scartato come malformato, nessun overflow (prima: crash)")
    func extremeTimestampIsRejected() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        let (_, linkB) = await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let key = try alice.identity.directChatKey(peerX25519PublicKeyRaw: bob.identity.x25519PublicKeyRaw)
        let payload = InnerPayload.text("boom", identity: alice.identity, nickname: "Alice")
        for timestamp in [Int64.min, Int64.max, -1, 0] {
            var envelope = try EnvelopeBuilder(identity: alice.identity)
                .build(packetType: .chatText, recipientID: bob.nodeID, payload: payload, key: key)
            envelope.createdAtMs = timestamp
            await bob.router.receive(try MeshWire.serialize(envelope), from: linkB)
        }
        #expect(await bob.router.stats.malformed == 4)
        #expect(await bob.router.stats.delivered == 0)
    }

    @Test("Opus: pacchetti vuoti vengono ignorati senza crash")
    func emptyOpusPacketsAreIgnored() throws {
        #expect(try OpusCodec.decode(packets: [Data(), Data()]).isEmpty)
        let samples = (0..<16_000).map { Float(sin(Double($0) * 0.05)) * 0.5 }
        var packets = try OpusCodec.encode(samples: samples)
        packets.insert(Data(), at: 3)
        #expect(!(try OpusCodec.decode(packets: packets)).isEmpty)
    }

    @Test("L2CAPConnection: oltre 4 MB in coda i nuovi frame vengono rifiutati (backpressure)")
    func outgoingQueueIsBounded() throws {
        var input: InputStream?
        var output: OutputStream?
        // Stream mai aperti: nulla viene scritto, la coda resta piena.
        Stream.getBoundStreams(withBufferSize: 1_024, inputStream: &input, outputStream: &output)
        let thread = RunLoopThread()
        thread.startAndWaitUntilReady()
        defer { thread.shutdown() }
        let connection = L2CAPConnection(id: LinkID(deviceID: UUID(), role: .central),
                                         inputStream: try #require(input), outputStream: try #require(output),
                                         thread: thread, onFrame: { _ in }, onClose: { _ in })
        let frame = Data(repeating: 7, count: 1_000_000)
        var accepted = 0
        var rejected = false
        for _ in 0..<10 {
            do {
                try connection.send(frame)
                accepted += 1
            } catch {
                rejected = true
                break
            }
        }
        #expect(rejected)
        #expect(accepted == 4)
    }
}
