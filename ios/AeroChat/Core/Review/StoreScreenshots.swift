#if DEBUG
import Foundation
import SwiftUI
import UIKit
import CryptoKit
import GRDB

/// Screenshot dell'App Store (solo DEBUG, mai in release): con `-storeScreenshots` l'app parte su un
/// database temporaneo con dati realistici, salta l'onboarding e usa un trasporto finto (nessuna radio).
/// `-storeScreen <nome>` apre direttamente una schermata: chats, conversation, cabin, lounge, cards,
/// nearby, flightcard. La lingua dei testi finti segue `-AppleLanguages` (italiano o inglese).
@MainActor
enum StoreScreenshots {

    enum Screen: String {
        case chats, conversation, cabin, lounge, cards, nearby, flightcard
    }

    nonisolated static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("-storeScreenshots")
    }

    static var screen: Screen {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "-storeScreen"), arguments.indices.contains(index + 1) else { return .chats }
        return Screen(rawValue: arguments[index + 1].lowercased()) ?? .chats
    }

    static var isItalian: Bool {
        (Locale.preferredLanguages.first ?? "it").hasPrefix("it")
    }

    /// Testo finto nelle due lingue degli screenshot.
    static func t(_ italian: String, _ english: String) -> String { isItalian ? italian : english }

    // MARK: Stato prodotto dal seed

    private(set) static var conversationToShow: ConversationRecord?
    private(set) static var flightLounge: ConversationRecord?
    private(set) static var typingConversationID: String?
    private(set) static var scopaState: GameSessionState?
    /// Carta della mano gia' selezionata al tavolo (evidenzia la presa).
    private(set) static var preselectedCard: Int?
    private(set) static var flightTrip: FlightTrip?
    /// Numeri completi della cartolina (foto, vocali, catena) al posto di quelli del solo volo.
    private(set) static var flightStats: FlightStats?
    private static var liveTask: Task<Void, Never>?

    // MARK: Ambiente

    /// Ambiente con DB e media temporanei, trasporto finto e notifiche mute (nessun permesso richiesto).
    static func makeEnvironment() -> AppEnvironment {
        if let bundleID = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundleID)
        }
        UserDefaults.standard.set(AppSettings.loungeRulesVersion, forKey: AppSettings.Key.loungeRulesAcceptedVersion)
        UserDefaults.standard.set(false, forKey: AppSettings.Key.demoMode)
        UserDefaults.standard.set(false, forKey: AppSettings.Key.smartReplies)
        return AppEnvironment(databaseFactory: { try AppDatabase.makeTemporary() },
                              transportFactory: { StoreScreenshotTransport(localNodeID: $0) },
                              mediaStoreFactory: { try MediaStore.makeTemporary() },
                              notifier: StoreScreenshotNotifier(),
                              tripStore: TripMemoryStore(key: "trip.memory.storeScreenshots"))
    }

    // MARK: Seed del database

    static func seed(database: AppDatabase, keys: IdentityKeys) async throws {
        let now = Date()
        // Orari dei messaggi coerenti con la barra di stato (09:41): l'ultimo messaggio e' delle 09:40.
        let clock = min(now, Calendar.current.date(bySettingHour: 9, minute: 40, second: 0, of: now) ?? now)
        func ago(_ minutes: Double) -> Int64 { clock.addingTimeInterval(-minutes * 60).unixMilliseconds }
        let myName = t("Giulia", "Julia")
        let myNodeID = keys.nodeID

        var me = IdentityRecord(identity: keys, nickname: myName, avatarColorHex: "EC4899")
        me.avatarImage = avatarImage(initial: String(myName.prefix(1)), hex: "EC4899")

        let pass = BoardingPass(carrier: "AZ", flightNumber: "204", origin: "FCO", destination: "JFK", seat: "14A",
                                departureDay: now)
        var lounge = ConversationRecord.flightLounge(pass, createdAt: now.addingTimeInterval(-3 * 3_600))
        lounge.isPinned = true

        let marco = SeedPeer.marco.record(now: now)
        let sofia = SeedPeer.sofia.record(now: now)
        let luca = SeedPeer.luca.record(now: now)
        let chiara = SeedPeer.chiara.record(now: now)
        let mamma = SeedPeer.mamma.record(now: now)

        var marcoChat = ConversationRecord.direct(with: marco, createdAt: now.addingTimeInterval(-86_400 * 20))
        var sofiaChat = ConversationRecord.direct(with: sofia, createdAt: now.addingTimeInterval(-86_400 * 9))
        var lucaChat = ConversationRecord.direct(with: luca, createdAt: now.addingTimeInterval(-86_400 * 4))
        var chiaraChat = ConversationRecord.direct(with: chiara, createdAt: now.addingTimeInterval(-86_400 * 30))
        var mammaChat = ConversationRecord.direct(with: mamma, createdAt: now.addingTimeInterval(-86_400 * 60))
        var tripGroup = ConversationRecord.group(id: UUID(), title: t("Vacanza a New York 🗽", "New York trip 🗽"),
                                                 secretKey: CryptoEngine.randomGroupSecretKey(),
                                                 createdAt: now.addingTimeInterval(-86_400 * 12))

        // Messaggi: (conversazione, mittente, nickname, testo, minuti fa, uscente, stato, salti)
        var messages: [MessageRecord] = []
        func text(_ conversation: ConversationRecord, _ peer: PeerRecord?, name: String? = nil, _ body: String,
                  _ minutes: Double, status: DeliveryStatus = .read, hops: Int = 1) {
            let outgoing = peer == nil && name == nil
            messages.append(MessageRecord(msgID: CryptoEngine.randomMessageID(), conversationID: conversation.id,
                                          senderNodeID: outgoing ? myNodeID : (peer?.nodeID ?? SeedPeer.nodeID(for: name ?? "")),
                                          senderNickname: outgoing ? myName : (peer?.nickname ?? name),
                                          contentType: .text, text: body, isOutgoing: outgoing,
                                          deliveryStatus: outgoing ? status : .read,
                                          hopCountReceived: outgoing ? nil : hops, createdAtMs: ago(minutes)))
        }

        // Marco: raggiungibile solo tramite altri passeggeri (schermata 2).
        text(marcoChat, marco, t("Sono al gate B12, tu dove sei?", "I'm at gate B12, where are you?"), 58, hops: 2)
        text(marcoChat, nil, t("Già a bordo! Fila 14, lato finestrino ✈️", "Already on board! Row 14, window seat ✈️"), 56)
        text(marcoChat, marco, t("Io sono in fila 27, tutto in fondo 😅", "I'm in row 27, all the way at the back 😅"), 41, hops: 2)
        text(marcoChat, nil, t("Ti scrivo senza Wi-Fi, che magia", "Texting you with no Wi-Fi, this is magic"), 39)
        text(marcoChat, marco, t("Il tuo messaggio è passato dai telefoni di due passeggeri!", "Your message just hopped through two passengers' phones!"), 37, hops: 3)
        text(marcoChat, nil, t("All'arrivo dividiamo il taxi per Manhattan?", "Split a cab to Manhattan when we land?"), 12)
        text(marcoChat, marco, t("Affare fatto 🚕 Ci vediamo al nastro bagagli", "Deal 🚕 See you at baggage claim"), 9, hops: 2)
        text(marcoChat, nil, t("Perfetto, a dopo!", "Perfect, see you later!"), 7, status: .deliveredAck)

        // Sofia: partita a Scopa in corso.
        let gameMsgID = CryptoEngine.randomMessageID()
        text(sofiaChat, sofia, t("Rivincita a Scopa? Stavolta il settebello è mio 😏", "Rematch at Scopa? The settebello is mine this time 😏"), 30)
        text(sofiaChat, nil, t("Accetto la sfida!", "Challenge accepted!"), 29)
        if let (state, card) = makeScopaState(gameID: gameMsgID.hexString, conversationID: sofiaChat.id, myNodeID: myNodeID,
                                              myName: myName, opponent: sofia) {
            scopaState = state
            preselectedCard = card
            messages.append(MessageRecord(msgID: gameMsgID, conversationID: sofiaChat.id, senderNodeID: myNodeID,
                                          senderNickname: myName, contentType: .game, gameState: try state.encoded(),
                                          isOutgoing: true, deliveryStatus: .read, createdAtMs: ago(28)))
        }
        text(sofiaChat, sofia, t("Tocca a te! 🃏", "Your turn! 🃏"), 3)

        // Luca: sta scrivendo.
        text(lucaChat, luca, t("Hai visto che film ci sono a bordo?", "Have you seen the movies on board?"), 22)
        text(lucaChat, nil, t("Sto guardando Dune, tu?", "Watching Dune, you?"), 20)

        // Gruppo di viaggio.
        text(tripGroup, chiara, t("Chi prenota il brunch domenica? 🥞", "Who's booking Sunday brunch? 🥞"), 95)
        text(tripGroup, marco, t("Io! Tavolo per 4 a Brooklyn", "Me! Table for 4 in Brooklyn"), 90, hops: 2)
        text(tripGroup, sofia, t("Allora sabato si va al MoMA", "Then MoMA on Saturday"), 18)

        // Chiara e Mamma: fuori portata, il messaggio aspetta.
        text(chiaraChat, chiara, t("Buon viaggio! Mandami una foto di New York 🗽", "Have a great trip! Send me a pic of New York 🗽"), 60 * 20)
        text(chiaraChat, nil, t("Promesso!", "Promise!"), 60 * 19.8)
        text(mammaChat, nil, t("Decollati! Ti scrivo appena atterro ❤️", "We took off! I'll text you when I land ❤️"), 150, status: .queued)

        // Cabina del volo AZ 204.
        let flightNames = SeedPeer.flightNames
        text(lounge, nil, name: flightNames.elena, t("Qualcuno sa se da JFK c'è un treno per Manhattan? 🚆", "Anyone know if there's a train from JFK to Manhattan? 🚆"), 34)
        text(lounge, nil, name: flightNames.davide, t("AirTrain fino a Jamaica, poi la metro E. Circa 45 minuti.", "AirTrain to Jamaica, then the E subway. About 45 minutes."), 32, hops: 2)
        text(lounge, nil, name: flightNames.kenji, t("Fila 31: si vede la Groenlandia dal finestrino! 🏔️", "Row 31: you can see Greenland out the window! 🏔️"), 21, hops: 3)
        text(lounge, nil, t("Qualcuno ha un caricatore USB-C da prestare? Fila 14 🔌", "Anyone have a USB-C charger to lend? Row 14 🔌"), 15)
        text(lounge, sofia, t("Ce l'ho io, te lo passo dopo il carrello ☕️", "I do, I'll pass it over after the drinks cart ☕️"), 14)
        text(lounge, nil, name: flightNames.elena, t("Chi fa una partita a Scopa per ingannare il tempo? 🃏", "Who's up for a card game to pass the time? 🃏"), 6)
        text(lounge, luca, t("Io ci sto! Sfidami da Persone vicine", "I'm in! Challenge me from Nearby"), 4)

        // Cabina Pubblica (12 ore).
        var cabin = ConversationRecord.cabinLounge(createdAt: now.addingTimeInterval(-2 * 3_600))
        text(cabin, nil, name: t("Roberta", "Rachel"), t("Il comandante dice 20 minuti di anticipo 🎉", "Captain says we're 20 minutes early 🎉"), 50, hops: 2)
        text(cabin, nil, name: "Tom", t("Qualcuno va a Brooklyn? Dividiamo un taxi", "Anyone heading to Brooklyn? Let's share a cab"), 44)
        text(cabin, nil, name: t("Lucia", "Lucy"), t("Consiglio: il tiramisù del menu è ottimo 😋", "Tip: the tiramisu on the menu is great 😋"), 31, hops: 3)
        text(cabin, nil, t("Io! Scrivimi dopo l'atterraggio", "Me! Message me after landing"), 29)
        text(cabin, nil, name: "Ahmed", t("Bimbo in fila 9 che non dorme: chi ha delle carte da prestare? 🃏", "Kid in row 9 can't sleep: anyone have cards to lend? 🃏"), 11, hops: 2)

        func last(_ conversation: ConversationRecord) -> Int64? {
            messages.filter { $0.conversationID == conversation.id }.map(\.createdAtMs).max()
        }
        marcoChat.lastMessageAtMs = last(marcoChat)
        sofiaChat.lastMessageAtMs = last(sofiaChat)
        sofiaChat.unreadCount = 1
        lucaChat.lastMessageAtMs = last(lucaChat)
        chiaraChat.lastMessageAtMs = last(chiaraChat)
        mammaChat.lastMessageAtMs = last(mammaChat)
        tripGroup.lastMessageAtMs = last(tripGroup)
        tripGroup.unreadCount = 3
        lounge.lastMessageAtMs = last(lounge)
        lounge.unreadCount = 5
        cabin.lastMessageAtMs = last(cabin)

        // Passeggeri non ancora contatti: solo il profilo (foto nelle Persone vicine).
        let peers = [marco, sofia, luca, chiara, mamma]
            + [SeedPeer.elena, .davide, .kenji, .paolo, .anna].map { $0.record(now: now, trusted: false) }
        let conversations = [marcoChat, sofiaChat, lucaChat, chiaraChat, mammaChat, tripGroup, lounge, cabin]
        let groupMembers = [chiara, marco, sofia].map { GroupMemberRecord(conversationID: tripGroup.id, peer: $0) }
            + SeedPeer.flightCabinMembers(now: now).map { GroupMemberRecord(conversationID: lounge.id, peer: $0) }
        let allMessages = messages
        let identity = me
        try await database.pool.write { db in
            try identity.save(db)
            for peer in peers { try peer.insert(db) }
            for conversation in conversations { try conversation.insert(db) }
            for member in groupMembers { try member.save(db) }
            for message in allMessages { try message.insert(db) }
        }

        conversationToShow = marcoChat
        flightLounge = lounge
        typingConversationID = lucaChat.id

        // Cartolina del volo (ricordo del viaggio) con classifica.
        var trip = FlightTrip(flightCode: pass.canonicalKey, title: pass.loungeTitle,
                              startedAtMs: ago(6 * 60), endedAtMs: ago(0))
        trip.messagesSent = 48
        trip.messagesReceived = 79
        trip.peopleMet = 14
        trip.gamesWon = 3
        trip.gamesPlayed = 4
        trip.leaderboard = [
            .init(nodeIDHex: myNodeID.hexString, name: myName, wins: 3, played: 4, isMe: true),
            .init(nodeIDHex: sofia.nodeID.hexString, name: sofia.nickname, wins: 2, played: 3, isMe: false),
            .init(nodeIDHex: SeedPeer.nodeID(for: flightNames.davide).hexString, name: flightNames.davide, wins: 1, played: 2, isMe: false),
            .init(nodeIDHex: SeedPeer.nodeID(for: flightNames.elena).hexString, name: flightNames.elena, wins: 0, played: 1, isMe: false),
        ]
        trip.notified = true
        flightTrip = trip
        var stats = FlightStats(trip: trip)
        stats.photos = 6
        stats.voiceNotes = 4
        stats.longestHop = 3
        flightStats = stats
    }

    // MARK: Stato vivo (in memoria)

    /// Persone vicine, richiesta "Sfida" in arrivo, "sta scrivendo" e telemetria del ponte, tenuti vivi.
    static func startLiveState(_ environment: AppEnvironment) {
        let now = Date()
        let flightCode = flightLounge?.flightCode
        for person in SeedPeer.nearbyPeople(flightCode: flightCode, now: now) {
            environment.presence.update(person)
        }
        let challenger = SeedPeer.riccardo.record(now: now)
        environment.contactRequests.received(IncomingContactRequest(
            requestID: Data((0..<NearbyProtocol.requestIDLength).map { _ in UInt8.random(in: 0...255) }),
            sender: challenger, avatarColorHex: SeedPeer.riccardo.colorHex,
            greeting: t("Partita veloce prima dell'atterraggio?", "Quick game before we land?"),
            gameKind: Int32(GameKind.scopa.rawValue), receivedAt: now))
        environment.contactRequests.banner = nil

        liveTask?.cancel()
        liveTask = Task { @MainActor [weak environment] in
            var tick = 0
            while !Task.isCancelled, let environment {
                if let id = typingConversationID {
                    environment.typing.update(conversationID: id, senderNodeID: SeedPeer.luca.nodeID,
                                              nickname: SeedPeer.luca.name, isTyping: true)
                }
                if tick % 10 == 0 {
                    for person in SeedPeer.nearbyPeople(flightCode: flightCode, now: Date()) {
                        environment.presence.update(person)
                    }
                    // Marco resta "tramite 1 persona" (la freschezza del ponte e' di 2 minuti).
                    try? await environment.database?.peers.markSeen(nodeID: SeedPeer.marco.nodeID, hopCount: 2, rssi: nil)
                }
                tick += 1
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    // MARK: Partita a Scopa

    /// Seconda mano di una partita (prima mano giocata davvero dal motore), ferma al nostro turno con
    /// una presa disponibile. Restituisce lo stato e la carta da selezionare.
    private static func makeScopaState(gameID: String, conversationID: String, myNodeID: Data, myName: String,
                                       opponent: PeerRecord) -> (GameSessionState, Int)? {
        var rng = SplitMix64(seed: 204)
        for _ in 0..<2_000 {
            guard var game = try? ScopaGame(playerCount: 2, dealer: 1, deck: Array(0..<40).shuffled(using: &rng)) else { continue }
            var moves = 0
            while game.phase == .playing, botPlay(&game) { moves += 1 }
            guard game.phase == .handOver, game.scores[0] > game.scores[1], game.scores[1] > 0 else { continue }
            guard (try? game.deal(deck: Array(0..<40).shuffled(using: &rng), dealer: game.nextDealer)) != nil else { continue }
            let target = 5 + Int(rng.next() % 6)
            var played = 0
            while played < target, game.phase == .playing, botPlay(&game) { played += 1; moves += 1 }
            guard game.phase == .playing, game.turn == 0, game.hands[0].count == 3,
                  (3...5).contains(game.table.count), game.remainingInDeck > 0,
                  game.scope[0] + game.scope[1] > 0 else { continue }
            // Carta con una sola presa di almeno due carte: l'evidenziazione si vede bene.
            guard let card = game.hands[0].first(where: { card in
                let options = game.captureOptions(for: card)
                return options.count == 1 && options[0].count >= 2
            }) else { continue }
            var state = GameSessionState(gameID: gameID, kind: GameKind.scopa.rawValue, conversationID: conversationID,
                                         opponentNodeID: opponent.nodeID, iAmInviter: true)
            state.status = .active
            state.sequence = UInt32(moves + 2)
            state.seats = [myNodeID, opponent.nodeID]
            state.seatNames = [myName, opponent.nickname]
            state.mySeat = 0
            state.scopa = game
            state.myTurn = true
            return (state, card)
        }
        return nil
    }

    /// Giocatore automatico semplice: la presa piu' ricca, altrimenti la carta piu' bassa.
    private static func botPlay(_ game: inout ScopaGame) -> Bool {
        let seat = game.turn
        let hand = game.hands[seat]
        var best: (card: Int, capture: [Int])?
        for card in hand {
            for option in game.captureOptions(for: card) where option.count >= (best?.capture.count ?? 0) {
                best = (card, option)
            }
        }
        if let best {
            return (try? game.play(seat: seat, card: best.card, captured: best.capture)) != nil
        }
        guard let card = hand.min(by: { NeapolitanCards.rank($0) < NeapolitanCards.rank($1) }) else { return false }
        return (try? game.play(seat: seat, card: card, captured: [])) != nil
    }

    // MARK: Avatar

    /// Avatar con l'iniziale su sfumatura del colore scelto (PNG, come una foto profilo).
    static func avatarImage(initial: String, hex: String) -> Data? {
        let size = CGSize(width: 160, height: 160)
        let base = UIColor(Color(hexString: hex) ?? AeroTheme.accentCyan)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let colors = [base.withAlphaComponent(1).cgColor, base.mixed(with: .black, amount: 0.35).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
            }
            let font = UIFont.systemFont(ofSize: 76, weight: .bold).rounded
            let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
            let text = initial.uppercased() as NSString
            let textSize = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (size.width - textSize.width) / 2, y: (size.height - textSize.height) / 2), withAttributes: attributes)
        }
        return image.pngData()
    }
}

