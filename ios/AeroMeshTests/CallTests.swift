import Foundation
import Testing
@testable import AeroChat

@Suite("Chiamate vocali")
@MainActor
struct CallTests {

    private func offer(_ callID: String, from node: TestNode, to peer: PeerRecord, action: CallAction = .offer) async -> Bool {
        var signal = CallSignal()
        signal.callID = callID
        signal.action = action
        let payload = InnerPayload.extra(try! MeshWire.serialize(signal), identity: node.identity, nickname: node.nickname)
        return await node.router.sendCallPacket(type: .callSignal, payload: payload, to: peer)
    }

    @Test("OFFER sul link diretto fa squillare il compagno; HANGUP prima della risposta = chiamata persa nel registro")
    func offerRingsAndHangupIsMissed() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let bobCalls = CallService(router: bob.router, database: bob.database, identity: bob.identity, nickname: "Bob")
        await bobCalls.start()
        defer { bobCalls.stop() }

        let bobPeer = try #require(try await alice.database.peers.fetch(nodeID: bob.nodeID))
        #expect(await offer("call-1", from: alice, to: bobPeer))
        #expect(await eventually { await MainActor.run { bobCalls.current?.phase == .ringing } })
        #expect(bobCalls.current?.direction == .incoming)
        #expect(bobCalls.current?.peer.nodeID == alice.nodeID)

        #expect(await offer("call-1", from: alice, to: bobPeer, action: .hangup))
        #expect(await eventually { await MainActor.run { bobCalls.current?.phase == .ended(.missed) } })
        let conversation = try await bob.directConversation(with: alice)
        #expect(await eventually {
            try await bob.database.messages.fetch(conversationID: conversation.id).contains { $0.text == "📞 Chiamata persa" }
        })
    }

    @Test("Rifiuto: il chiamato chiude con REJECT e registra la chiamata rifiutata")
    func declineEndsCall() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let bobCalls = CallService(router: bob.router, database: bob.database, identity: bob.identity, nickname: "Bob")
        await bobCalls.start()
        defer { bobCalls.stop() }

        let bobPeer = try #require(try await alice.database.peers.fetch(nodeID: bob.nodeID))
        _ = await offer("call-2", from: alice, to: bobPeer)
        #expect(await eventually { await MainActor.run { bobCalls.current?.phase == .ringing } })
        bobCalls.decline()
        #expect(bobCalls.current?.phase == .ended(.rejected))
    }

    @Test("Chiamate solo dirette: senza link verso il compagno la segnalazione non parte (niente ponti ne' coda)")
    func callSignalNeedsDirectLink() async throws {
        let alice = try await TestNode.make("Alice")
        let bridge = try await TestNode.make("Ponte")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, carol)
        await FakeMeshNetwork.connect(alice.transport, bridge.transport)
        await FakeMeshNetwork.connect(bridge.transport, carol.transport)

        let carolPeer = try #require(try await alice.database.peers.fetch(nodeID: carol.nodeID))
        #expect(await offer("call-3", from: alice, to: carolPeer) == false)
        #expect(try await alice.database.relayQueue.pending().isEmpty)
    }

    @Test("Audio: fast path verso il destinatario, senza scritture in seen_packets")
    func callAudioFastPath() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let stream = await bob.router.callAudioPackets()
        let seenBefore = try await bob.database.seenPackets.count()

        var audio = CallAudio()
        audio.callID = "call-4"
        audio.sequence = 1
        audio.frames = [Data([1, 2, 3]), Data([4, 5, 6])]
        let payload = InnerPayload.extra(try MeshWire.serialize(audio), identity: alice.identity, nickname: "Alice")
        let bobPeer = try #require(try await alice.database.peers.fetch(nodeID: bob.nodeID))
        #expect(await alice.router.sendCallPacket(type: .callAudio, payload: payload, to: bobPeer))

        var iterator = stream.makeAsyncIterator()
        let received = try #require(await iterator.next())
        #expect(received.senderNodeID == alice.nodeID)
        #expect(received.audio.frames == audio.frames)
        #expect(try await bob.database.seenPackets.count() == seenBefore)
    }

    @Test("FeatureGate: chiamata solo 1-a-1, con link diretto e segnale sufficiente")
    func callGateRules() throws {
        let identity = IdentityKeys.generate()
        let peer = PeerRecord(nodeID: identity.nodeID, x25519Pub: identity.x25519PublicKeyRaw,
                              ed25519Pub: identity.ed25519PublicKeyRaw, nickname: "P")
        let direct = ConversationRecord.direct(with: peer)
        func decision(_ reachability: Reachability, pro: Bool = true) -> FeatureDecision {
            FeatureGate.decision(for: .call, userIsPro: pro, conversation: direct, reachability: reachability)
        }
        #expect(decision(.direct(rssi: -60)) == .allowed)
        #expect(decision(.direct(rssi: nil)) == .allowed)
        #expect(decision(.direct(rssi: -95)) == .callSignalTooWeak)
        #expect(decision(.viaBridge(hops: 2)) == .callNeedsDirectLink)
        #expect(decision(.outOfRange) == .peerOutOfRange)
        #expect(decision(.direct(rssi: -60), pro: false) == .requiresPass)
    }

    @Test("Opus in streaming: frame da 20 ms codificati uno alla volta e decodificati con lo stato del decoder")
    func opusStreamingRoundTrip() throws {
        let encoder = try #require(OpusStreamEncoder())
        let decoder = try #require(OpusStreamDecoder())
        let frameSize = Int(OpusCodec.frameSamples)
        var packets: [Data] = []
        for index in 0..<50 {
            let frame = (0..<frameSize).map { Float(sin(Double(index * frameSize + $0) * 0.06)) * 0.5 }
            if let packet = encoder.encode(frame) { packets.append(packet) }
        }
        #expect(packets.count >= 45)
        #expect(packets.allSatisfy { $0.count < 200 })
        let decoded = packets.flatMap { decoder.decode($0) }
        #expect(decoded.count >= 40 * frameSize)
    }
}
