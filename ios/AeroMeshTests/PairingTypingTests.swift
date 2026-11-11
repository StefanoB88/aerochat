import Testing
import Foundation
import CryptoKit
@testable import AeroChat

/// Registro degli eventi del router (per osservare i "sta scrivendo" ricevuti).
actor RouterEventLog {
    private(set) var events: [RouterEvent] = []

    func append(_ event: RouterEvent) { events.append(event) }

    /// Solo i `typingChanged` (conversazione, mittente, stato).
    func typing() -> [(conversationID: String, senderNodeID: Data, isTyping: Bool)] {
        events.compactMap {
            if case .typingChanged(let conversationID, let senderNodeID, _, let isTyping) = $0 {
                return (conversationID, senderNodeID, isTyping)
            }
            return nil
        }
    }

    static func recording(_ router: MeshRouterActor) async -> RouterEventLog {
        let log = RouterEventLog()
        let stream = await router.events()
        Task { for await event in stream { await log.append(event) } }
        return log
    }
}

@Suite("Release 1.2 – PAIR_HELLO (chat dopo il QR) e TYPING (sta scrivendo)")
struct PairingTypingTests {

    @Test("PAIR_HELLO: chiave HKDF dal token 00..0f identica ad Android")
    func pairHelloKeyVector() throws {
        let key = try CryptoEngine.pairHelloKey(token: Data(0..<16))
        let hex = key.withUnsafeBytes { Data($0) }.map { String(format: "%02x", $0) }.joined()
        #expect(hex == "02c24c52d1ebd371f822a1c7bcfa653c40c6728d473b2cc0320762783cb3722c")
    }

    // MARK: - Token nel QR

    @Test("URI di pairing: con e senza `pt`, parametri sconosciuti ignorati, `pt` malformato scartato")
    func pairingURIRoundTrip() throws {
        let identity = IdentityKeys.generate()
        let plain = PairingPayload(identity: identity, nickname: "Giulia Rossi")
        #expect(!plain.uriString.contains("pt="))
        let decodedPlain = try PairingPayload(uriString: plain.uriString)
        #expect(decodedPlain == plain)
        #expect(decodedPlain.pairingToken == nil)

        let token = Data((0..<16).map { UInt8($0 * 13 % 256) })
        let withToken = plain.withPairingToken(token)
        #expect(withToken.uriString.contains("pt=\(token.base64URLEncodedString())"))
        #expect(!withToken.uriString.hasSuffix("="), "base64url senza padding")
        let decoded = try PairingPayload(uriString: withToken.uriString)
        #expect(decoded.pairingToken == token)
        #expect(decoded == withToken)

        // Gruppo + token + parametri futuri sconosciuti.
        let group = PairingPayload(identity: identity, nickname: "Giulia", groupID: UUID(),
                                   groupSecretKey: CryptoEngine.randomGroupSecretKey(), groupTitle: "Volo AZ204",
                                   pairingToken: token)
        let decodedGroup = try PairingPayload(uriString: group.uriString + "&zz=futuro&v2=1")
        #expect(decodedGroup == group)
        #expect(decodedGroup.isGroupInvite)

        // Token di lunghezza sbagliata: ignorato, il QR resta valido.
        let malformed = try PairingPayload(uriString: plain.uriString + "&pt=AAAA")
        #expect(malformed.pairingToken == nil)
        #expect(malformed.nodeID == plain.nodeID)
    }

    @Test("Token di abbinamento: validi 7 giorni, al massimo 16 (i piu' vecchi scartati)")
    func pairingTokenStoreLimits() async throws {
        let database = try AppDatabase.makeTemporary()
        let store = database.pairingTokens
        let now = Date()
        let expired = PairingTokenStore.randomToken()
        try await store.save(expired, createdAt: now.addingTimeInterval(-8 * 24 * 3_600))
        #expect(try await store.validTokens(now: now).isEmpty)

        var issued: [Data] = []
        for index in 0..<20 {
            let token = PairingTokenStore.randomToken()
            try await store.save(token, createdAt: now.addingTimeInterval(Double(index)))
            issued.append(token)
        }
        let valid = try await store.validTokens(now: now.addingTimeInterval(30))
        #expect(valid.count == PairingTokenStore.maxTokens)
        #expect(Set(valid) == Set(issued.suffix(16)))
        #expect(valid.allSatisfy { $0.count == 16 })
    }

