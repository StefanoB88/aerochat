import Testing
import Foundation
import SwiftProtobuf
@testable import AeroChat

@Suite("Release 1.4 – posto, bacheca della cabina e funzioni locali")
struct Release14Tests {

    private static let flight = "AZ|204|2026-11-11"
    private static let visibleOnFlight = PresenceSettings(isVisible: true, openToGames: true, flightCode: flight)

    // MARK: - A. Wire

    @Test("Presence.seat e BoardPost: byte identici ai vettori Android")
    func crossPlatformVectors() throws {
        #expect(PacketType.boardPost.rawValue == 20)
        #expect(PacketType(rawValue: 20) == .boardPost)
        #expect(PacketType.allCases.contains(.boardPost))
        #expect(!PacketType.boardPost.isStoreAndForwardEligible)
        #expect(NearbyProtocol.boardPostTTL == 3)
        #expect(BoardKind.lookingFor.rawValue == 1)
        #expect(BoardKind.offering.rawValue == 2)
        #expect(BoardKind.found.rawValue == 3)
        #expect(BoardKind.seatSwap.rawValue == 4)

        var presence = Presence()
        presence.avatarColorHex = "00E5FF"
        presence.flightCode = "AZ|204|2026-11-10"
        presence.openToGames = true
        presence.sentAtMs = 1_700_000_000_000
        presence.seat = "14C"
        // Vettore 1.3 + `2a 03 "14C"` (campo 5).
        #expect(try presence.serializedData().hexString
                == "0a063030453546461211415a7c3230347c323032362d31312d313018012080d095ffbc312a03313443")
        #expect(try MeshWire.parsePresence(try MeshWire.serialize(presence)) == presence)

