import Foundation
import SwiftProtobuf

// Alias leggibili sui tipi generati dai .proto.
typealias MeshEnvelope = Aeromesh_V1_MeshEnvelope
typealias InnerPayload = Aeromesh_V1_InnerPayload
typealias PacketType = Aeromesh_V1_PacketType
typealias GroupMember = Aeromesh_V1_GroupMember
typealias GroupKeyGossip = Aeromesh_V1_GroupKeyGossip
typealias ProPassGrant = Aeromesh_V1_ProPassGrant
typealias GameMove = Aeromesh_V1_GameMove
typealias GameKind = Aeromesh_V1_GameKind
typealias GameAction = Aeromesh_V1_GameAction
typealias ShotReport = Aeromesh_V1_ShotReport

/// Punto unico di (de)serializzazione Protobuf, cosi' il resto dell'app (e i test)
/// non dipendono direttamente dalle API di SwiftProtobuf.
enum MeshWire {

    static func serialize(_ envelope: MeshEnvelope) throws -> Data {
        try envelope.serializedBytes()
    }

    static func serialize(_ payload: InnerPayload) throws -> Data {
        try payload.serializedBytes()
    }

    static func serialize(_ gossip: GroupKeyGossip) throws -> Data {
        try gossip.serializedBytes()
    }

    static func serialize(_ grant: ProPassGrant) throws -> Data {
        try grant.serializedBytes()
    }

    static func serialize(_ move: GameMove) throws -> Data {
        try move.serializedBytes()
    }

    static func parseEnvelope(_ data: Data) throws -> MeshEnvelope {
        try MeshEnvelope(serializedBytes: data)
    }

    static func parseInnerPayload(_ data: Data) throws -> InnerPayload {
        try InnerPayload(serializedBytes: data)
    }

    static func parseGroupKeyGossip(_ data: Data) throws -> GroupKeyGossip {
        try GroupKeyGossip(serializedBytes: data)
    }

    static func parseProPassGrant(_ data: Data) throws -> ProPassGrant {
        try ProPassGrant(serializedBytes: data)
    }

    static func parseGameMove(_ data: Data) throws -> GameMove {
        try GameMove(serializedBytes: data)
    }
}

extension InnerPayload {

    /// Payload base con le chiavi pubbliche del mittente (necessarie al destinatario per verificare
    /// la firma e, se non ci conosce ancora, per rispondere).
    static func signed(by identity: IdentityKeys, nickname: String) -> InnerPayload {
        var payload = InnerPayload()
        payload.senderNickname = nickname
        payload.senderX25519Pub = identity.x25519PublicKeyRaw
        payload.senderEd25519Pub = identity.ed25519PublicKeyRaw
        return payload
    }

    /// Messaggio testuale 1-a-1, di gruppo o di Lounge.
    static func text(_ text: String, identity: IdentityKeys, nickname: String, replyTo replyToMsgID: Data? = nil) -> InnerPayload {
        var payload = signed(by: identity, nickname: nickname)
        payload.text = text
        if let replyToMsgID {
            payload.replyToMsgID = replyToMsgID.hexString
        }
        return payload
    }

    /// `DELIVERY_ACK`: `reply_to_msg_id` contiene il `msg_id` (hex) del messaggio consegnato.
    static func deliveryAck(for msgID: Data, identity: IdentityKeys, nickname: String) -> InnerPayload {
        var payload = signed(by: identity, nickname: nickname)
        payload.replyToMsgID = msgID.hexString
        return payload
    }

    /// Payload con `extra_data` (gossip, pro pass, mosse di gioco).
    static func extra(_ data: Data, identity: IdentityKeys, nickname: String) -> InnerPayload {
        var payload = signed(by: identity, nickname: nickname)
        payload.extraData = data
        return payload
    }

    /// `msg_id` referenziato da `reply_to_msg_id`, se valido.
    var replyToMessageID: Data? {
        guard !replyToMsgID.isEmpty, let data = Data(hexString: replyToMsgID), data.count == MeshProtocol.msgIDLength else { return nil }
        return data
    }

    /// `node_id` derivato dalla chiave X25519 dichiarata nel payload.
    var declaredSenderNodeID: Data? {
        guard senderX25519Pub.count == MeshProtocol.publicKeyLength else { return nil }
        return CryptoEngine.nodeID(forX25519PublicKey: senderX25519Pub)
    }
}

extension GroupMember {
    init(peer: PeerRecord) {
        self.init()
        nodeID = peer.nodeID
        x25519Pub = peer.x25519Pub
        ed25519Pub = peer.ed25519Pub
        nickname = peer.nickname
    }

    init(identity: IdentityKeys, nickname: String) {
        self.init()
        nodeID = identity.nodeID
        x25519Pub = identity.x25519PublicKeyRaw
        ed25519Pub = identity.ed25519PublicKeyRaw
        self.nickname = nickname
    }

    /// Un membro e' valido solo se il suo `node_id` corrisponde alla chiave X25519 dichiarata.
    var isConsistent: Bool {
        nodeID.count == MeshProtocol.nodeIDLength
            && x25519Pub.count == MeshProtocol.publicKeyLength
            && ed25519Pub.count == MeshProtocol.publicKeyLength
            && CryptoEngine.nodeID(forX25519PublicKey: x25519Pub) == nodeID
    }

    var asPeerRecord: PeerRecord {
        PeerRecord(nodeID: nodeID, x25519Pub: x25519Pub, ed25519Pub: ed25519Pub, nickname: nickname.isEmpty ? String(localized: "Passeggero") : nickname)
    }
}