    // MARK: - PAIR_HELLO

    @Test("Scansione a senso unico: entrambi hanno la chat 1-a-1 in cima e si scrivono nei due sensi")
    func oneWayScanCreatesChatOnBothSides() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        await FakeMeshNetwork.connect(alice.transport, bob.transport)

        // Alice mostra il QR (con token), solo Bob lo scansiona.
        let token = try await alice.database.pairingTokens.issue()
        let bobChat = try await bob.router.pair(with: alice.pairingPayload.withPairingToken(token))
        #expect(bobChat.lastMessageAtMs != nil, "la chat vuota di Bob e' ordinata in cima")

        #expect(await eventually { try await alice.database.peers.fetch(nodeID: bob.nodeID)?.isTrusted == true })
        let aliceChatID = ConversationRecord.direct(with: try #require(try await alice.database.peers.fetch(nodeID: bob.nodeID))).id
        #expect(await eventually { try await alice.database.conversations.fetch(id: aliceChatID) != nil })
        let aliceChat = try #require(try await alice.database.conversations.fetch(id: aliceChatID))
        #expect(aliceChat.lastMessageAtMs != nil)
        #expect(try await alice.database.conversations.fetchPermanent().first?.id == aliceChatID)
        #expect(try await alice.database.peers.fetch(nodeID: bob.nodeID)?.nickname == "Bob")
        #expect(alice.notifier.notified.isEmpty, "nessuna notifica per il PAIR_HELLO")

        // Il DELIVERY_ACK di Alice svuota la coda di Bob; il token non viene consumato.
        #expect(await eventually { try await bob.database.relayQueue.pending().isEmpty })
        #expect(try await alice.database.pairingTokens.validTokens().contains(token))

        // Ora si scrivono nei due sensi.
        let fromAlice = try await alice.router.sendText("Ciao Bob, sono al 14C", in: aliceChat)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: fromAlice.msgID) != nil })
        let fromBob = try await bob.router.sendText("Io al 15A!", in: bobChat)
        #expect(await eventually { try await alice.database.messages.fetch(msgID: fromBob.msgID) != nil })
        #expect(await eventually { try await bob.database.messages.fetch(msgID: fromBob.msgID)?.deliveryStatus == .deliveredAck })
    }

    @Test("PAIR_HELLO con token sconosciuto o scaduto: scartato, nessun contatto ne' chat")
    func helloWithUnknownOrExpiredTokenIsRejected() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        let carol = try await TestNode.make("Carol")
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        await FakeMeshNetwork.connect(alice.transport, carol.transport)

        // Token mai emesso da Alice.
        try await bob.router.pair(with: alice.pairingPayload.withPairingToken(PairingTokenStore.randomToken()))
        // Token emesso 8 giorni fa: scaduto.
        let expired = PairingTokenStore.randomToken()
        try await alice.database.pairingTokens.save(expired, createdAt: Date().addingTimeInterval(-8 * 24 * 3_600))
        try await carol.router.pair(with: alice.pairingPayload.withPairingToken(expired))

        try await Task.sleep(for: .milliseconds(400))
        #expect(try await alice.database.peers.fetch(nodeID: bob.nodeID) == nil)
        #expect(try await alice.database.peers.fetch(nodeID: carol.nodeID) == nil)
        #expect(try await alice.database.conversations.fetchPermanent().isEmpty)
        #expect(await alice.router.stats.undecryptable >= 2)
    }

    @Test("PAIR_HELLO con node_id che non deriva dalla chiave X25519 dichiarata: scartato")
    func helloWithMismatchedNodeIDIsRejected() async throws {
        let alice = try await TestNode.make("Alice")
        let victim = IdentityKeys.generate()
        let mallory = IdentityKeys.generate()
        let token = try await alice.database.pairingTokens.issue()

        // Mallory firma con la propria chiave ma dichiara la X25519 della vittima.
        var payload = InnerPayload.signed(by: mallory, nickname: "Finto")
        payload.senderX25519Pub = victim.x25519PublicKeyRaw
        let envelope = try EnvelopeBuilder(identity: mallory).build(packetType: .pairHello, recipientID: alice.nodeID,
                                                                    payload: payload, key: try CryptoEngine.pairHelloKey(token: token))
        let link = LinkID(deviceID: UUID(), role: .central)
        await alice.router.receive(try MeshWire.serialize(envelope), from: link)

        #expect(try await alice.database.peers.fetch(nodeID: mallory.nodeID) == nil)
        #expect(try await alice.database.peers.fetch(nodeID: victim.nodeID) == nil)
        #expect(try await alice.database.conversations.fetchPermanent().isEmpty)

        // Controprova: lo stesso token con chiavi coerenti viene accettato.
        let honest = InnerPayload.signed(by: mallory, nickname: "Onesto")
        let valid = try EnvelopeBuilder(identity: mallory).build(packetType: .pairHello, recipientID: alice.nodeID,
                                                                 payload: honest, key: try CryptoEngine.pairHelloKey(token: token))
        await alice.router.receive(try MeshWire.serialize(valid), from: link)
        let accepted = try #require(try await alice.database.peers.fetch(nodeID: mallory.nodeID))
        #expect(accepted.isTrusted)
        #expect(accepted.nickname == "Onesto")
    }

    @Test("PacketType 15/16 e TypingIndicator: round-trip e idoneita' alla coda")
    func newPacketTypes() throws {
        #expect(PacketType.pairHello.rawValue == 15)
        #expect(PacketType.typing.rawValue == 16)
        #expect(PacketType(rawValue: 15) == .pairHello)
        #expect(PacketType(rawValue: 16) == .typing)
        #expect(PacketType.pairHello.isStoreAndForwardEligible)
        #expect(!PacketType.typing.isStoreAndForwardEligible)
        var indicator = TypingIndicator()
        indicator.isTyping = true
        let bytes = try MeshWire.serialize(indicator)
        #expect(bytes == Data([0x08, 0x01]))
        #expect(try MeshWire.parseTypingIndicator(bytes).isTyping)
        #expect(try !MeshWire.parseTypingIndicator(Data()).isTyping)
    }

    // MARK: - TYPING

    @Test("TYPING: consegnato in 1-a-1 e nei gruppi (anche via ponte), mai inviato in Cabina Pubblica / Cabina del volo")
    func typingDirectGroupAndLounge() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, bob)
        try await TestNode.pair(bob, carol)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        await FakeMeshNetwork.connect(bob.transport, carol.transport)
        let bobLog = await RouterEventLog.recording(bob.router)
        let carolLog = await RouterEventLog.recording(carol.router)

        // 1-a-1.
        let direct = try await alice.directConversation(with: bob)
        let bobDirectID = try await bob.directConversation(with: alice).id
        #expect(await alice.router.sendTyping(true, in: direct))
        #expect(await eventually {
            await bobLog.typing().contains { $0.conversationID == bobDirectID && $0.senderNodeID == alice.nodeID && $0.isTyping }
        })
        #expect(await alice.router.sendTyping(false, in: direct))
        #expect(await eventually { await bobLog.typing().last?.isTyping == false })
        #expect(try await bob.database.messages.fetch(conversationID: bobDirectID).isEmpty, "niente DB")
        #expect(try await alice.database.relayQueue.pending().isEmpty, "niente coda")

        // Gruppo: Carol lo riceve tramite Bob (ttl 2 = un ponte).
        let group = try await bob.router.createGroup(title: "Volo AZ204")
        let invite = try #require(await bob.router.groupInvitePayload(for: group))
        let aliceGroup = try await alice.router.pair(with: invite)
        _ = try await carol.router.pair(with: invite)
        #expect(await alice.router.sendTyping(true, in: aliceGroup))
        #expect(await eventually {
            await carolLog.typing().contains { $0.conversationID == group.id && $0.senderNodeID == alice.nodeID && $0.isTyping }
        })
        #expect(await eventually {
            await bobLog.typing().contains { $0.conversationID == group.id && $0.senderNodeID == alice.nodeID && $0.isTyping }
        })

        // Cabina Pubblica e Cabina del volo: mai inviato.
        let lounge = try await alice.database.conversations.currentCabinLounge()
        let before = await alice.transport.framesSent()
        #expect(await !alice.router.sendTyping(true, in: lounge))
        var flight = aliceGroup
        flight.flightCode = "AZ204"
        #expect(await !alice.router.sendTyping(true, in: flight))
        #expect(!MeshRouterActor.allowsTyping(in: lounge))
        #expect(!MeshRouterActor.allowsTyping(in: flight))
        #expect(await alice.transport.framesSent() == before)
    }

    @Test("TYPING: un messaggio in arrivo dallo stesso mittente spegne il \"sta scrivendo\"")
    func incomingMessageClearsTyping() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let bobLog = await RouterEventLog.recording(bob.router)

        let direct = try await alice.directConversation(with: bob)
        await alice.router.sendTyping(true, in: direct)
        #expect(await eventually { await bobLog.typing().last?.isTyping == true })
        let sent = try await alice.router.sendText("Eccomi", in: direct)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })
        #expect(await eventually { await bobLog.typing().last?.isTyping == false })

        // Lo stesso flusso nello store della UI.
        let store = await TypingStore()
        let bobDirectID = try await bob.directConversation(with: alice).id
        for event in await bobLog.typing() {
            await store.update(conversationID: event.conversationID, senderNodeID: event.senderNodeID,
                               nickname: "Alice", isTyping: event.isTyping)
        }
        #expect(await !store.isAnyoneTyping(in: bobDirectID))
    }

    @Test("TypingStore: scade dopo 6 s, `false` azzera, testi dell'intestazione")
    @MainActor
    func typingStoreExpiry() {
        var now = Date(timeIntervalSince1970: 1_000)
        let store = TypingStore(lifetime: 6, clock: { now })
        let marco = Data(repeating: 1, count: 8)
        let sara = Data(repeating: 2, count: 8)

        store.update(conversationID: "g", senderNodeID: marco, nickname: "Marco", isTyping: true)
        #expect(TypingStore.headerText(for: store.typers(in: "g"), isGroup: true) == String(localized: "Marco sta scrivendo…"))
        #expect(TypingStore.headerText(for: store.typers(in: "g"), isGroup: false) == String(localized: "sta scrivendo…"))
        store.update(conversationID: "g", senderNodeID: sara, nickname: "Sara", isTyping: true)
        #expect(store.typers(in: "g").count == 2)
        #expect(TypingStore.headerText(for: store.typers(in: "g"), isGroup: true) == String(localized: "\(2) persone stanno scrivendo…"))

        now = now.addingTimeInterval(4)
        store.update(conversationID: "g", senderNodeID: sara, nickname: "Sara", isTyping: true)   // rinnovo
        now = now.addingTimeInterval(2.5)                                                         // Marco: 6,5 s
        #expect(store.typers(in: "g").map(\.nickname) == ["Sara"])
        store.purgeExpired()
        #expect(store.typersByConversation["g"]?.count == 1)

        store.update(conversationID: "g", senderNodeID: sara, nickname: "Sara", isTyping: false)
        #expect(!store.isAnyoneTyping(in: "g"))
        #expect(store.typersByConversation["g"] == nil)
        #expect(TypingStore.headerText(for: [], isGroup: true) == nil)
    }

    @Test("TypingSender: true alla prima battitura, rinnovo ogni 4 s, false a testo vuoto / stop")
    @MainActor
    func typingSenderCadence() {
        var now = Date(timeIntervalSince1970: 1_000)
        var sent: [Bool] = []
        let sender = TypingSender(refreshInterval: 4, idleTimeout: 60, clock: { now }) { sent.append($0) }

        sender.textChanged("C")
        sender.textChanged("Ci")
        now = now.addingTimeInterval(2)
        sender.textChanged("Cia")
        #expect(sent == [true])
        now = now.addingTimeInterval(2.1)
        sender.textChanged("Ciao")
        #expect(sent == [true, true])
        sender.textChanged("   ")
        #expect(sent == [true, true, false])
        sender.stop()
        #expect(sent == [true, true, false], "nessun false doppio")
        sender.textChanged("Di nuovo")
        sender.stop()
        #expect(sent == [true, true, false, true, false])
    }
}
