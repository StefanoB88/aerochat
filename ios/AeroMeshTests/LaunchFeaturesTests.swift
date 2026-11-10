import Foundation
import Testing
@testable import AeroChat

/// Funzioni introdotte per il lancio sull'App Store: moderazione, carta d'imbarco, quiz, emergenze, pass.
struct LaunchFeaturesTests {

    // MARK: - Filtro linguaggio

    @Test func contentFilterMasksWholeWordsOnly() {
        #expect(ContentFilter.containsProfanity("Che cazzo di ritardo"))
        #expect(!ContentFilter.containsProfanity("Il cazzuolo del muratore"))  // parola diversa, non filtrata
        #expect(ContentFilter.masked("Che cazzo di ritardo") == "Che c**** di ritardo")
        #expect(ContentFilter.masked("Buon volo a tutti") == "Buon volo a tutti")
        // Accenti e maiuscole non aggirano il filtro.
        #expect(ContentFilter.containsProfanity("SCHEIßE, wieder Verspätung"))
        #expect(ContentFilter.masked("Putaín") == "P*****")
    }

    // MARK: - Carta d'imbarco (IATA BCBP)

    @Test func boardingPassParsesStandardBarcode() throws {
        // Formato IATA 792, un segmento: nome, PNR, FCO->LHR, AZ 0204, giorno 314, classe Y, posto 12A.
        let raw = "M1ROSSI/MARIO         EABC123 FCOLHRAZ 0204 314Y012A0001 100"
        let now = ISO8601DateFormatter().date(from: "2026-11-10T12:00:00Z")!
        let pass = try BoardingPass(bcbp: raw, now: now)
        #expect(pass.carrier == "AZ")
        #expect(pass.flightNumber == "204")
        #expect(pass.origin == "FCO")
        #expect(pass.destination == "LHR")
        #expect(pass.seat == "12A")
        #expect(pass.canonicalKey == "AZ|204|2026-11-10")
        #expect(pass.flightCode == "AZ 204")
    }

    @Test func boardingPassJulianDayPicksNearestYear() {
        // 2 gennaio 2027: il giorno 365 appartiene al 2026 (31 dicembre), non al 2027.
        let now = ISO8601DateFormatter().date(from: "2027-01-02T08:00:00Z")!
        let date = BoardingPass.date(julianDay: 365, near: now)
        let formatter = DateFormatter()
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        #expect(formatter.string(from: date) == "2026-12-31")
    }

    @Test func boardingPassManualEntryAndDerivation() throws {
        let day = ISO8601DateFormatter().date(from: "2026-11-10T20:30:00Z")!
        let a = try #require(BoardingPass(flightText: "az 0204", departureDay: day))
        let b = try #require(BoardingPass(flightText: "AZ-204", departureDay: day))
        // Stesso volo e stessa data: stesso gruppo e stessa chiave su ogni telefono.
        #expect(a.canonicalKey == b.canonicalKey)
        #expect(a.loungeGroupID == b.loungeGroupID)
        #expect(a.loungeSecretKey == b.loungeSecretKey)
        #expect(a.loungeSecretKey.count == 32)
        // UUID v4 valido (bit di versione e variante).
        let bytes = a.loungeGroupID.rawBytes
        #expect(bytes[6] >> 4 == 4)
        #expect(bytes[8] >> 6 == 0b10)
        #expect(BoardingPass(flightText: "204", departureDay: day) == nil)
        #expect(BoardingPass(flightText: "ABCD", departureDay: day) == nil)
    }

