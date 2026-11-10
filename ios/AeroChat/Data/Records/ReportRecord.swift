import Foundation
import GRDB

/// Motivo di una segnalazione (linee guida App Store 1.2: contenuti generati dagli utenti).
enum ReportReason: String, Codable, CaseIterable, Sendable, Identifiable {
    case spam
    case harassment
    case hateSpeech = "hate_speech"
    case sexualContent = "sexual_content"
    case violence
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .spam: return String(localized: "Spam o pubblicità")
        case .harassment: return String(localized: "Molestie o bullismo")
        case .hateSpeech: return String(localized: "Odio o discriminazione")
        case .sexualContent: return String(localized: "Contenuto sessuale")
        case .violence: return String(localized: "Violenza o minacce")
        case .other: return String(localized: "Altro")
        }
    }
}

/// Segnalazione di un messaggio, salvata sul dispositivo (la mesh non ha un server): il mittente viene
/// bloccato e l'utente puo' inoltrare il rapporto via email al team di sicurezza.
struct ReportRecord: Codable, Hashable, Sendable, FetchableRecord, MutablePersistableRecord {

    static let databaseTableName = "reports"

    var id: Int64?
    var msgID: Data
    var conversationID: String
    var reportedNodeID: Data
    var reportedNickname: String?
    var reason: ReportReason
    var details: String?
    /// Copia del testo segnalato: il messaggio originale puo' sparire (Cabina 12 h, blocco).
    var textSnapshot: String?
    var createdAtMs: Int64

    enum CodingKeys: String, CodingKey {
        case id
        case msgID = "msg_id"
        case conversationID = "conversation_id"
        case reportedNodeID = "reported_node_id"
        case reportedNickname = "reported_nickname"
        case reason
        case details
        case textSnapshot = "text_snapshot"
        case createdAtMs = "created_at_ms"
    }

    enum Columns {
        static let id = Column(CodingKeys.id)
        static let reportedNodeID = Column(CodingKeys.reportedNodeID)
        static let createdAtMs = Column(CodingKeys.createdAtMs)
    }

    init(message: MessageRecord, reason: ReportReason, details: String?, createdAt: Date = Date()) {
        self.id = nil
        self.msgID = message.msgID
        self.conversationID = message.conversationID
        self.reportedNodeID = message.senderNodeID
        self.reportedNickname = message.senderNickname
        self.reason = reason
        let trimmed = details?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.details = trimmed.isEmpty ? nil : String(trimmed.prefix(500))
        self.textSnapshot = message.text.map { String($0.prefix(1_000)) }
        self.createdAtMs = createdAt.unixMilliseconds
    }

    mutating func didInsert(_ inserted: InsertionSuccess) {
        id = inserted.rowID
    }

    /// Testo del rapporto da inoltrare via email (nessun dato personale oltre a nickname e id di nodo).
    var emailBody: String {
        var lines = [
            String(localized: "Segnalazione AeroChat"),
            String(localized: "Motivo: \(reason.title)"),
            String(localized: "Utente segnalato: \(reportedNickname ?? "-") (\(reportedNodeID.hexString))"),
            String(localized: "Messaggio: \(msgID.hexString)"),
            String(localized: "Data: \(Date(unixMilliseconds: createdAtMs).formatted(date: .abbreviated, time: .shortened))"),
        ]
        if let details { lines.append(String(localized: "Dettagli: \(details)")) }
        if let textSnapshot { lines.append(String(localized: "Testo: \(textSnapshot)")) }
        return lines.joined(separator: "\n")
    }
}
