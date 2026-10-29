import Foundation

/// Vicino radio raggiungibile direttamente (1 hop).
struct Neighbor: Sendable, Hashable, Identifiable {
    let nodeID: Data
    var rssi: Int?
    var linkCount: Int

    var id: Data { nodeID }
}

/// Indicatore semantico "Stato Radio" dell'header chat.
enum Reachability: Sendable, Equatable {
    /// 🟢 Connessione diretta (`hop_count == 1`): testo, vocali e foto attivi.
    case direct(rssi: Int?)
    /// 🟡 Connesso tramite ponte: testo e mini-vocali, foto disabilitate.
    case viaBridge(hops: Int)
    /// ⚪ Fuori portata: invio in coda.
    case outOfRange

    var allowsPhotos: Bool {
        if case .direct = self { return true }
        return false
    }

    var allowsRealtimeDelivery: Bool {
        self != .outOfRange
    }
}

/// Eventi del router verso la UI.
enum RouterEvent: Sendable, Equatable {
    case radioStateChanged(RadioState)
    case neighborsChanged([Neighbor])
    case messageReceived(conversationID: String, msgID: Data)
    case relayQueueChanged(count: Int)
    case groupMembersChanged(conversationID: String)
}

/// Pacchetto decifrato e verificato che il router non gestisce da solo (media, giochi, Pro Pass):
/// viene consegnato ai sottosistemi delle fasi successive tramite `inboundPackets()`.
struct InboundPacket: Sendable {
    let envelope: MeshEnvelope
    let payload: InnerPayload
    let conversation: ConversationRecord
    let sender: PeerRecord
    /// 1 = ricevuto direttamente dal mittente, N = attraversati N-1 ponti.
    let hopCount: Int
}

/// Statistiche del router (diagnostica e test).
struct RouterStats: Sendable, Equatable {
    var received = 0
    var delivered = 0
    var relayed = 0
    var duplicates = 0
    var malformed = 0
    var undecryptable = 0
    var queued = 0
    /// Pacchetti scartati dalla finestra anti-replay (troppo vecchi o nel futuro).
    var replayRejected = 0
    /// Relay annullati perche' un vicino ha ritrasmesso prima di noi (backoff del flooding).
    var relaySuppressed = 0
}

enum RouterError: Error, Equatable {
    case notRunning
    case unknownPeer(Data)
    case missingGroupKey
    case invalidGossip(String)
    case invalidConversation
    case senderMismatch
    case linkNotAllowedInLounge
}

/// Consegna notifiche locali a schermo spento. Implementato con `UNUserNotificationCenter`.
protocol MeshNotifier: Sendable {
    func notify(message: MessageRecord, conversation: ConversationRecord) async
}

struct NoopNotifier: MeshNotifier {
    func notify(message: MessageRecord, conversation: ConversationRecord) async {}
}

extension PacketType {
    /// Pacchetti testuali/di controllo che possono attendere in `relay_queue` (Delay-Tolerant Networking).
    var isStoreAndForwardEligible: Bool {
        switch self {
        case .chatText, .deliveryAck, .groupKeyGossip, .proPassGrant, .gameMove: return true
        case .publicLounge, .mediaHeader, .mediaChunk, .UNRECOGNIZED: return false
        }
    }

    var isMedia: Bool {
        self == .mediaHeader || self == .mediaChunk
    }
}