    @Test func boardingPassRejectsNonBarcode() {
        #expect(throws: BoardingPass.ParseError.self) {
            try BoardingPass(bcbp: "aeromesh://v1?x=abc")
        }
    }

    @Test func flightLoungeRecordIsFreeTextGroup() throws {
        let day = ISO8601DateFormatter().date(from: "2026-11-10T20:30:00Z")!
        let pass = try #require(BoardingPass(flightText: "LH123", departureDay: day))
        let lounge = ConversationRecord.flightLounge(pass)
        #expect(lounge.type == .group)
        #expect(lounge.isFlightLounge)
        #expect(lounge.expiresAtMs != nil)
        #expect(FeatureGate.decision(for: .groupChat, userIsPro: false, conversation: lounge, reachability: .outOfRange) == .allowed)
        #expect(FeatureGate.decision(for: .photo, userIsPro: false, conversation: lounge, reachability: .direct(rssi: -50)) == .requiresPass)
    }

    // MARK: - Protocollo: quiz ed emergenze

    @Test func quizPacketRoundTrip() throws {
        var packet = QuizPacket()
        packet.quizID = "abc123"
        packet.action = .question
        packet.round = 2
        packet.totalRounds = 5
        packet.question = "Capitale dell'Australia?"
        packet.options = ["Sydney", "Canberra"]
        packet.hostNodeID = Data(repeating: 7, count: 8)
        packet.deadlineMs = 1_800_000_000_000
        var score = QuizScore()
        score.nodeID = Data(repeating: 1, count: 8)
        score.nickname = "Anna"
        score.points = 150
        packet.scores = [score]
        let decoded = try MeshWire.parseQuizPacket(try MeshWire.serialize(packet))
        #expect(decoded == packet)
        #expect(PacketType.quiz.rawValue == 12)
        #expect(!PacketType.quiz.isStoreAndForwardEligible)
    }

    @Test func emergencyBroadcastRoundTrip() throws {
        var broadcast = EmergencyBroadcast()
        broadcast.status = .needHelp
        broadcast.text = "Fila 23, passeggero svenuto"
        broadcast.latitude = 41.8
        broadcast.longitude = 12.25
        broadcast.hasLocation_p = true
        broadcast.issuedAtMs = 1_800_000_000_000
        let decoded = try MeshWire.parseEmergencyBroadcast(try MeshWire.serialize(broadcast))
        #expect(decoded == broadcast)
        #expect(PacketType.emergency.rawValue == 13)
        #expect(PacketType.emergency.isStoreAndForwardEligible)
        #expect(EmergencyService.systemText(nickname: "Luca", broadcast: broadcast).contains("🆘 Luca"))
    }

    // MARK: - Riassunto Cabina

    @Test func summaryKeepsOnlyGroundedPoints() {
        let lines = [
            CabinAssistant.ChatLine(author: "Marco", text: "Il volo per Londra ha 40 minuti di ritardo"),
            CabinAssistant.ChatLine(author: "Anna", text: "Qualcuno divide un taxi da Heathrow?"),
            CabinAssistant.ChatLine(author: "Luca", text: "Io ci sto per il taxi"),
        ]
        let result = CabinAssistant.grounded([
            ("Il volo ha 40 minuti di ritardo", [1]),
            ("Anna e Luca dividono un taxi", [2, 3]),
            ("Tutti sono molto felici del pranzo", [2]),   // inventato: nessuna parola in comune
            ("Si parla di un concerto", [9]),             // riga inesistente
            ("Il volo ha 40 minuti di ritardo", [1]),     // duplicato
        ], lines: lines)
        #expect(result == ["Il volo ha 40 minuti di ritardo", "Anna e Luca dividono un taxi"])
    }

    // MARK: - Pass

    @Test func singleFlightPassTier() {
        #expect(ProTier(productID: MeshProtocol.ProductID.pass24h) == .pass24h)
        #expect(ProTier.pass24h.passDuration == TimeInterval(86_400))
        #expect(ProTier.pass24h.isTimeLimited)
        #expect(!ProTier.lifetime.isTimeLimited)
        let active = EntitlementState(tier: .pass24h, validUntilMs: Date().addingTimeInterval(3_600).unixMilliseconds, source: .storeKit)
        #expect(active.isPro())
        let expired = EntitlementState(tier: .pass24h, validUntilMs: Date().addingTimeInterval(-60).unixMilliseconds, source: .storeKit)
        #expect(!expired.isPro())
    }

    // MARK: - Segnalazioni

    @Test func reportsArePersisted() async throws {
        let database = try AppDatabase.makeTemporary()
        let lounge = try await database.conversations.currentCabinLounge()
        let message = MessageRecord(msgID: CryptoEngine.randomMessageID(), conversationID: lounge.id,
                                    senderNodeID: Data(repeating: 9, count: 8), senderNickname: "Troll",
                                    contentType: .text, text: "spam spam", isOutgoing: false, deliveryStatus: .deliveredAck)
        _ = try await database.messages.insert(message)
        let report = try await database.reports.insert(ReportRecord(message: message, reason: .spam, details: "  link a un sito  "))
        #expect(report.id != nil)
        #expect(report.details == "link a un sito")
        #expect(try await database.reports.count() == 1)
        #expect(report.emailBody.contains("Troll"))
        try await database.reports.delete(id: report.id!)
        #expect(try await database.reports.count() == 0)
    }

    @Test func flightLoungeJoinIsIdempotent() async throws {
        let database = try AppDatabase.makeTemporary()
        let day = ISO8601DateFormatter().date(from: "2026-11-10T20:30:00Z")!
        let pass = try #require(BoardingPass(flightText: "FR1234", departureDay: day))
        let first = try await database.conversations.joinFlightLounge(pass)
        let second = try await database.conversations.joinFlightLounge(pass)
        #expect(first.id == second.id)
        #expect(try await database.conversations.activeFlightLounges().count == 1)
        #expect(try await database.conversations.fetchPermanent().contains { $0.id == first.id })
    }
}
