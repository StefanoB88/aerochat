import Testing
import Foundation
import SwiftProtobuf
@testable import AeroChat

@Suite("Release 1.3 – PRESENCE (persone vicine) e CONTACT_REQUEST (Saluta / Sfida)")
struct NearbyTests {

    @Test("PRESENCE e CONTACT_REQUEST: byte identici al vettore Android")
    func crossPlatformVectors() throws {
        var presence = Aeromesh_V1_Presence()
        presence.avatarColorHex = "00E5FF"
        presence.flightCode = "AZ|204|2026-11-10"
        presence.openToGames = true
        presence.sentAtMs = 1_700_000_000_000
        #expect(try presence.serializedData().hexString
                == "0a063030453546461211415a7c3230347c323032362d31312d313018012080d095ffbc31")

        var request = Aeromesh_V1_ContactRequest()
        request.action = .request
        request.requestID = Data(0..<16)
        request.greeting = "Ciao"
        request.gameKind = 3
        #expect(try request.serializedData().hexString
                == "08011210000102030405060708090a0b0c0d0e0f1a044369616f2003")
    }

    private static let visible = PresenceSettings(isVisible: true, openToGames: true, flightCode: "AZ|204|2026-11-11")

    /// Due nodi visibili e collegati che si vedono a vicenda nella cache PRESENCE.
    private func nearbyPair() async throws -> (TestNode, TestNode) {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        await alice.router.setPresenceSettings(Self.visible)
        await bob.router.setPresenceSettings(Self.visible)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        #expect(await eventually { await !alice.router.nearbyPeople().isEmpty })
        #expect(await eventually { await !bob.router.nearbyPeople().isEmpty })
        return (alice, bob)
    }

    // MARK: - PRESENCE

    @Test("Presence: serializzazione protobuf e numeri di tipo 17 / 18")
    func presenceWire() throws {
        #expect(PacketType.presence.rawValue == 17)
        #expect(PacketType.contactRequest.rawValue == 18)
        #expect(!PacketType.presence.isStoreAndForwardEligible)
        #expect(!PacketType.contactRequest.isStoreAndForwardEligible)
        var presence = Presence()
        presence.avatarColorHex = "00E5FF"
        presence.flightCode = "AZ|204|2026-11-11"
        presence.openToGames = true
        presence.sentAtMs = 1_762_000_000_000
        let decoded = try MeshWire.parsePresence(try MeshWire.serialize(presence))
        #expect(decoded == presence)
        var request = ContactRequest()
        request.action = .accept
        request.requestID = Data(0..<16)
        request.greeting = "Ciao"
        request.gameKind = Int32(GameKind.scopa.rawValue)
        #expect(try MeshWire.parseContactRequest(try MeshWire.serialize(request)) == request)
        // Byte esatti (contratto con Android): campo 1 enum, 2 bytes, 3 string, 4 int32.
        var minimal = ContactRequest()
        minimal.action = .request
        minimal.gameKind = 3
        #expect(try MeshWire.serialize(minimal) == Data([0x08, 0x01, 0x20, 0x03]))
    }

    @Test("Presence: andata e ritorno all'apertura del link, con colore, volo e sfide")
    func presenceRoundTrip() async throws {
        let (alice, bob) = try await nearbyPair()
        let seen = try #require(await bob.router.nearbyPeople().first)
        #expect(seen.nodeID == alice.nodeID)
        #expect(seen.nickname == "Alice")
        #expect(seen.flightCode == "AZ|204|2026-11-11")
        #expect(seen.openToGames)
        #expect(seen.x25519Pub == alice.identity.x25519PublicKeyRaw)
        #expect(seen.rssi != nil, "RSSI dal trasporto")
        // Mai nel DB.
        #expect(try await bob.database.peers.fetch(nodeID: alice.nodeID) == nil)
        // Invio periodico: un nuovo PRESENCE raggiunge il vicino.
        #expect(await alice.router.sendPresence())
    }

    @Test("Presence: la cache scade 3 minuti dopo l'ultimo annuncio e tiene al massimo 64 voci")
    func presenceCacheExpiry() throws {
        let start = Date()
        var cache = PresenceCache()
        let identity = IdentityKeys.generate()
        let person = NearbyPerson(nodeID: identity.nodeID, x25519Pub: identity.x25519PublicKeyRaw, ed25519Pub: identity.ed25519PublicKeyRaw,
                                  nickname: "Marco", avatarColorHex: nil, flightCode: nil, openToGames: true, rssi: -50, lastSeen: start)
        cache.upsert(person, now: start)
        #expect(cache.person(identity.nodeID, now: start.addingTimeInterval(179)) != nil)
        #expect(cache.person(identity.nodeID, now: start.addingTimeInterval(181)) == nil)
        let purged = cache.purgeExpired(now: start.addingTimeInterval(181))
        #expect(purged)
        #expect(cache.entries.isEmpty)

        for index in 0..<70 {
            let other = IdentityKeys.generate()
            cache.upsert(NearbyPerson(nodeID: other.nodeID, x25519Pub: other.x25519PublicKeyRaw, ed25519Pub: other.ed25519PublicKeyRaw,
                                      nickname: "P\(index)", avatarColorHex: nil, flightCode: nil, openToGames: false,
                                      rssi: -40 - index, lastSeen: start.addingTimeInterval(Double(index))),
                         now: start.addingTimeInterval(Double(index)))
        }
        #expect(cache.entries.count == 64)
        let sorted = cache.sorted(now: start.addingTimeInterval(70))
        #expect(sorted.first?.nickname == "P6", "segnale piu' forte rimasto in testa")
    }

    @Test("Presence: PresenceStore osservabile scade da solo")
    @MainActor
    func presenceStoreExpiry() async throws {
        var now = Date()
        let store = PresenceStore(clock: { now })
        let identity = IdentityKeys.generate()
        store.update(NearbyPerson(nodeID: identity.nodeID, x25519Pub: identity.x25519PublicKeyRaw, ed25519Pub: identity.ed25519PublicKeyRaw,
                                  nickname: "Marco", avatarColorHex: "00E5FF", flightCode: nil, openToGames: true, rssi: nil, lastSeen: now))
        #expect(store.count == 1)
        now = now.addingTimeInterval(200)
        store.purgeExpired()
        #expect(store.isEmpty)
    }

    @Test("Presence: mittente bloccato ignorato")
    func blockedPresenceIgnored() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await bob.database.peers.upsert(PeerRecord(nodeID: alice.nodeID, x25519Pub: alice.identity.x25519PublicKeyRaw,
                                                       ed25519Pub: alice.identity.ed25519PublicKeyRaw, nickname: "Alice"))
        try await bob.database.peers.setBlocked(true, nodeID: alice.nodeID)
        await bob.router.refreshBlockedPeers()
        await alice.router.setPresenceSettings(Self.visible)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        #expect(await eventually(timeout: 1) { await alice.router.sendPresence() })
        try await Task.sleep(for: .milliseconds(300))
        #expect(await bob.router.nearbyPeople().isEmpty)
    }

    @Test("Presence: visibilita' spenta = nessun invio (si continua a vedere gli altri)")
    func invisibleStopsSending() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        await alice.router.setPresenceSettings(PresenceSettings(isVisible: false))
        await bob.router.setPresenceSettings(Self.visible)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        #expect(await eventually { await !alice.router.nearbyPeople().isEmpty }, "Alice vede Bob")
        #expect(await alice.router.sendPresence() == false)
        try await Task.sleep(for: .milliseconds(300))
        #expect(await bob.router.nearbyPeople().isEmpty, "Bob non vede Alice")
    }

    // MARK: - CONTACT_REQUEST

    @Test("Saluta: REQUEST -> ACCEPT crea il contatto fidato e la chat su entrambi")
    func requestAcceptCreatesChat() async throws {
        let (alice, bob) = try await nearbyPair()
        let aliceLog = await RouterEventLog.recording(alice.router)
        let bobSeen = try #require(await alice.router.nearbyPeople().first)
        let requestID = try await alice.router.sendContactRequest(to: bobSeen.asPeerRecord, greeting: "Ciao, posto 12C?")

        #expect(await eventually { await bob.router.pendingContactRequests().count == 1 })
        let pending = try #require(await bob.router.pendingContactRequests().first)
        #expect(pending.requestID == requestID)
        #expect(pending.greeting == "Ciao, posto 12C?")
        #expect(pending.gameKind == 0)
        #expect(bob.notifier.contactRequests.count == 1, "notifica locale \"Alice ti saluta\"")
        #expect(try await bob.database.peers.fetch(nodeID: alice.nodeID) == nil, "nessun contatto prima di Accetta")

        let bobChat = try #require(try await bob.router.respondToContactRequest(requestID, accept: true))
        #expect(try await bob.database.peers.fetch(nodeID: alice.nodeID)?.isTrusted == true)
        #expect(try await bob.database.conversations.fetchPermanent().first?.id == bobChat.id)

        #expect(await eventually { try await alice.database.peers.fetch(nodeID: bob.nodeID)?.isTrusted == true })
        let aliceChat = try await alice.directConversation(with: bob)
        #expect(try await alice.database.conversations.fetchPermanent().first?.id == aliceChat.id)
        #expect(await eventually {
            await aliceLog.events.contains { event in
                if case .contactRequestAnswered(let id, _, _, .accepted, _) = event { return id == requestID }
                return false
            }
        })
        // Ora si scrivono come dopo il QR.
        try await alice.router.sendText("Eccomi!", in: aliceChat)
        #expect(await eventually { try await bob.database.messages.fetch(conversationID: bobChat.id).contains { $0.text == "Eccomi!" } })
    }

    @Test("Sfida: dopo ACCEPT parte l'invito di gioco, gia' accettato da chi ha accettato la sfida")
    func challengeStartsGame() async throws {
        let (alice, bob) = try await nearbyPair()
        let aliceGames = GameService(router: alice.router, database: alice.database, identity: alice.identity, nickname: "Alice")
        let bobGames = GameService(router: bob.router, database: bob.database, identity: bob.identity, nickname: "Bob")
        await aliceGames.start()
        await bobGames.start()
        let bobSeen = try #require(await alice.router.nearbyPeople().first)
        let requestID = try await alice.router.sendContactRequest(to: bobSeen.asPeerRecord, gameKind: .briscola)
        #expect(await eventually { await bob.router.pendingContactRequests().first?.gameKind == Int32(GameKind.briscola.rawValue) })
        let bobChat = try #require(try await bob.router.respondToContactRequest(requestID, accept: true))

        // L'invito arriva a Bob e parte da solo: la partita e' attiva per entrambi (Alice distribuisce).
        #expect(await eventually {
            try await bob.database.messages.fetch(conversationID: bobChat.id).contains { message in
                guard let data = message.gameState, let state = try? GameSessionState.decode(data) else { return false }
                return state.gameKind == .briscola && state.status == .active && state.briscola != nil
            }
        })
        let aliceChat = try await alice.directConversation(with: bob)
        #expect(await eventually {
            try await alice.database.messages.fetch(conversationID: aliceChat.id).contains { message in
                guard let data = message.gameState, let state = try? GameSessionState.decode(data) else { return false }
                return state.iAmInviter && state.status == .active && state.briscola != nil
            }
        })
    }

    @Test("DECLINE: chi ha chiesto vede il rifiuto, nessun contatto creato")
    func declineNotifiesRequester() async throws {
        let (alice, bob) = try await nearbyPair()
        let aliceLog = await RouterEventLog.recording(alice.router)
        let bobSeen = try #require(await alice.router.nearbyPeople().first)
        let requestID = try await alice.router.sendContactRequest(to: bobSeen.asPeerRecord)
        #expect(await eventually { await !bob.router.pendingContactRequests().isEmpty })
        #expect(try await bob.router.respondToContactRequest(requestID, accept: false) == nil)
        #expect(await eventually {
            await aliceLog.events.contains { event in
                if case .contactRequestAnswered(let id, _, _, .declined, _) = event { return id == requestID }
                return false
            }
        })
        #expect(try await alice.database.peers.fetch(nodeID: bob.nodeID) == nil)
        #expect(try await bob.database.peers.fetch(nodeID: alice.nodeID) == nil)
        #expect(await bob.router.pendingContactRequests().isEmpty)
    }

    @Test("Mittente sconosciuto (ne' DB ne' PRESENCE): richiesta scartata")
    func unknownSenderDropped() async throws {
        let carol = try await TestNode.make("Carol")
        let bob = try await TestNode.make("Bob")
        // Bob e' visibile, Carol no: Carol conosce le chiavi di Bob, Bob non sa nulla di Carol.
        await bob.router.setPresenceSettings(Self.visible)
        await carol.router.setPresenceSettings(PresenceSettings(isVisible: false))
        await FakeMeshNetwork.connect(carol.transport, bob.transport)
        #expect(await eventually { await !carol.router.nearbyPeople().isEmpty })
        let bobSeen = try #require(await carol.router.nearbyPeople().first)
        _ = try await carol.router.sendContactRequest(to: bobSeen.asPeerRecord)
        try await Task.sleep(for: .milliseconds(400))
        #expect(await bob.router.pendingContactRequests().isEmpty)
        #expect(bob.notifier.contactRequests.isEmpty)
        #expect(await bob.router.stats.undecryptable >= 1)
    }

    @Test("Limite: una REQUEST per mittente ogni 10 minuti")
    func rateLimit() async throws {
        let (alice, bob) = try await nearbyPair()
        let bobSeen = try #require(await alice.router.nearbyPeople().first)
        let first = try await alice.router.sendContactRequest(to: bobSeen.asPeerRecord)
        #expect(await eventually { await bob.router.pendingContactRequests().count == 1 })
        _ = try await alice.router.sendContactRequest(to: bobSeen.asPeerRecord, gameKind: .scopa)
        _ = try await alice.router.sendContactRequest(to: bobSeen.asPeerRecord)
        try await Task.sleep(for: .milliseconds(400))
        let pending = await bob.router.pendingContactRequests()
        #expect(pending.count == 1)
        #expect(pending.first?.requestID == first)
    }

    @Test("Saluto: niente link, max 140 caratteri; codice volo leggibile")
    func greetingSanitized() {
        #expect(NearbyProtocol.sanitizedGreeting("guarda https://spam.example", maskProfanity: false).isEmpty)
        #expect(NearbyProtocol.sanitizedGreeting(String(repeating: "a", count: 200), maskProfanity: false).count == 140)
        #expect(NearbyProtocol.displayFlightNumber(fromCanonical: "AZ|204|2026-11-11") == "AZ204")
        #expect(NearbyProtocol.displayFlightNumber(fromCanonical: "garbage") == nil)
    }
}

