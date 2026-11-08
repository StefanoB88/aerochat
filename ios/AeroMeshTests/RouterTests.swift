import Testing
import Foundation
@testable import MyApp

@Suite("MeshRouterActor – flooding, relay, store-and-forward, ack, gossip")
struct RouterTests {

    // MARK: - Cache anti-loop

    @Test("SeenPacketCache: dedup, scadenza ed eviction FIFO alla capienza")
    func seenCacheBehaviour() {
        var cache = SeenPacketCache(capacity: 16, lifetime: 10)
        let first = CryptoEngine.randomMessageID()
        let inserted = cache.insertIfNew(first, nowMs: 0)
        #expect(inserted)
        let duplicate = cache.insertIfNew(first, nowMs: 5_000)
        #expect(!duplicate)
        let afterExpiry = cache.insertIfNew(first, nowMs: 10_001)
        #expect(afterExpiry, "scaduto: torna nuovo")

        for _ in 0..<40 { _ = cache.insertIfNew(CryptoEngine.randomMessageID(), nowMs: 20_000) }
        #expect(cache.count <= 16)
        #expect(!cache.contains(first, nowMs: 20_000), "il piu' vecchio e' stato evitto")

        cache.purgeExpired(nowMs: 100_000)
        #expect(cache.count == 0)
    }

    // MARK: - Consegna diretta

    @Test("1-a-1: il testo arriva con hop 1, viene notificato e l'ACK porta il mittente a delivered_ack")
    func directTextDeliveryAndAck() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let conversation = try await alice.directConversation(with: bob)
        let sent = try await alice.router.sendText("Ci vediamo al gate B12", in: conversation)
        #expect(sent.deliveryStatus == .sentMesh)

