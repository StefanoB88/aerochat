import Foundation
import GRDB

/// Livello Pro cache-ato localmente (verificato offline tramite ricevuta JWS di StoreKit 2).
enum ProTier: String, Codable, Sendable, DatabaseValueConvertible {
    case free
    /// Pass Volo Singolo (24 ore).
    case pass24h = "pass_24h"
    case pass72h = "pass_72h"
    case lifetime

    /// Pass a tempo (scade), a differenza del lifetime.
    var isTimeLimited: Bool { self == .pass24h || self == .pass72h }
}

/// Riga unica (`id = 1`) con l'identita' locale.
struct IdentityRecord: Codable, Hashable, Sendable, FetchableRecord, PersistableRecord {

    static let databaseTableName = "identity"
    static let singletonID: Int64 = 1

    var id: Int64 = IdentityRecord.singletonID
    var nodeID: Data
    var x25519Pub: Data
    var ed25519Pub: Data
    var nickname: String
    var avatarColorHex: String
    var proTier: ProTier
    var proValidUntilMs: Int64?
    var proReceiptJWS: String?
    var createdAtMs: Int64
    /// Foto profilo WebP 128x128 condivisa con i contatti (nil = solo iniziale su colore).
    var avatarImage: Data?
    /// Versione del profilo pubblico (nickname, colore, foto) annunciata con `PROFILE_UPDATE`.
    var profileUpdatedAtMs: Int64?

    enum CodingKeys: String, CodingKey {
        case id
        case nodeID = "node_id"
        case x25519Pub = "x25519_pub"
        case ed25519Pub = "ed25519_pub"
        case nickname
        case avatarColorHex = "avatar_color_hex"
        case proTier = "pro_tier"
        case proValidUntilMs = "pro_valid_until_ms"
        case proReceiptJWS = "pro_receipt_jws"
        case createdAtMs = "created_at_ms"
        case avatarImage = "avatar_image"
        case profileUpdatedAtMs = "profile_updated_at_ms"
    }

    enum Columns {
        static let id = Column(CodingKeys.id)
        static let nickname = Column(CodingKeys.nickname)
        static let avatarColorHex = Column(CodingKeys.avatarColorHex)
        static let avatarImage = Column(CodingKeys.avatarImage)
        static let profileUpdatedAtMs = Column(CodingKeys.profileUpdatedAtMs)
        static let proTier = Column(CodingKeys.proTier)
        static let proValidUntilMs = Column(CodingKeys.proValidUntilMs)
    }

    /// Crea il record dall'identita' crittografica e dal nickname scelto nell'onboarding.
    init(identity: IdentityKeys, nickname: String, avatarColorHex: String, createdAt: Date = Date()) {
        self.nodeID = identity.nodeID
        self.x25519Pub = identity.x25519PublicKeyRaw
        self.ed25519Pub = identity.ed25519PublicKeyRaw
        self.nickname = nickname
        self.avatarColorHex = avatarColorHex
        self.proTier = .free
        self.proValidUntilMs = nil
        self.proReceiptJWS = nil
        self.createdAtMs = createdAt.unixMilliseconds
        self.avatarImage = nil
        self.profileUpdatedAtMs = createdAt.unixMilliseconds
    }

    /// `true` se l'utente e' Pro in questo istante (lifetime o pass non scaduto).
    func isPro(now: Date = Date()) -> Bool {
        switch proTier {
        case .lifetime: return true
        case .pass24h, .pass72h: return (proValidUntilMs ?? 0) > now.unixMilliseconds
        case .free: return false
        }
    }
}