// MARK: - Persone finte

/// Compagni di viaggio finti con chiavi deterministiche (stessi `node_id` a ogni avvio).
struct SeedPeer: Sendable {
    let tag: String
    let italian: String
    let english: String
    let colorHex: String
    /// RSSI del link diretto (nil = non e' un vicino radio).
    let rssi: Int?

    var name: String { (Locale.preferredLanguages.first ?? "it").hasPrefix("it") ? italian : english }

    static let marco = SeedPeer(tag: "marco", italian: "Marco", english: "Mark", colorHex: "F59E0B", rssi: nil)
    static let sofia = SeedPeer(tag: "sofia", italian: "Sofia", english: "Sophie", colorHex: "8B5CF6", rssi: -52)
    static let luca = SeedPeer(tag: "luca", italian: "Luca", english: "Luke", colorHex: "10B981", rssi: -63)
    static let chiara = SeedPeer(tag: "chiara", italian: "Chiara", english: "Claire", colorHex: "F97316", rssi: nil)
    static let mamma = SeedPeer(tag: "mamma", italian: "Mamma", english: "Mom", colorHex: "00E5FF", rssi: nil)
    static let elena = SeedPeer(tag: "elena", italian: "Elena", english: "Emily", colorHex: "00E5FF", rssi: -60)
    static let davide = SeedPeer(tag: "davide", italian: "Davide", english: "David", colorHex: "F59E0B", rssi: -68)
    static let kenji = SeedPeer(tag: "kenji", italian: "Kenji", english: "Kenji", colorHex: "10B981", rssi: -74)
    static let paolo = SeedPeer(tag: "paolo", italian: "Paolo", english: "Paul", colorHex: "F97316", rssi: nil)
    static let anna = SeedPeer(tag: "anna", italian: "Anna", english: "Hannah", colorHex: "EC4899", rssi: nil)
    static let riccardo = SeedPeer(tag: "riccardo", italian: "Riccardo", english: "Ryan", colorHex: "8B5CF6", rssi: nil)