@Suite("Release 1.3 – passeggero virtuale e ricordo del viaggio")
struct DemoAndTripTests {

    // MARK: - Bot

    @Test("Bot Scopa: se puo' prendere prende (preferendo la scopa e il settebello)")
    func scopaBotPrefersCapture() throws {
        let settebello = NeapolitanCards.id(suit: .denari, rank: 7)
        let sevenCoppe = NeapolitanCards.id(suit: .coppe, rank: 7)
        let twoSpade = NeapolitanCards.id(suit: .spade, rank: 2)
        let fiveBastoni = NeapolitanCards.id(suit: .bastoni, rank: 5)
        // Il 7 di coppe prende il settebello; il 2 non prende nulla.
        let play = try #require(DemoGameBot.chooseScopaPlay(hand: [twoSpade, sevenCoppe], table: [settebello, fiveBastoni]))
        #expect(play.card == sevenCoppe)
        #expect(play.captured == [settebello])
        // Nessuna presa: scarta senza prendere.
        let discard = try #require(DemoGameBot.chooseScopaPlay(hand: [twoSpade], table: [fiveBastoni]))
        #expect(discard.captured.isEmpty)
        #expect(ScopaGame.isLegalCapture(discard.captured, card: discard.card, table: [fiveBastoni]))
    }

