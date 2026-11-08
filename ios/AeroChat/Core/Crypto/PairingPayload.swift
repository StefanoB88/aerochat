import Foundation

/// Contenuto del QR Code di pairing / catena gruppi.
///
/// Formato URI:
/// `aeromesh://v1?x=<base64url_x25519_32B>&e=<base64url_ed25519_32B>&n=<url_encoded_nick>&g=<optional_group_uuid>&gk=<optional_base64url_group_key>&gt=<optional_url_encoded_group_title>`
///
/// `gt` e' opzionale e retrocompatibile: chi non lo conosce lo ignora e riceve il titolo dal Group Key Gossip.
struct PairingPayload: Sendable, Equatable {

    enum Failure: Error, Equatable {
        case invalidScheme
        case unsupportedVersion
        case missingField(String)
        case invalidField(String)
    }

    let x25519PublicKeyRaw: Data
    let ed25519PublicKeyRaw: Data
    let nickname: String
    let groupID: UUID?
    let groupSecretKey: Data?
    /// Nome del gruppo di viaggio, cosi' chi scansiona lo vede subito (senza attendere il gossip).
    let groupTitle: String?

    /// Lunghezza massima del titolo nel QR (resta leggibile dalla fotocamera).
    static let maxGroupTitleLength = 64

    init(x25519PublicKeyRaw: Data, ed25519PublicKeyRaw: Data, nickname: String, groupID: UUID? = nil, groupSecretKey: Data? = nil,
         groupTitle: String? = nil) {
        self.x25519PublicKeyRaw = x25519PublicKeyRaw
        self.ed25519PublicKeyRaw = ed25519PublicKeyRaw
        self.nickname = nickname
        self.groupID = groupID
        self.groupSecretKey = groupSecretKey
        let trimmedTitle = groupTitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.groupTitle = trimmedTitle.flatMap { $0.isEmpty ? nil : String($0.prefix(Self.maxGroupTitleLength)) }
    }

    /// Payload personale a partire dall'identita' locale.
    init(identity: IdentityKeys, nickname: String, groupID: UUID? = nil, groupSecretKey: Data? = nil, groupTitle: String? = nil) {
        self.init(x25519PublicKeyRaw: identity.x25519PublicKeyRaw,
                  ed25519PublicKeyRaw: identity.ed25519PublicKeyRaw,
                  nickname: nickname,
                  groupID: groupID,
                  groupSecretKey: groupSecretKey,
                  groupTitle: groupTitle)
    }

    /// `node_id` derivato dalla chiave X25519 contenuta nel QR.
    var nodeID: Data { CryptoEngine.nodeID(forX25519PublicKey: x25519PublicKeyRaw) }

    /// `true` se il QR trasporta anche l'invito a un gruppo di viaggio.
    var isGroupInvite: Bool { groupID != nil && groupSecretKey != nil }

    // MARK: - Encoding

    /// Caratteri ammessi senza escape nel nickname (RFC 3986 unreserved): tutto il resto e' percent-encoded,
    /// incluso lo spazio, cosi' il parser Android (`URLDecoder`) e quello iOS ottengono lo stesso risultato.
    private static let nicknameAllowed: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~")
        return set
    }()

    var uriString: String {
        var items: [(String, String)] = [
            ("x", x25519PublicKeyRaw.base64URLEncodedString()),
            ("e", ed25519PublicKeyRaw.base64URLEncodedString()),
            ("n", nickname.addingPercentEncoding(withAllowedCharacters: Self.nicknameAllowed) ?? ""),
        ]
        if let groupID {
            items.append(("g", groupID.uuidString.lowercased()))
        }
        if let groupSecretKey {
            items.append(("gk", groupSecretKey.base64URLEncodedString()))
        }
        if let groupTitle {
            items.append(("gt", groupTitle.addingPercentEncoding(withAllowedCharacters: Self.nicknameAllowed) ?? ""))
        }
        let query = items.map { "\($0.0)=\($0.1)" }.joined(separator: "&")
        return "\(MeshProtocol.qrScheme)://\(MeshProtocol.qrVersion)?\(query)"
    }

    var url: URL? { URL(string: uriString) }

    // MARK: - Decoding

    init(uriString: String) throws {
        let trimmed = uriString.trimmingCharacters(in: .whitespacesAndNewlines)
        let prefix = "\(MeshProtocol.qrScheme)://"
        guard trimmed.lowercased().hasPrefix(prefix) else { throw Failure.invalidScheme }

        let afterScheme = trimmed.dropFirst(prefix.count)
        let parts = afterScheme.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        let version = parts.first.map(String.init) ?? ""
        guard version.lowercased() == MeshProtocol.qrVersion else { throw Failure.unsupportedVersion }
        guard parts.count == 2 else { throw Failure.missingField("query") }

        var fields: [String: String] = [:]
        for pair in parts[1].split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard kv.count == 2 else { continue }
            fields[String(kv[0])] = String(kv[1])
        }

        guard let xRaw = fields["x"] else { throw Failure.missingField("x") }
        guard let x = Data(base64URLEncoded: xRaw), x.count == MeshProtocol.publicKeyLength else { throw Failure.invalidField("x") }
        guard let eRaw = fields["e"] else { throw Failure.missingField("e") }
        guard let e = Data(base64URLEncoded: eRaw), e.count == MeshProtocol.publicKeyLength else { throw Failure.invalidField("e") }
        guard let nRaw = fields["n"] else { throw Failure.missingField("n") }
        // `URLEncoder` su Android codifica lo spazio come "+": lo normalizziamo prima del percent-decoding.
        guard let nickname = nRaw.replacingOccurrences(of: "+", with: " ").removingPercentEncoding,
              !nickname.isEmpty else { throw Failure.invalidField("n") }

        var groupID: UUID?
        if let gRaw = fields["g"], !gRaw.isEmpty {
            guard let parsed = UUID(uuidString: gRaw) else { throw Failure.invalidField("g") }
            groupID = parsed
        }
        var groupSecretKey: Data?
        if let gkRaw = fields["gk"], !gkRaw.isEmpty {
            guard let key = Data(base64URLEncoded: gkRaw), key.count == MeshProtocol.symmetricKeyLength else {
                throw Failure.invalidField("gk")
            }
            groupSecretKey = key
        }
        // Titolo facoltativo: se non decodificabile si ignora (il gossip lo correggera').
        let groupTitle = fields["gt"].flatMap { $0.replacingOccurrences(of: "+", with: " ").removingPercentEncoding }

        self.init(x25519PublicKeyRaw: x, ed25519PublicKeyRaw: e, nickname: nickname, groupID: groupID, groupSecretKey: groupSecretKey,
                  groupTitle: groupTitle)
    }
}