    /// Vicini radio del trasporto finto.
    static let neighbors: [SeedPeer] = [sofia, luca, elena, davide, kenji]

    static var flightNames: (elena: String, davide: String, kenji: String) { (elena.name, davide.name, kenji.name) }

    var keys: IdentityKeys {
        let x = Data(SHA256.hash(data: Data("AeroChat-StoreScreenshots-x25519-\(tag)".utf8)))
        let e = Data(SHA256.hash(data: Data("AeroChat-StoreScreenshots-ed25519-\(tag)".utf8)))
        // 32 byte qualsiasi sono una chiave privata Curve25519 valida.
        return try! IdentityKeys(x25519PrivateRaw: x, ed25519PrivateRaw: e)
    }

    var nodeID: Data { keys.nodeID }

    static func nodeID(for name: String) -> Data {
        [elena, davide, kenji, paolo, anna, riccardo, marco, sofia, luca, chiara].first { $0.name == name }?.nodeID
            ?? Data(SHA256.hash(data: Data(name.utf8))).prefix(MeshProtocol.nodeIDLength)
    }

    @MainActor
    func record(now: Date, trusted: Bool = true) -> PeerRecord {
        let keys = keys
        var peer = PeerRecord(nodeID: keys.nodeID, x25519Pub: keys.x25519PublicKeyRaw, ed25519Pub: keys.ed25519PublicKeyRaw,
                              nickname: name, isTrusted: trusted, createdAt: now.addingTimeInterval(-86_400 * 30))
        peer.avatarColorHex = colorHex
        peer.avatarImage = StoreScreenshots.avatarImage(initial: String(name.prefix(1)), hex: colorHex)
        peer.profileUpdatedAtMs = now.unixMilliseconds
        if self.tag == SeedPeer.marco.tag {
            peer.lastSeenAtMs = now.unixMilliseconds
            peer.lastHopCount = 2
        }
        return peer
    }

