import Foundation
import CryptoKit
import Security

/// Payload della transazione StoreKit 2 (JWS firmato da Apple) – solo i campi che ci servono.
struct JWSTransactionPayload: Codable, Sendable, Equatable {
    let transactionId: String
    let originalTransactionId: String?
    let productId: String
    let bundleId: String?
    /// Millisecondi Unix.
    let purchaseDate: Int64
    let expiresDate: Int64?
    let revocationDate: Int64?
    let type: String?
    let environment: String?
}

/// Fornisce la chiave pubblica del firmatario dopo aver validato la catena `x5c`.
protocol JWSTrustProvider: Sendable {
    func verifiedSigningKey(x5c: [Data]) throws -> P256.Signing.PublicKey
}

/// Verifica **offline** della ricevuta JWS di StoreKit 2: catena di certificati `x5c` valutata dal
/// trust store di sistema (radice Apple), pin opzionale sulla root, firma ES256 su `header.payload`.
struct JWSTransactionVerifier: Sendable {

    enum Failure: Error, Equatable {
        case malformed
        case unsupportedAlgorithm(String)
        case missingCertificates
        case untrustedChain(String)
        case rootNotPinned(String)
        case invalidSignature
        case wrongBundle
        case revoked
    }

    struct Header: Codable {
        let alg: String
        let x5c: [String]?
    }

    /// SHA-256 di "Apple Root CA - G3" (DER). Pin aggiuntivo oltre alla valutazione del trust store.
    static let appleRootCAG3Fingerprint = "63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179"

    var expectedBundleID: String?
    var trustProvider: any JWSTrustProvider

    init(expectedBundleID: String? = Bundle.main.bundleIdentifier,
         trustProvider: any JWSTrustProvider = AppleChainTrustProvider()) {
        self.expectedBundleID = expectedBundleID
        self.trustProvider = trustProvider
    }

    /// Verifica firma e catena; ritorna il payload decodificato.
    func verify(_ jws: String) throws -> JWSTransactionPayload {
        let parts = jws.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let headerData = Data(base64URLEncoded: String(parts[0])),
              let payloadData = Data(base64URLEncoded: String(parts[1])),
              let signature = Data(base64URLEncoded: String(parts[2])) else { throw Failure.malformed }
        let header = try decode(Header.self, from: headerData)
        guard header.alg == "ES256" else { throw Failure.unsupportedAlgorithm(header.alg) }
        guard let x5c = header.x5c, !x5c.isEmpty else { throw Failure.missingCertificates }
        let certificates = try x5c.map { encoded -> Data in
            guard let data = Data(base64Encoded: encoded) ?? Data(base64URLEncoded: encoded) else { throw Failure.malformed }
            return data
        }

        let key = try trustProvider.verifiedSigningKey(x5c: certificates)
        let signedMessage = Data("\(parts[0]).\(parts[1])".utf8)
        guard signature.count == 64,
              let ecdsa = try? P256.Signing.ECDSASignature(rawRepresentation: signature),
              key.isValidSignature(ecdsa, for: signedMessage) else { throw Failure.invalidSignature }

        let payload = try decode(JWSTransactionPayload.self, from: payloadData)
        if let expectedBundleID, let bundle = payload.bundleId, bundle != expectedBundleID { throw Failure.wrongBundle }
        if payload.revocationDate != nil { throw Failure.revoked }
        return payload
    }

    /// Decodifica il payload senza verificarlo (diagnostica / UI "acquistato il ...").
    static func unverifiedPayload(_ jws: String) throws -> JWSTransactionPayload {
        let parts = jws.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, let payloadData = Data(base64URLEncoded: String(parts[1])) else { throw Failure.malformed }
        return try JSONDecoder().decode(JWSTransactionPayload.self, from: payloadData)
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw Failure.malformed
        }
    }
}

/// Valuta la catena `x5c` con `SecTrust` (radice nel trust store iOS, funziona offline) e, se richiesto,
/// verifica che la root coincida con il pin Apple Root CA - G3.
struct AppleChainTrustProvider: JWSTrustProvider {

    var pinnedRootFingerprints: Set<String> = [JWSTransactionVerifier.appleRootCAG3Fingerprint]
    var requirePinnedRoot = true

    func verifiedSigningKey(x5c: [Data]) throws -> P256.Signing.PublicKey {
        let certificates = x5c.compactMap { SecCertificateCreateWithData(nil, $0 as CFData) }
        guard certificates.count == x5c.count, let leaf = certificates.first else {
            throw JWSTransactionVerifier.Failure.missingCertificates
        }

        var trust: SecTrust?
        let status = SecTrustCreateWithCertificates(certificates as CFArray, SecPolicyCreateBasicX509(), &trust)
        guard status == errSecSuccess, let trust else {
            throw JWSTransactionVerifier.Failure.untrustedChain("SecTrustCreate \(status)")
        }
        SecTrustSetNetworkFetchAllowed(trust, false)
        var error: CFError?
        guard SecTrustEvaluateWithError(trust, &error) else {
            throw JWSTransactionVerifier.Failure.untrustedChain((error as Error?)?.localizedDescription ?? "evaluate")
        }

        if requirePinnedRoot {
            let chain = (SecTrustCopyCertificateChain(trust) as? [SecCertificate]) ?? certificates
            guard let root = chain.last else { throw JWSTransactionVerifier.Failure.rootNotPinned("catena vuota") }
            let fingerprint = Data(SHA256.hash(data: SecCertificateCopyData(root) as Data)).hexString
            guard pinnedRootFingerprints.contains(fingerprint) else {
                throw JWSTransactionVerifier.Failure.rootNotPinned(fingerprint)
            }
        }

        guard let secKey = SecCertificateCopyKey(leaf),
              let external = SecKeyCopyExternalRepresentation(secKey, nil) as Data?,
              let key = try? P256.Signing.PublicKey(x963Representation: external) else {
            throw JWSTransactionVerifier.Failure.invalidSignature
        }
        return key
    }
}