    /// Partita completa contro il bot: noi giochiamo con la stessa strategia, ogni risposta del bot deve
    /// essere accettata dalle regole (`receiveCardMove`).
    private func playFullCardMatch(_ kind: GameKind) async throws -> GameSessionState {
        let me = IdentityKeys.generate().nodeID
        let bot = DemoGameBot()
        let gameID = CryptoEngine.randomMessageID().hexString
        var state = GameSessionState(gameID: gameID, kind: kind.rawValue, conversationID: DemoPeerIdentity.conversationID,
                                     opponentNodeID: bot.botNodeID, iAmInviter: true)
        state.seats = [me, bot.botNodeID]
        state.mySeat = 0
        var invite = GameMove()
        invite.gameID = gameID
        invite.kind = kind
        invite.action = .invite
        var inbox = await bot.respond(to: invite, from: me)
        #expect(inbox.first?.action == .accept)
        inbox.removeFirst()
        state.status = .active
        state.sequence = 1
        for _ in 0..<400 {
            for reply in inbox {
                let accepted = state.receiveCardMove(BufferedCardMove(reply, senderNodeID: bot.botNodeID), maxGap: 16)
                #expect(accepted, "mossa del bot legale")
            }
            inbox = []
            guard state.status != .finished else { break }
            var move = GameMove()
            move.gameID = gameID
            move.kind = kind
            if state.shouldDeal {
                let deck = NeapolitanCards.shuffledDeck()
                try state.applyCardMove(BufferedCardMove(sequence: state.sequence + 1, action: .deal, senderNodeID: me, cell: 0, deck: deck))
                move.action = .deal
                move.cell = 0
                move.deck = Data(deck.map { UInt8($0) })
            } else if state.turnSeat == 0 {
                let choice = try #require(DemoGameBot.chooseCard(in: state, seat: 0))
                try state.applyCardMove(BufferedCardMove(sequence: state.sequence + 1, action: .move, senderNodeID: me,
                                                         cell: choice.card, captured: choice.captured))
                move.action = .move
                move.cell = UInt32(choice.card)
                move.captured = Data(choice.captured.sorted().map { UInt8($0) })
            } else {
                Issue.record("nessuno muove: stallo")
                break
            }
            move.sequence = state.sequence
            inbox = await bot.respond(to: move, from: me)
        }
        return state
    }