    /// Membri della Cabina del volo (i vicini radio sul volo).
    @MainActor
    static func flightCabinMembers(now: Date) -> [PeerRecord] {
        [sofia, luca, elena, davide, kenji].map { $0.record(now: now, trusted: false) }
    }

    /// Persone vicine (PRESENCE finti): quattro sul nostro volo, due no.
    static func nearbyPeople(flightCode: String?, now: Date) -> [NearbyPerson] {
        let entries: [(SeedPeer, Bool, Bool, Int)] = [
            (sofia, true, false, -52),
            (elena, true, true, -60),
            (davide, true, false, -66),
            (kenji, true, true, -74),
            (paolo, false, true, -79),
            (anna, false, false, -86),
        ]
        return entries.map { peer, onFlight, games, rssi in
            let keys = peer.keys
            return NearbyPerson(nodeID: keys.nodeID, x25519Pub: keys.x25519PublicKeyRaw, ed25519Pub: keys.ed25519PublicKeyRaw,
                                nickname: peer.name, avatarColorHex: peer.colorHex,
                                flightCode: onFlight ? flightCode : nil, openToGames: games, rssi: rssi, lastSeen: now)
        }
    }
}

// MARK: - Trasporto e notifiche finti

/// Trasporto senza radio: Bluetooth acceso e qualche vicino collegato; i frame in uscita vengono scartati.
actor StoreScreenshotTransport: TransportProvider {

    nonisolated let localNodeID: Data
    private let links: [PeerLink]
    private let stream: AsyncStream<TransportEvent>
    private let continuation: AsyncStream<TransportEvent>.Continuation

    init(localNodeID: Data) {
        self.localNodeID = localNodeID
        links = SeedPeer.neighbors.map { peer in
            PeerLink(id: LinkID(deviceID: UUID(), role: .central), nodeID: peer.nodeID, rssi: peer.rssi, connectedAt: Date())
        }
        (stream, continuation) = AsyncStream.makeStream(of: TransportEvent.self, bufferingPolicy: .unbounded)
    }

    func start() async {
        continuation.yield(.radioStateChanged(.poweredOn))
        for link in links { continuation.yield(.linkOpened(link)) }
    }

    func stop() async { continuation.finish() }
    func events() async -> AsyncStream<TransportEvent> { stream }
    func radioState() async -> RadioState { .poweredOn }
    func activeLinks() async -> [PeerLink] { links }
    func send(_ frame: Data, to link: LinkID) async throws {}
    func send(_ frame: Data, toNode nodeID: Data) async throws -> Bool { links.contains { $0.nodeID == nodeID } }
    func broadcast(_ frame: Data, excluding: Set<LinkID>) async -> Int { links.filter { !excluding.contains($0.id) }.count }
    func setTrustedPeripheralIdentifiers(_ identifiers: Set<UUID>) async {}
}

