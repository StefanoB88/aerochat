import Foundation
import GRDB

/// Contatto conosciuto (scansionato via QR o appreso dal Group Key Gossip).
struct PeerRecord: Codable, Hashable, Sendable, FetchableRecord, PersistableRecord {

    static let databaseTableName = "peers"

    var nodeID: Data
    var x25519Pub: Data
    var ed25519Pub: Data
    var nickname: String
    /// Compagno di viaggio: verso di lui manteniamo la connessione GATT persistente in background.
    var isTrusted: Bool
    var isBlocked: Bool
    var lastSeenAtMs: Int64?
    var lastHopCount: Int?
    var lastRSSI: Int?
    var createdAtMs: Int64
    /// UUID CoreBluetooth del peripheral (valido solo su questo iPhone) per la riconnessione in background.
    var bleIdentifier: String?
    /// Profilo pubblico ricevuto con `PROFILE_UPDATE` (colore avatar e foto WebP).
    var avatarColorHex: String?
    var avatarImage: Data?
    var profileUpdatedAtMs: Int64?

    enum CodingKeys: String, CodingKey {
        case nodeID = "node_id"
        case x25519Pub = "x25519_pub"
        case ed25519Pub = "ed25519_pub"
        case nickname
        case isTrusted = "is_trusted"
        case isBlocked = "is_blocked"
        case lastSeenAtMs = "last_seen_at_ms"
        case lastHopCount = "last_hop_count"
        case lastRSSI = "last_rssi"
        case createdAtMs = "created_at_ms"
        case bleIdentifier = "ble_identifier"
        case avatarColorHex = "avatar_color_hex"
        case avatarImage = "avatar_image"
        case profileUpdatedAtMs = "profile_updated_at_ms"
    }

    enum Columns {
        static let bleIdentifier = Column(CodingKeys.bleIdentifier)
        static let nodeID = Column(CodingKeys.nodeID)
        static let x25519Pub = Column(CodingKeys.x25519Pub)
        static let nickname = Column(CodingKeys.nickname)
        static let isTrusted = Column(CodingKeys.isTrusted)
        static let isBlocked = Column(CodingKeys.isBlocked)
        static let lastSeenAtMs = Column(CodingKeys.lastSeenAtMs)
        static let lastHopCount = Column(CodingKeys.lastHopCount)
        static let lastRSSI = Column(CodingKeys.lastRSSI)
        static let avatarColorHex = Column(CodingKeys.avatarColorHex)
        static let avatarImage = Column(CodingKeys.avatarImage)
        static let profileUpdatedAtMs = Column(CodingKeys.profileUpdatedAtMs)
    }

    init(nodeID: Data, x25519Pub: Data, ed25519Pub: Data, nickname: String,
         isTrusted: Bool = false, isBlocked: Bool = false,
         lastSeenAtMs: Int64? = nil, lastHopCount: Int? = nil, lastRSSI: Int? = nil,
         createdAt: Date = Date(), bleIdentifier: String? = nil) {
        self.nodeID = nodeID
        self.x25519Pub = x25519Pub
        self.ed25519Pub = ed25519Pub
        self.nickname = nickname
        self.isTrusted = isTrusted
        self.isBlocked = isBlocked
        self.lastSeenAtMs = lastSeenAtMs
        self.lastHopCount = lastHopCount
        self.lastRSSI = lastRSSI
        self.createdAtMs = createdAt.unixMilliseconds
        self.bleIdentifier = bleIdentifier
    }

    /// Peer da un QR di pairing: i compagni scansionati di persona sono fidati per definizione.
    init(pairing: PairingPayload, isTrusted: Bool = true, createdAt: Date = Date()) {
        self.init(nodeID: pairing.nodeID,
                  x25519Pub: pairing.x25519PublicKeyRaw,
                  ed25519Pub: pairing.ed25519PublicKeyRaw,
                  nickname: pairing.nickname,
                  isTrusted: isTrusted,
                  createdAt: createdAt)
    }
}