    @Test("Bot Briscola: partita intera con sole mosse legali")
    func briscolaFullMatch() async throws {
        let state = try await playFullCardMatch(.briscola)
        #expect(state.status == .finished)
        #expect(state.briscola?.isFinished == true)
    }

    @Test("Bot Scopa: partita intera (piu' mani, il bot distribuisce a turno) con sole mosse legali")
    func scopaFullMatch() async throws {
        let state = try await playFullCardMatch(.scopa)
        #expect(state.status == .finished)
        #expect(state.scopa?.isMatchOver == true)
    }

    @Test("Bot Tris: blocca la nostra fila e risponde con mosse valide")
    func ticTacToeBot() async throws {
        let me = IdentityKeys.generate().nodeID
        let bot = DemoGameBot()
        let gameID = CryptoEngine.randomMessageID().hexString
        var invite = GameMove()
        invite.gameID = gameID
        invite.kind = .ticTacToe
        invite.action = .invite
        #expect(await bot.respond(to: invite, from: me).first?.sequence == 1)
        var move = GameMove()
        move.gameID = gameID
        move.kind = .ticTacToe
        move.action = .move
        move.cell = 0
        move.sequence = 2
        let first = try #require(await bot.respond(to: move, from: me).first)
        #expect(first.sequence == 3)
        #expect(first.cell == 4, "centro")
        move.cell = 1
        move.sequence = 4
        let second = try #require(await bot.respond(to: move, from: me).first)
        #expect(second.cell == 2, "blocca 0-1-2")
    }