        let bobConversation = try await bob.directConversation(with: alice)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })
        let received = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(received.text == "Ci vediamo al gate B12")
        #expect(received.conversationID == bobConversation.id)
        #expect(received.hopCountReceived == 1)
        #expect(received.isOutgoing == false)
        #expect(received.senderNickname == "Alice")
        #expect(bob.notifier.notified.map(\.msgID) == [sent.msgID])

        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .deliveredAck })
        #expect(await alice.router.reachability(of: bob.nodeID) == .direct(rssi: -60))
        #expect(await alice.router.neighbors().map(\.nodeID) == [bob.nodeID])
    }

    // MARK: - Relay

    @Test("Multi-hop: uno sconosciuto fa da ponte (ttl-1, hop+1), l'ACK torna indietro, il ponte non legge nulla")
    func relayThroughStrangerBridge() async throws {
        let alice = try await TestNode.make("Alice")
        let bridge = try await TestNode.make("Ponte")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, carol)
        await FakeMeshNetwork.connect(alice.transport, bridge.transport)
        await FakeMeshNetwork.connect(bridge.transport, carol.transport)

        let conversation = try await alice.directConversation(with: carol)
        let sent = try await alice.router.sendText("Taxi insieme?", in: conversation)

        #expect(await eventually { try await carol.database.messages.fetch(msgID: sent.msgID) != nil })
        let received = try #require(try await carol.database.messages.fetch(msgID: sent.msgID))
        #expect(received.hopCountReceived == 2)
        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .deliveredAck })

        #expect(try await bridge.database.messages.fetch(msgID: sent.msgID) == nil)
        let bridgeStats = await bridge.router.stats
        #expect(bridgeStats.relayed >= 2, "messaggio + ack")
        #expect(bridgeStats.delivered == 0)
        #expect(await carol.router.reachability(of: alice.nodeID) == .viaBridge(hops: 2))
    }

    @Test("Anti-loop: lo stesso frame ricevuto due volte viene scartato in memoria")
    func duplicateFramesAreDropped() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        let (_, linkOnBob) = await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let conversation = try await alice.directConversation(with: bob)
        let sent = try await alice.router.sendText("eco", in: conversation)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })

        let frame = try #require(await alice.transport.sent.first?.frame)
        await bob.router.receive(frame, from: linkOnBob)
        await bob.router.receive(frame, from: linkOnBob)
        let stats = await bob.router.stats
        #expect(stats.duplicates >= 2)
        #expect(try await bob.database.messages.fetch(conversationID: (try await bob.directConversation(with: alice)).id).count == 1)
    }

    @Test("TTL: un pacchetto con ttl 1 non viene piu' ritrasmesso")
    func ttlExhaustedStopsRelay() async throws {
        let alice = try await TestNode.make("Alice")
        let bridge = try await TestNode.make("Ponte")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, carol)
        await FakeMeshNetwork.connect(alice.transport, bridge.transport)
        await FakeMeshNetwork.connect(bridge.transport, carol.transport)

        let conversation = try await alice.directConversation(with: carol)
        let payload = InnerPayload.text("non deve passare", identity: alice.identity, nickname: "Alice")
        let result = try await alice.router.sendPacket(type: .chatText, payload: payload, in: conversation, ttl: 1)
        #expect(result.reached)
        try await Task.sleep(for: .milliseconds(300))
        #expect(try await carol.database.messages.fetch(msgID: result.msgID) == nil)
        #expect(await bridge.router.stats.relayed == 0)
    }

    @Test("Filtro media: un MEDIA_CHUNK diretto non attraversa sconosciuti, in gruppo passa da un solo ponte membro")
    func mediaRelayPolicy() async throws {
        let alice = try await TestNode.make("Alice")
        let bridge = try await TestNode.make("Ponte")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, carol)
        await FakeMeshNetwork.connect(alice.transport, bridge.transport)
        await FakeMeshNetwork.connect(bridge.transport, carol.transport)

        var chunk = InnerPayload.signed(by: alice.identity, nickname: "Alice")
        chunk.mediaID = "img-1"
        chunk.chunkIndex = 0
        chunk.totalChunks = 1
        chunk.chunkData = Data(repeating: 0xAB, count: 2_000)

        // 1. Diretto verso Carol attraverso un ponte estraneo: bloccato.
        let direct = try await alice.directConversation(with: carol)
        _ = try await alice.router.sendPacket(type: .mediaChunk, payload: chunk, in: direct, storeIfUnreachable: false)
        try await Task.sleep(for: .milliseconds(300))
        #expect(await bridge.router.stats.relayed == 0)

        // 2. Gruppo di cui il ponte e' membro: un solo salto di relay consentito.
        let groupID = UUID()
        let key = CryptoEngine.randomGroupSecretKey()
        for node in [alice, bridge, carol] {
            _ = try await node.database.conversations.createGroup(id: groupID, title: "Volo", secretKey: key)
        }
        let aliceGroup = try #require(try await alice.database.conversations.fetch(id: groupID.uuidString.lowercased()))
        let inboundOnCarol = await carol.router.inboundPackets()
        _ = try await alice.router.sendPacket(type: .mediaChunk, payload: chunk, in: aliceGroup, storeIfUnreachable: false)

        var iterator = inboundOnCarol.makeAsyncIterator()
        let packet = try #require(await iterator.next())
        #expect(packet.payload.mediaID == "img-1")
        #expect(packet.hopCount == 2)
        #expect(packet.sender.nodeID == alice.nodeID)
        #expect(await eventually { await bridge.router.stats.relayed == 1 })
    }

    // MARK: - Store-and-forward

    @Test("Store-and-Forward: senza vicini il messaggio resta in coda e parte da solo al primo link")
    func storeAndForwardFlushesOnNewLink() async throws {
        let alice = try await TestNode.make("Alice")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, carol)

        let conversation = try await alice.directConversation(with: carol)
        let sent = try await alice.router.sendText("Ti scrivo appena atterriamo", in: conversation)
        #expect(sent.deliveryStatus == .queued)
        #expect(try await alice.database.relayQueue.stats().count == 1)

        await FakeMeshNetwork.connect(alice.transport, carol.transport)

        #expect(await eventually { try await carol.database.messages.fetch(msgID: sent.msgID) != nil })
        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .deliveredAck })
        #expect(await eventually { try await alice.database.relayQueue.stats().count == 0 })
    }

    @Test("Store-and-Forward sul ponte: il relay senza altri vicini viene accodato e consegnato dopo")
    func bridgeQueuesRelayUntilNextHopAppears() async throws {
        let alice = try await TestNode.make("Alice")
        let bridge = try await TestNode.make("Ponte")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, carol)
        await FakeMeshNetwork.connect(alice.transport, bridge.transport)

        let conversation = try await alice.directConversation(with: carol)
        let sent = try await alice.router.sendText("Messaggio in valigia", in: conversation)
        #expect(sent.deliveryStatus == .sentMesh, "il ponte e' stato raggiunto")
        #expect(await eventually { try await bridge.database.relayQueue.stats().count == 1 })

        await FakeMeshNetwork.connect(bridge.transport, carol.transport)
        #expect(await eventually { try await carol.database.messages.fetch(msgID: sent.msgID) != nil })
        #expect(await eventually { try await bridge.database.relayQueue.stats().count == 0 })
    }

    // MARK: - Lounge e gruppi

    @Test("Cabina Pubblica: sconosciuti si scambiano testo col nickname, i link vengono scartati")
    func publicLoungeBetweenStrangers() async throws {
        let alice = try await TestNode.make("Seat 12A")
        let bob = try await TestNode.make("Seat 31F")
        await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let lounge = try await alice.database.conversations.currentCabinLounge()
        let sent = try await alice.router.sendText("Qualcuno ha un caricatore?", in: lounge)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })
        let received = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(received.conversationID == ConversationRecord.cabinLoungeID)
        #expect(received.senderNickname == "Seat 12A")
        #expect(bob.notifier.notified.isEmpty, "la Lounge non notifica")

        let spam = try await alice.router.sendText("Guarda https://example.com", in: lounge)
        try await Task.sleep(for: .milliseconds(300))
        #expect(try await bob.database.messages.fetch(msgID: spam.msgID) == nil)
        #expect(MeshRouterActor.containsLink("vai su www.sito.it"))
    }

    @Test("Group Key Gossip: A e C scansionano solo B e si riconoscono a vicenda, poi chattano via B")
    func groupKeyGossipPropagatesMembers() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, bob)
        try await TestNode.pair(bob, carol)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        await FakeMeshNetwork.connect(bob.transport, carol.transport)

        let group = try await bob.router.createGroup(title: "Volo FR1234")
        let invite = try #require(await bob.router.groupInvitePayload(for: group))
        #expect(invite.isGroupInvite)

        // A e C scansionano il QR di gruppo di B.
        let aliceGroup = try await alice.router.pair(with: invite)
        #expect(aliceGroup.type == .group)
        #expect(aliceGroup.groupSecretKey == group.groupSecretKey)
        let carolGroup = try await carol.router.pair(with: invite)

        // Gossip: B apprende A e C e propaga; A e C si conoscono senza scansionarsi.
        #expect(await eventually {
            let members = try await alice.database.conversations.groupMembers(of: aliceGroup.id).map(\.nodeID)
            return members.contains(carol.nodeID) && members.contains(bob.nodeID)
        })
        #expect(await eventually {
            let members = try await carol.database.conversations.groupMembers(of: carolGroup.id).map(\.nodeID)
            return members.contains(alice.nodeID)
        })
        #expect(try await alice.database.peers.fetch(nodeID: carol.nodeID)?.nickname == "Carol")
        #expect(try await alice.database.peers.fetch(nodeID: carol.nodeID)?.isTrusted == false)
        #expect(try await bob.database.conversations.groupMembers(of: group.id).count == 2)

        // Messaggio di gruppo da A: B lo riceve direttamente, C via B.
        let sent = try await alice.router.sendText("Siamo tutti a bordo?", in: aliceGroup)
        #expect(await eventually { try await carol.database.messages.fetch(msgID: sent.msgID) != nil })
        let onCarol = try #require(try await carol.database.messages.fetch(msgID: sent.msgID))
        #expect(onCarol.conversationID == carolGroup.id)
        #expect(onCarol.hopCountReceived == 2)
        #expect(try await bob.database.messages.fetch(msgID: sent.msgID)?.hopCountReceived == 1)
        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .deliveredAck })
        #expect(await alice.router.reachability(of: aliceGroup) == .direct(rssi: -60))
    }

    @Test("Backoff del flooding: chi sente il duplicato da un vicino annulla il proprio relay")
    func floodingBackoffSuppressesRedundantRelay() async throws {
        let alice = try await TestNode.make("Alice")
        let fast = try await TestNode.make("Veloce", relayBackoff: 0.0...0.0)
        let slow = try await TestNode.make("Lento", relayBackoff: 1.0...1.0)
        // Rete completamente magliata: entrambi i vicini ricevono il messaggio direttamente da Alice.
        await FakeMeshNetwork.connect(alice.transport, fast.transport)
        await FakeMeshNetwork.connect(alice.transport, slow.transport)
        await FakeMeshNetwork.connect(fast.transport, slow.transport)

        let lounge = try await alice.database.conversations.currentCabinLounge()
        let sent = try await alice.router.sendText("Qualcuno scende a Bergamo?", in: lounge)
        #expect(await eventually { try await slow.database.messages.fetch(msgID: sent.msgID) != nil })

        // "Veloce" ritrasmette subito verso "Lento"; "Lento" sente il duplicato e rinuncia al suo relay.
        #expect(await eventually { await fast.router.stats.relayed == 1 })
        #expect(await eventually { await slow.router.stats.relaySuppressed == 1 })
        try await Task.sleep(for: .milliseconds(1_300))
        #expect(await slow.router.stats.relayed == 0)
        #expect(try await slow.database.messages.fetch(conversationID: ConversationRecord.cabinLoungeID).count == 1)
    }

    @Test("Anti-replay: pacchetti piu' vecchi della finestra o nel futuro vengono scartati senza toccare il DB")
    func replayWindowRejectsStalePackets() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        let (_, linkOnBob) = await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let bobPeer = try #require(try await alice.database.peers.fetch(nodeID: bob.nodeID))
        let key = try alice.identity.directChatKey(peerX25519PublicKeyRaw: bobPeer.x25519Pub)
        let builder = EnvelopeBuilder(identity: alice.identity)
        func frame(ageSeconds: TimeInterval) throws -> (Data, Data) {
            let payload = InnerPayload.text("vecchio", identity: alice.identity, nickname: "Alice")
            let envelope = try builder.build(packetType: .chatText, recipientID: bob.nodeID, payload: payload, key: key,
                                             createdAtMs: Date().addingTimeInterval(-ageSeconds).unixMilliseconds)
            return (try MeshWire.serialize(envelope), envelope.msgID)
        }

        let (stale, staleID) = try frame(ageSeconds: 7 * 3_600)
        await bob.router.receive(stale, from: linkOnBob)
        let (future, futureID) = try frame(ageSeconds: -10 * 60)
        await bob.router.receive(future, from: linkOnBob)
        let (fresh, freshID) = try frame(ageSeconds: 3_600)
        await bob.router.receive(fresh, from: linkOnBob)

        #expect(await eventually { try await bob.database.messages.fetch(msgID: freshID) != nil })
        #expect(try await bob.database.messages.fetch(msgID: staleID) == nil)
        #expect(try await bob.database.messages.fetch(msgID: futureID) == nil)
        let stats = await bob.router.stats
        #expect(stats.replayRejected == 2)
        #expect(stats.delivered == 1)
        #expect(try await bob.database.seenPackets.contains(msgID: staleID) == false, "i pacchetti rifiutati non inquinano la cache")
    }

    @Test("Peer bloccato: i suoi messaggi non vengono consegnati")
    func blockedPeerIsIgnored() async throws {
        let alice = try await TestNode.make("Alice")
        let troll = try await TestNode.make("Troll")
        try await TestNode.pair(alice, troll)
        try await alice.database.peers.setBlocked(true, nodeID: troll.nodeID)
        await alice.router.refreshBlockedPeers()
        await FakeMeshNetwork.connect(alice.transport, troll.transport)

        let conversation = try await troll.directConversation(with: alice)
        let sent = try await troll.router.sendText("spam", in: conversation)
        try await Task.sleep(for: .milliseconds(300))
        #expect(try await alice.database.messages.fetch(msgID: sent.msgID) == nil)
        #expect(try await troll.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .sentMesh)
    }
}