/// Nessuna notifica (e nessuna richiesta di permesso) durante gli screenshot.
struct StoreScreenshotNotifier: MeshNotifier {
    func notify(message: MessageRecord, conversation: ConversationRecord) async {}
    func notifyContactRequest(_ request: IncomingContactRequest) async {}
}

/// Generatore deterministico (stessa partita a ogni avvio).
struct SplitMix64: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

private extension UIColor {
    func mixed(with other: UIColor, amount: CGFloat) -> UIColor {
        var (r1, g1, b1, a1): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        var (r2, g2, b2, a2): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        other.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(red: r1 + (r2 - r1) * amount, green: g1 + (g2 - g1) * amount, blue: b1 + (b2 - b1) * amount, alpha: 1)
    }
}

private extension UIFont {
    var rounded: UIFont {
        guard let descriptor = fontDescriptor.withDesign(.rounded) else { return self }
        return UIFont(descriptor: descriptor, size: pointSize)
    }
}

// MARK: - Schermata da aprire

/// Apre la schermata richiesta con `-storeScreen` appena la mesh finta e' pronta.
struct StoreScreenshotPresenter: ViewModifier {

    @Binding var selectedTab: MainTabView.Tab
    @Environment(AppEnvironment.self) private var environment
    @State private var showCardTable = false

    func body(content: Content) -> some View {
        content
            .fullScreenCover(isPresented: $showCardTable) {
                if let state = StoreScreenshots.scopaState {
                    CardTableView(state: state) { _ in }
                }
            }
            .task {
                guard StoreScreenshots.isEnabled else { return }
                try? await Task.sleep(for: .seconds(1.5))
                switch StoreScreenshots.screen {
                case .chats:
                    break
                case .conversation:
                    environment.requestedConversation = StoreScreenshots.conversationToShow
                case .cabin:
                    environment.requestedConversation = StoreScreenshots.flightLounge
                case .lounge:
                    selectedTab = .cabin
                case .cards:
                    showCardTable = true
                case .nearby:
                    environment.requestedNearby = .all
                case .flightcard:
                    environment.requestedTripCard = StoreScreenshots.flightTrip
                }
            }
    }
}
#endif