    @Test("Passeggero virtuale: GameService gioca con il bot senza mandare nulla in radio")
    func demoGameNeverTouchesRadio() async throws {
        let node = try await TestNode.make("Alice")
        let neighbor = try await TestNode.make("Bob")
        await FakeMeshNetwork.connect(node.transport, neighbor.transport)
        let peer = try await node.database.peers.upsert(PeerRecord(nodeID: Data(DemoPeerIdentity.nodeID), x25519Pub: DemoPeerIdentity.x25519Pub,
                                                                   ed25519Pub: DemoPeerIdentity.ed25519Pub, nickname: "Passeggero virtuale",
                                                                   isTrusted: true))
        let chat = try await node.database.conversations.ensureDirectConversation(with: peer)
        let games = GameService(router: node.router, database: node.database, identity: node.identity, nickname: "Alice")
        await games.start()
        await games.setLocalBot(DemoGameBot(), delay: .milliseconds(10))
        try await Task.sleep(for: .milliseconds(200))
        let before = await node.transport.framesSent()

        let state = try await games.invite(kind: .briscola, in: chat)
        try await node.router.sendText("Ciao!", in: chat)
        #expect(await eventually {
            guard let session = try? await games.session(gameID: state.gameID) else { return false }
            return session.status == .active && session.briscola != nil
        })
        #expect(await node.transport.framesSent() == before, "nulla in radio verso il passeggero virtuale")
        #expect(try await node.database.relayQueue.pending().isEmpty)
    }

    // MARK: - Ricordo del viaggio

    @Test("Ricordo del viaggio: una sola notifica per volo (atterraggio o Cabina scaduta)")
    func tripNotifiedOncePerFlight() {
        var memory = TripMemory()
        let flight = TripMemory.ActiveFlight(flightCode: "AZ|204|2026-11-11", title: "AZ 204 · FCO → LHR · 11 nov")
        let start = Date()
        #expect(memory.observe(activeFlight: flight, airplaneMode: false, now: start) == nil)
        #expect(memory.current?.flightCode == flight.flightCode)
        // Bluetooth/rete accesi senza modalita' aereo: non e' un atterraggio.
        #expect(memory.observe(activeFlight: flight, airplaneMode: false, now: start.addingTimeInterval(60)) == nil)
        #expect(memory.observe(activeFlight: flight, airplaneMode: true, now: start.addingTimeInterval(120)) == nil)
        memory.recordMet(IdentityKeys.generate().nodeID)
        // Modalita' aereo spenta dopo essere stata accesa: atterrati -> notifica.
        let ended = memory.observe(activeFlight: flight, airplaneMode: false, now: start.addingTimeInterval(7_200))
        #expect(ended?.flightCode == flight.flightCode)
        #expect(ended?.metNodeIDs.count == 1)
        // Mai una seconda volta per lo stesso volo (la Cabina resta attiva, poi scade).
        #expect(memory.observe(activeFlight: flight, airplaneMode: true, now: start.addingTimeInterval(7_300)) == nil)
        #expect(memory.observe(activeFlight: flight, airplaneMode: false, now: start.addingTimeInterval(7_400)) == nil)
        #expect(memory.observe(activeFlight: nil, airplaneMode: false, now: start.addingTimeInterval(9_000)) == nil)
        #expect(memory.current == nil)

        // Altro volo: finisce quando la Cabina scade.
        let next = TripMemory.ActiveFlight(flightCode: "LH|100|2026-11-12", title: "LH 100")
        #expect(memory.observe(activeFlight: next, airplaneMode: false, now: start.addingTimeInterval(10_000)) == nil)
        #expect(memory.observe(activeFlight: nil, airplaneMode: false, now: start.addingTimeInterval(20_000))?.flightCode == next.flightCode)
        #expect(memory.finished.count == 2)

        // Persistenza.
        let defaults = UserDefaults(suiteName: "trip-test-\(UUID().uuidString)")!
        let store = TripMemoryStore(defaults: defaults)
        store.save(memory)
        #expect(store.load() == memory)
    }

    @Test("Ricordo del viaggio: contatori di messaggi, persone e partite durante il volo")
    func tripCounters() async throws {
        let database = try AppDatabase.makeTemporary()
        let me = IdentityKeys.generate()
        let marco = IdentityKeys.generate()
        let peer = try await database.peers.upsert(PeerRecord(nodeID: marco.nodeID, x25519Pub: marco.x25519PublicKeyRaw,
                                                              ed25519Pub: marco.ed25519PublicKeyRaw, nickname: "Marco"))
        let chat = try await database.conversations.ensureDirectConversation(with: peer)
        let startMs = Date.nowUnixMilliseconds - 60_000
        try await database.messages.insert(MessageRecord.outgoingText("Ciao", conversationID: chat.id, sender: me, senderNickname: "Io", replyTo: nil))
        try await database.messages.insert(MessageRecord(msgID: CryptoEngine.randomMessageID(), conversationID: chat.id,
                                                         senderNodeID: marco.nodeID, senderNickname: "Marco", contentType: .text,
                                                         text: "Ehi", isOutgoing: false, deliveryStatus: .deliveredAck, hopCountReceived: 1))
        var won = GameSessionState(gameID: CryptoEngine.randomMessageID().hexString, kind: GameKind.scopa.rawValue,
                                   conversationID: chat.id, opponentNodeID: marco.nodeID, iAmInviter: true)
        won.status = .finished
        won.outcome = .iWon
        won.seats = [me.nodeID, marco.nodeID]
        won.seatNames = ["Io", "Marco"]
        let gameMsgID = try #require(won.messageID)
        try await database.messages.insert(MessageRecord(msgID: gameMsgID, conversationID: chat.id, senderNodeID: me.nodeID,
                                                         senderNickname: "Io", contentType: .game, text: "🃏 Scopa",
                                                         gameState: try won.encoded(), isOutgoing: true, deliveryStatus: .deliveredAck))
        var trip = FlightTrip(flightCode: "AZ|204|2026-11-11", title: "AZ 204", startedAtMs: startMs)
        trip.metNodeIDs = [IdentityKeys.generate().nodeID.hexString]
        trip.endedAtMs = Date.nowUnixMilliseconds + 60_000
        let computed = try await trip.withComputedCounters(database: database, myNodeID: me.nodeID, myName: "Io")
        #expect(computed.messagesSent == 1)
        #expect(computed.messagesReceived == 1)
        #expect(computed.gamesPlayed == 1)
        #expect(computed.gamesWon == 1)
        #expect(computed.peopleMet == 2, "uno visto con PRESENCE + Marco che ha scritto")
        #expect(computed.leaderboard.first?.isMe == true)
        #expect(FlightLeaderboard.myRank(in: computed.leaderboard) == 1)
        #expect(computed.leaderboard.contains { $0.name == "Marco" && $0.played == 1 && $0.wins == 0 })
        let stats = FlightStats(trip: computed)
        #expect(stats.peopleMet == 2 && stats.gamesWon == 1 && stats.headline?.contains("AZ204") == true)
    }
}