        var post = BoardPost()
        post.postID = Data(0..<16)
        post.kind = .seatSwap
        post.text = "Ciao"
        post.flightCode = "AZ|204|2026-11-10"
        post.seat = "14C"
        post.createdAtMs = 1_700_000_000_000
        post.isClosed = true
        #expect(try MeshWire.serialize(post).hexString
                == "0a10000102030405060708090a0b0c0d0e0f" + "1004" + "1a044369616f"
                + "2211415a7c3230347c323032362d31312d3130" + "2a03313443" + "3080d095ffbc31" + "3801")
        #expect(try MeshWire.parseBoardPost(try MeshWire.serialize(post)) == post)
    }

    @Test("Posto: trim, maiuscole, fila 1...999 e lettera A...K")
    func seatValidation() {
        #expect(SeatCode.normalized(" 14c ") == "14C")
        #expect(SeatCode.normalized("1A") == "1A")
        #expect(SeatCode.normalized("999K") == "999K")
        #expect(SeatCode.normalized("1000A") == nil)
        #expect(SeatCode.normalized("14L") == nil)
        #expect(SeatCode.normalized("C14") == nil)
        #expect(SeatCode.normalized("A") == nil)
        #expect(SeatCode.normalized("") == nil)
        #expect(SeatCode.normalized(nil) == nil)
        #expect(SeatCode.normalized("１4C") == nil, "solo cifre ASCII")
        #expect(SeatCode.row("32F") == 32)
        #expect(SeatCode.letter("32f") == "F")
    }

    // MARK: - Bacheca: logica pura

    private func entry(_ author: IdentityKeys, text: String = "Cerco un caricatore", at date: Date,
                       postID: Data = CryptoEngine.randomMessageID()) -> BoardPostEntry {
        BoardPostEntry(postID: postID, authorNodeID: author.nodeID, x25519Pub: author.x25519PublicKeyRaw,
                       ed25519Pub: author.ed25519PublicKeyRaw, nickname: "Marco", avatarColorHex: nil, kind: .lookingFor,
                       text: text, flightCode: nil, seat: nil, createdAt: date, lastSeen: date, isMine: false)
    }

    @Test("Bacheca: 10 minuti senza ripetizione, 6 ore di vita, 50 voci, 10 per mittente, chiusura e blocco")
    func boardCacheLimits() {
        let start = Date()
        var cache = BoardCache()
        let marco = IdentityKeys.generate()
        let post = entry(marco, at: start)
        let r1 = cache.receive(post, now: start)
        #expect(r1)
        #expect(cache.posts(now: start.addingTimeInterval(9 * 60)).count == 1)
        // Ripetuto: resta vivo.
        let r2 = cache.receive(post, now: start.addingTimeInterval(9 * 60))
        #expect(r2)
        #expect(cache.posts(now: start.addingTimeInterval(18 * 60)).count == 1)
        #expect(cache.posts(now: start.addingTimeInterval(20 * 60)).isEmpty, "non ripetuto da 10 minuti")
        // Annuncio piu' vecchio di 6 ore: scartato.
        let r3 = cache.receive(entry(marco, at: start.addingTimeInterval(-6 * 3_600 - 1)), now: start)
        #expect(!r3)
        // Stesso post_id da un altro autore: ignorato.
        let other = IdentityKeys.generate()
        var cache2 = BoardCache()
        cache2.receive(post, now: start)
        let r4 = cache2.receive(entry(other, at: start, postID: post.postID), now: start)
        #expect(!r4)
        #expect(cache2.author(marco.nodeID, now: start) != nil)
        // Chiusura solo dall'autore.
        let r5 = cache2.close(post.postID, author: other.nodeID)
        #expect(!r5)
        let r6 = cache2.close(post.postID, author: marco.nodeID)
        #expect(r6)
        #expect(cache2.posts(now: start).isEmpty)

        // Al massimo 10 annunci per mittente.
        var perSender = BoardCache()
        for index in 0..<12 { perSender.receive(entry(marco, text: "Annuncio \(index)", at: start), now: start) }
        #expect(perSender.posts(now: start).count == 10)
        let r7 = perSender.removeAuthors([marco.nodeID])
        #expect(r7)
        #expect(perSender.posts(now: start).isEmpty)

        // Al massimo 50 voci in tutto (via le meno recenti).
        var full = BoardCache()
        for index in 0..<60 {
            let time = start.addingTimeInterval(Double(index))
            full.receive(entry(IdentityKeys.generate(), at: time), now: time)
        }
        #expect(full.posts(now: start.addingTimeInterval(60)).count == 50)
    }

    @Test("Bacheca: testo 1...140 senza link e saluto di risposta al massimo di 140 caratteri")
    func boardTextAndReplyGreeting() {
        #expect(BoardPostEntry.validText("  Cerco un caricatore USB-C  ") == "Cerco un caricatore USB-C")
        #expect(BoardPostEntry.validText("   ") == nil)
        #expect(BoardPostEntry.validText(String(repeating: "a", count: 141)) == nil)
        #expect(BoardPostEntry.validText(String(repeating: "a", count: 140)) != nil)
        #expect(BoardPostEntry.validText("Vedi https://example.com") == nil)
        let prefix = "Per il tuo annuncio: "
        #expect(BoardPostEntry.replyGreeting(prefix: prefix, text: "Offro cuffie") == "Per il tuo annuncio: Offro cuffie")
        let long = BoardPostEntry.replyGreeting(prefix: prefix, text: String(repeating: "x", count: 140))
        #expect(long.count == 140)
        #expect(long.hasPrefix(prefix) && long.hasSuffix("…"))
    }

    // MARK: - Bacheca e posto via mesh

    @Test("Presence.seat: solo se condiviso e con un volo; il ricevente lo mostra solo sullo stesso volo")
    func seatSharedOnlyWhenEnabled() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        await alice.router.setPresenceSettings(Self.visibleOnFlight)
        await bob.router.setPresenceSettings(Self.visibleOnFlight)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        #expect(await eventually { await bob.router.nearbyPeople().first?.nodeID == alice.nodeID })
        #expect(await bob.router.nearbyPeople().first?.seat == nil, "posto non condiviso di default")

        var shared = Self.visibleOnFlight
        shared.seat = "14C"
        await alice.router.setPresenceSettings(shared)
        #expect(await alice.router.sendPresence())
        #expect(await eventually { await bob.router.nearbyPeople().first?.seat == "14C" })
        let seen = try #require(await bob.router.nearbyPeople().first)
        #expect(seen.seatOnFlight(Self.flight) == "14C")
        #expect(seen.seatOnFlight("AZ|999|2026-11-11") == nil)

        // Senza volo il posto non parte.
        await alice.router.setPresenceSettings(PresenceSettings(isVisible: true, openToGames: true, flightCode: nil, seat: "14C"))
        #expect(await alice.router.sendPresence())
        #expect(await eventually { await bob.router.nearbyPeople().first?.flightCode == nil })
        #expect(await bob.router.nearbyPeople().first?.seat == nil)
    }

    @Test("BOARD_POST: arriva tramite un ponte (ttl 3), mai nel DB, e sparisce quando l'autore lo chiude")
    func boardPostRelayedAndClosed() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        let carol = try await TestNode.make("Carol")
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        await FakeMeshNetwork.connect(bob.transport, carol.transport)
        #expect(await eventually { await !alice.router.neighbors().isEmpty })
        #expect(await eventually { await !carol.router.neighbors().isEmpty })

        let posted = try await alice.router.postToBoard(kind: .lookingFor, text: "  Cerco un caricatore USB-C  ")
        #expect(posted.text == "Cerco un caricatore USB-C")
        #expect(posted.isMine)
        #expect(await eventually { await carol.router.boardPosts().contains { $0.postID == posted.postID } })
        let seen = try #require(await carol.router.boardPosts().first { $0.postID == posted.postID })
        #expect(seen.nickname == "Alice")
        #expect(seen.kind == .lookingFor)
        #expect(seen.authorNodeID == alice.nodeID)
        #expect(!seen.isMine)
        #expect(await bob.router.boardPosts().contains { $0.postID == posted.postID })
        #expect(await bob.router.stats.relayed >= 1)
        // Mai nel DB.
        #expect(try await carol.database.peers.fetch(nodeID: alice.nodeID) == nil)

        // Ripetizione (nuovo msg_id): stessa voce, nessun doppione.
        await alice.router.resendBoardPosts()
        try await Task.sleep(for: .milliseconds(200))
        #expect(await carol.router.boardPosts().filter { $0.postID == posted.postID }.count == 1)

        #expect(await alice.router.closeBoardPost(posted.postID))
        #expect(await eventually { await carol.router.boardPosts().isEmpty })
        #expect(await eventually { await bob.router.boardPosts().isEmpty })
    }

    @Test("BOARD_POST: massimo 3 aperti, niente link, annunci di un altro volo ignorati")
    func boardLimitsAndOtherFlight() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        await alice.router.setPresenceSettings(PresenceSettings(isVisible: false, openToGames: true, flightCode: "AZ|204|2026-11-11"))
        for index in 0..<3 { try await alice.router.postToBoard(kind: .offering, text: "Snack \(index)") }
        await #expect(throws: RouterError.boardLimitReached) {
            try await alice.router.postToBoard(kind: .offering, text: "Quarto")
        }
        let first = try #require(await alice.router.boardPosts().first)
        #expect(await alice.router.closeBoardPost(first.postID))
        await #expect(throws: RouterError.invalidBoardPost) {
            try await alice.router.postToBoard(kind: .offering, text: "Vedi https://example.com")
        }
        await #expect(throws: RouterError.invalidBoardPost) {
            try await alice.router.postToBoard(kind: .unspecified, text: "Ciao")
        }

        await bob.router.setPresenceSettings(PresenceSettings(isVisible: false, openToGames: true, flightCode: "AZ|777|2026-11-11"))
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        #expect(await eventually { await !bob.router.neighbors().isEmpty })
        let carol = try await TestNode.make("Carol")
        await carol.router.setPresenceSettings(PresenceSettings(isVisible: false, openToGames: true, flightCode: "AZ|204|2026-11-11"))
        await FakeMeshNetwork.connect(alice.transport, carol.transport)
        #expect(await eventually { await !carol.router.neighbors().isEmpty })
        // I 2 annunci aperti di Alice (volo 204) arrivano a Carol (stesso volo), non a Bob (volo 777).
        #expect(await eventually { await carol.router.boardPosts().count == 2 })
        try await Task.sleep(for: .milliseconds(200))
        #expect(await bob.router.boardPosts().isEmpty)
    }

    @Test("Rispondere a un annuncio: saluto precompilato; le chiavi dell'autore vengono dalla bacheca")
    func replyToBoardPost() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        // Bob visibile, Alice invisibile: Bob conosce Alice solo dalla bacheca.
        await bob.router.setPresenceSettings(PresenceSettings(isVisible: true, openToGames: true, flightCode: nil))
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        #expect(await eventually { await !alice.router.nearbyPeople().isEmpty })
        let posted = try await alice.router.postToBoard(kind: .offering, text: "Offro cuffie in prestito")
        #expect(await eventually { await bob.router.boardPosts().contains { $0.postID == posted.postID } })
        #expect(await bob.router.nearbyPeople().isEmpty, "Alice e' invisibile")

        // Bob risponde all'annuncio: Alice lo conosce dal suo PRESENCE.
        let entry = try #require(await bob.router.boardPosts().first { $0.postID == posted.postID })
        let greeting = BoardPostEntry.replyGreeting(prefix: "Per il tuo annuncio: ", text: entry.text)
        try await bob.router.sendContactRequest(to: entry.asNearbyPerson.asPeerRecord, greeting: greeting)
        #expect(await eventually { await !alice.router.pendingContactRequests().isEmpty })
        let request = try #require(await alice.router.pendingContactRequests().first)
        #expect(request.greeting == "Per il tuo annuncio: Offro cuffie in prestito")
        #expect(request.sender.nodeID == bob.nodeID)
        _ = try await alice.router.respondToContactRequest(request.requestID, accept: false)

        // L'autrice saluta Bob: Bob apre la richiesta con le chiavi della bacheca (Alice non e' nel DB ne' visibile).
        let bobAsSeen = try #require(await alice.router.nearbyPeople().first)
        try await alice.router.sendContactRequest(to: bobAsSeen.asPeerRecord, greeting: "Ciao!")
        #expect(await eventually { await bob.router.pendingContactRequests().contains { $0.sender.nodeID == alice.nodeID } })
    }

    // MARK: - B. Funzioni locali

    @Test("Partita gratis: chiave per volo o per giorno locale, consumo e memoria limitata")
    func freeGameAllowance() throws {
        let rome = try #require(TimeZone(identifier: "Europe/Rome"))
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = rome
        let lateEvening = try #require(calendar.date(from: DateComponents(year: 2026, month: 11, day: 10, hour: 23, minute: 30)))
        let afterMidnight = try #require(calendar.date(from: DateComponents(year: 2026, month: 11, day: 11, hour: 0, minute: 30)))
        #expect(FreeGameAllowance.key(activeFlightCode: "AZ|204|2026-11-10", now: lateEvening, timeZone: rome) == "flight:AZ|204|2026-11-10")
        #expect(FreeGameAllowance.key(activeFlightCode: nil, now: lateEvening, timeZone: rome) == "day:2026-11-10")
        #expect(FreeGameAllowance.key(activeFlightCode: "  ", now: afterMidnight, timeZone: rome) == "day:2026-11-11")

        var used: [String] = []
        #expect(FreeGameAllowance.isAvailable("day:2026-11-10", used: used))
        used = FreeGameAllowance.consume("day:2026-11-10", used: used)
        #expect(!FreeGameAllowance.isAvailable("day:2026-11-10", used: used))
        #expect(FreeGameAllowance.isAvailable("flight:AZ|204|2026-11-10", used: used), "il volo ha la sua partita gratis")
        for index in 0..<100 { used = FreeGameAllowance.consume("day:\(index)", used: used) }
        #expect(used.count == FreeGameAllowance.maxRememberedKeys)
        #expect(used.last == "day:99")
    }

    @Test("Premio inviti: al 3° contatto nuovo distinto 24 h di Pass, una sola volta")
    func referralCounter() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        var state = ReferralProgram.State()
        state = ReferralProgram.register("a", isNewPeer: true, state: state, now: now).state
        state = ReferralProgram.register("a", isNewPeer: true, state: state, now: now).state
        state = ReferralProgram.register("b", isNewPeer: false, state: state, now: now).state
        #expect(state.progress == 1, "doppioni e contatti gia' noti non contano")
        state = ReferralProgram.register("c", isNewPeer: true, state: state, now: now).state
        #expect(state.progress == 2)
        #expect(!state.isPassActive(now: now))
        let third = ReferralProgram.register("d", isNewPeer: true, state: state, now: now)
        #expect(third.unlocked)
        state = third.state
        #expect(state.progress == 3)
        #expect(state.rewardGranted)
        #expect(state.isPassActive(now: now.addingTimeInterval(ReferralProgram.rewardDuration - 1)))
        #expect(!state.isPassActive(now: now.addingTimeInterval(ReferralProgram.rewardDuration)))
        let again = ReferralProgram.register("e", isNewPeer: true, state: state, now: now.addingTimeInterval(2 * 86_400))
        #expect(!again.unlocked)
        #expect(again.state.passUntilMs == state.passUntilMs)
        #expect(again.state.progress == 3)
    }

    @Test("Richiesta di recensione: 3 giorni, 5 aperture, una ogni 90 giorni")
    func reviewPromptConditions() {
        let day: TimeInterval = 86_400
        let first = Date(timeIntervalSince1970: 1_700_000_000)
        let now = first.addingTimeInterval(3 * day)
        #expect(ReviewPrompt.shouldRequest(now: now, firstOpen: first, openCount: 5, lastRequest: nil))
        #expect(!ReviewPrompt.shouldRequest(now: now.addingTimeInterval(-1), firstOpen: first, openCount: 5, lastRequest: nil))
        #expect(!ReviewPrompt.shouldRequest(now: now, firstOpen: first, openCount: 4, lastRequest: nil))
        #expect(!ReviewPrompt.shouldRequest(now: now, firstOpen: nil, openCount: 9, lastRequest: nil))
        #expect(!ReviewPrompt.shouldRequest(now: now, firstOpen: first, openCount: 9, lastRequest: now.addingTimeInterval(-89 * day)))
        #expect(ReviewPrompt.shouldRequest(now: now, firstOpen: first, openCount: 9, lastRequest: now.addingTimeInterval(-90 * day)))

        let defaults = UserDefaults(suiteName: "review-\(UUID().uuidString)")!
        for _ in 0..<5 { ReviewPrompt.recordOpen(defaults: defaults, now: first) }
        #expect(!ReviewPrompt.consumeIfAllowed(defaults: defaults, now: first.addingTimeInterval(day)))
        #expect(ReviewPrompt.consumeIfAllowed(defaults: defaults, now: now))
        #expect(!ReviewPrompt.consumeIfAllowed(defaults: defaults, now: now.addingTimeInterval(day)), "una ogni 90 giorni")
    }

    private func trip(_ code: String, people: Int, sent: Int, received: Int, played: Int, won: Int, hops: Int? = nil) -> FlightTrip {
        var trip = FlightTrip(flightCode: code, title: code, startedAtMs: 0, endedAtMs: 1)
        trip.peopleMet = people
        trip.messagesSent = sent
        trip.messagesReceived = received
        trip.gamesPlayed = played
        trip.gamesWon = won
        trip.longestHop = hops
        return trip
    }

    @Test("Passaporto: contatori cumulativi, un volo contato una volta, badge con data")
    func passportBadges() throws {
        var stats = PassportStats()
        let t10 = Date(unixMilliseconds: 10)
        let first = TravelPassport.record(trip("AZ|1|2026-01-01", people: 6, sent: 40, received: 30, played: 2, won: 1, hops: 3), into: stats, now: t10)
        stats = first.stats
        #expect(Set(first.newBadges) == [.firstFlight, .firstWin])
        #expect(stats.flights == 1)
        #expect(stats.longestChain == 3)
        #expect(TravelPassport.record(trip("AZ|1|2026-01-01", people: 6, sent: 40, received: 30, played: 2, won: 1), into: stats).stats == stats)

        let t30 = Date(unixMilliseconds: 30)
        let second = TravelPassport.record(trip("AZ|2|2026-02-01", people: 5, sent: 20, received: 15, played: 1, won: 0, hops: 2), into: stats, now: t30)
        stats = second.stats
        #expect(Set(second.newBadges) == [.tenPeople, .hundredMessages])
        #expect(stats.peopleMet == 11)
        #expect(stats.messages == 105)
        #expect(stats.longestChain == 3)
        #expect(stats.badges[.firstFlight] == 10)
        #expect(stats.badges[.tenPeople] == 30)

        for index in 3...10 {
            stats = TravelPassport.record(trip("AZ|\(index)|2026-03-01", people: 5, sent: 0, received: 0, played: 2, won: 2), into: stats).stats
        }
        #expect(stats.flights == 10)
        #expect(Set(stats.badges.keys) == Set(PassportBadge.allCases))

        // Persistenza (JSON in UserDefaults).
        let store = TravelPassportStore(defaults: UserDefaults(suiteName: "passport-\(UUID().uuidString)")!)
        #expect(store.record(trip("AZ|1|2026-01-01", people: 1, sent: 1, received: 0, played: 0, won: 0)) == [.firstFlight])
        #expect(store.load().flights == 1)
        #expect(store.record(trip("AZ|1|2026-01-01", people: 1, sent: 1, received: 0, played: 0, won: 0)).isEmpty)
    }

    @Test("Posizione in chat: formato a 5 decimali, accuratezza, lettura e URL di Mappe")
    func locationTextFormatAndParse() throws {
        let text = LocationShare.format(latitude: 45.464211, longitude: 9.190006, accuracyMeters: 12.4)
        #expect(text == "📍 45.46421, 9.19001 (±12 m)")
        #expect(LocationShare.format(latitude: -33.868820, longitude: 151.209296, accuracyMeters: nil) == "📍 -33.86882, 151.20930")
        let point = try #require(LocationShare.parse(text))
        #expect(abs(point.latitude - 45.46421) < 1e-9)
        #expect(abs(point.longitude - 9.19001) < 1e-9)
        #expect(point.accuracyMeters == 12)
        let noAccuracy = try #require(LocationShare.parse("Siamo qui 📍 -33.86882, 151.20930 al rifugio"))
        #expect(noAccuracy.accuracyMeters == nil)
        #expect(abs(noAccuracy.latitude + 33.86882) < 1e-9)
        #expect(LocationShare.parse("45.46421, 9.19001") == nil)
        #expect(LocationShare.parse("📍 95.00000, 9.00000") == nil)
        #expect(LocationShare.mapsURL(for: point)?.absoluteString == "maps://?ll=45.46421,9.19001&q=45.46421,9.19001")
    }

    @Test("Emergenza in volo: un avviso ogni 10 minuti, 10 caratteri e conferma solo per 'Ho bisogno di aiuto'")
    func emergencyInFlightRules() {
        let sent = Date(timeIntervalSince1970: 1_000_000)
        #expect(EmergencySafety.canSend(lastSent: nil, now: sent))
        #expect(!EmergencySafety.canSend(lastSent: sent, now: sent.addingTimeInterval(9 * 60)))
        #expect(EmergencySafety.canSend(lastSent: sent, now: sent.addingTimeInterval(10 * 60)))
        #expect(EmergencySafety.minutesUntilNext(lastSent: sent, now: sent) == 10)
        #expect(EmergencySafety.minutesUntilNext(lastSent: sent, now: sent.addingTimeInterval(9 * 60 + 30)) == 1)
        #expect(EmergencySafety.minutesUntilNext(lastSent: nil, now: sent) == 0)
        #expect(!EmergencySafety.isHelpTextSufficient("  aiuto   "))
        #expect(EmergencySafety.isHelpTextSufficient("Malore fila 12"))
        #expect(EmergencySafety.requiresConfirmation(.needHelp, inFlight: true))
        #expect(!EmergencySafety.requiresConfirmation(.needHelp, inFlight: false))
        #expect(!EmergencySafety.requiresConfirmation(.ok, inFlight: true))
    }

    @Test("Promemoria del volo: alle 07:00 del giorno di partenza, mai nel passato")
    func flightReminder() throws {
        let rome = try #require(TimeZone(identifier: "Europe/Rome"))
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        var local = Calendar(identifier: .gregorian)
        local.timeZone = rome
        let departure = try #require(utc.date(from: DateComponents(year: 2026, month: 11, day: 12)))
        let expected = try #require(local.date(from: DateComponents(year: 2026, month: 11, day: 12, hour: 7)))
        func at(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
            local.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
        }
        #expect(FlightReminder.fireDate(departureDay: departure, now: at(10, 18), timeZone: rome) == expected)
        #expect(FlightReminder.fireDate(departureDay: departure, now: at(12, 6, 30), timeZone: rome) == expected)
        #expect(FlightReminder.fireDate(departureDay: departure, now: at(12, 8), timeZone: rome) == nil)
        #expect(FlightReminder.fireDate(departureDay: departure, now: at(13, 6), timeZone: rome) == nil)
        #expect(FlightReminder.label(canonicalKey: "AZ|204|2026-11-12") == "AZ204")
    }

    @Test("Pass del gruppo: messaggio di sistema con nickname e ora")
    func groupPassText() {
        let text = ProPassService.groupPassText(nickname: "Marco", validUntilMs: 1_700_000_000_000)
        #expect(text.hasPrefix("🎁 Marco"))
        #expect(text.contains(Date(unixMilliseconds: 1_700_000_000_000).formatted(date: .omitted, time: .shortened)))
    }

    @Test("Scambio posto: saluto con e senza il nostro posto")
    @MainActor
    func seatSwapGreeting() {
        #expect(AppEnvironment.seatSwapGreeting(mySeat: "14C").contains("14C"))
        #expect(!AppEnvironment.seatSwapGreeting(mySeat: nil).isEmpty)
    }
}
