import Foundation
import CryptoKit

/// Coppia di chiavi di identita' del nodo locale (X25519 per ECDH, Ed25519 per firma).
/// Le chiavi CryptoKit sono `Sendable`, quindi la struct puo' attraversare gli actor.
struct IdentityKeys: Sendable {

    let agreementKey: Curve25519.KeyAgreement.PrivateKey
    let signingKey: Curve25519.Signing.PrivateKey

    init(agreementKey: Curve25519.KeyAgreement.PrivateKey, signingKey: Curve25519.Signing.PrivateKey) {
        self.agreementKey = agreementKey
        self.signingKey = signingKey
    }

    /// Ricostruisce le chiavi dai 32 byte raw salvati nel Keychain.
    init(x25519PrivateRaw: Data, ed25519PrivateRaw: Data) throws {
        agreementKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: x25519PrivateRaw)
        signingKey = try Curve25519.Signing.PrivateKey(rawRepresentation: ed25519PrivateRaw)
    }

    static func generate() -> IdentityKeys {
        IdentityKeys(agreementKey: Curve25519.KeyAgreement.PrivateKey(),
                     signingKey: Curve25519.Signing.PrivateKey())
    }

    /// Chiave pubblica X25519 raw (32 byte) – campo `x` del QR e `sender_x25519_pub`.
    var x25519PublicKeyRaw: Data { agreementKey.publicKey.rawRepresentation }

    /// Chiave pubblica Ed25519 raw (32 byte) – campo `e` del QR e `sender_ed25519_pub`.
    var ed25519PublicKeyRaw: Data { signingKey.publicKey.rawRepresentation }

    /// `node_id` locale: primi 8 byte di `SHA256(x25519_public_key_raw)`.
    var nodeID: Data { CryptoEngine.nodeID(forX25519PublicKey: x25519PublicKeyRaw) }

    /// Chiave simmetrica della chat 1-a-1 con il peer indicato.
    func directChatKey(peerX25519PublicKeyRaw: Data) throws -> SymmetricKey {
        try CryptoEngine.deriveDirectChatKey(privateKey: agreementKey, peerX25519PublicKeyRaw: peerX25519PublicKeyRaw)
    }
}

/// Custode delle chiavi di identita': le genera al primo avvio e le persiste nel Keychain.
actor IdentityStore {

    enum Account {
        static let x25519 = "identity.x25519.private"
        static let ed25519 = "identity.ed25519.private"
    }

    private let keychain: KeychainStore
    private var cached: IdentityKeys?

    init(keychain: KeychainStore = KeychainStore()) {
        self.keychain = keychain
    }

    /// Restituisce le chiavi esistenti o ne genera di nuove (onboarding zero-account).
    func loadOrCreateKeys() throws -> IdentityKeys {
        if let cached { return cached }

        if let x25519 = try keychain.load(account: Account.x25519),
           let ed25519 = try keychain.load(account: Account.ed25519) {
            let keys = try IdentityKeys(x25519PrivateRaw: x25519, ed25519PrivateRaw: ed25519)
            cached = keys
            return keys
        }

        let keys = IdentityKeys.generate()
        try keychain.save(keys.agreementKey.rawRepresentation, account: Account.x25519)
        try keychain.save(keys.signingKey.rawRepresentation, account: Account.ed25519)
        cached = keys
        return keys
    }

    /// `true` se nel Keychain esiste gia' un'identita'.
    func hasStoredIdentity() throws -> Bool {
        try keychain.load(account: Account.x25519) != nil && keychain.load(account: Account.ed25519) != nil
    }

    /// Cancella l'identita' (reset app). Le conversazioni cifrate con la vecchia chiave diventano illeggibili.
    func reset() throws {
        try keychain.delete(account: Account.x25519)
        try keychain.delete(account: Account.ed25519)
        cached = nil
    }
}
