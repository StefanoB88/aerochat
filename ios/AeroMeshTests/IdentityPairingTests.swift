import Testing
import Foundation
@testable import AeroChat

@Suite("Identita' Keychain e QR Code di pairing")
struct IdentityPairingTests {

    @Test("KeychainStore: save / load / overwrite / delete")
    func keychainRoundTrip() throws {
        let store = KeychainStore(service: "com.aeromesh.tests.\(UUID().uuidString)")
        let account = "unit-test"
        #expect(try store.load(account: account) == nil)

        try store.save(Data([1, 2, 3]), account: account)
        #expect(try store.load(account: account) == Data([1, 2, 3]))

        try store.save(Data([9, 9]), account: account)
        #expect(try store.load(account: account) == Data([9, 9]))

        try store.delete(account: account)
        #expect(try store.load(account: account) == nil)
        try store.delete(account: account) // idempotente
    }

    @Test("IdentityStore: le chiavi persistono tra istanze e il reset le rigenera")
    func identityStorePersistsAcrossInstances() async throws {
        let keychain = KeychainStore(service: "com.aeromesh.tests.identity.\(UUID().uuidString)")
        let first = IdentityStore(keychain: keychain)
        #expect(try await first.hasStoredIdentity() == false)
        let keys = try await first.loadOrCreateKeys()
        #expect(keys.nodeID.count == 8)
        #expect(keys.x25519PublicKeyRaw.count == 32)
        #expect(keys.ed25519PublicKeyRaw.count == 32)

        let second = IdentityStore(keychain: keychain)
        let reloaded = try await second.loadOrCreateKeys()
        #expect(reloaded.nodeID == keys.nodeID)
        #expect(reloaded.ed25519PublicKeyRaw == keys.ed25519PublicKeyRaw)

        try await second.reset()
        let regenerated = try await second.loadOrCreateKeys()
        #expect(regenerated.nodeID != keys.nodeID)
        try await second.reset()
    }

    @Test("QR URI: roundtrip completo con gruppo e chiave")
    func pairingURIRoundTrip() throws {
        let identity = IdentityKeys.generate()
        let groupID = UUID()
        let groupKey = CryptoEngine.randomGroupSecretKey()
        let payload = PairingPayload(identity: identity, nickname: "Stefano Rossi ✈", groupID: groupID, groupSecretKey: groupKey)

        let uri = payload.uriString
        #expect(uri.hasPrefix("aeromesh://v1?x="))
        #expect(!uri.contains(" "))
        #expect(!uri.contains("+"))
        // base64url senza padding: '=' compare solo come separatore chiave=valore, mai dentro i valori.
        let query = try #require(uri.split(separator: "?", maxSplits: 1).last)
        for pair in query.split(separator: "&") {
            let value = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).last ?? ""
            #expect(!value.contains("="), "padding '=' inatteso in \(pair)")
        }

        let parsed = try PairingPayload(uriString: uri)
        #expect(parsed == payload)
        #expect(parsed.nodeID == identity.nodeID)
        #expect(parsed.isGroupInvite)
        #expect(parsed.url != nil)
    }

    @Test("QR URI: il nome del gruppo viaggia nel parametro opzionale 'gt' ed e' retrocompatibile")
    func pairingURICarriesGroupTitle() throws {
        let identity = IdentityKeys.generate()
        let payload = PairingPayload(identity: identity, nickname: "Bob", groupID: UUID(),
                                     groupSecretKey: CryptoEngine.randomGroupSecretKey(), groupTitle: "  Volo FR 1234 & co ✈ ")
        #expect(payload.groupTitle == "Volo FR 1234 & co ✈")
        let uri = payload.uriString
        #expect(uri.contains("&gt=Volo%20FR%201234%20%26%20co%20"))
        #expect(try PairingPayload(uriString: uri) == payload)

        // Android con `URLEncoder` ("+" per lo spazio) e QR legacy senza 'gt'.
        let android = try PairingPayload(uriString: uri.replacingOccurrences(of: "Volo%20FR%201234", with: "Volo+FR+1234"))
        #expect(android.groupTitle == "Volo FR 1234 & co ✈")
        let legacy = try PairingPayload(uriString: String(uri[..<uri.range(of: "&gt=")!.lowerBound]))
        #expect(legacy.groupTitle == nil)
        #expect(legacy.isGroupInvite)
    }

    @Test("QR URI: payload personale senza gruppo e decodifica dello spazio codificato come '+' da Android")
    func pairingURIAndroidCompatibility() throws {
        let identity = IdentityKeys.generate()
        let x = identity.x25519PublicKeyRaw.base64URLEncodedString()
        let e = identity.ed25519PublicKeyRaw.base64URLEncodedString()
        let androidURI = "aeromesh://v1?x=\(x)&e=\(e)&n=Mario+Rossi%21"
        let parsed = try PairingPayload(uriString: androidURI)
        #expect(parsed.nickname == "Mario Rossi!")
        #expect(parsed.groupID == nil)
        #expect(parsed.groupSecretKey == nil)
        #expect(parsed.isGroupInvite == false)
    }

    @Test("QR URI: schema, versione e lunghezze chiave vengono validati")
    func pairingURIValidation() throws {
        let identity = IdentityKeys.generate()
        let good = PairingPayload(identity: identity, nickname: "ok").uriString
        #expect(throws: PairingPayload.Failure.invalidScheme) { try PairingPayload(uriString: "https://example.com") }
        #expect(throws: PairingPayload.Failure.unsupportedVersion) { try PairingPayload(uriString: good.replacingOccurrences(of: "v1?", with: "v9?")) }
        let shortKey = Data(repeating: 1, count: 16).base64URLEncodedString()
        #expect(throws: PairingPayload.Failure.invalidField("x")) {
            try PairingPayload(uriString: "aeromesh://v1?x=\(shortKey)&e=\(identity.ed25519PublicKeyRaw.base64URLEncodedString())&n=a")
        }
        #expect(throws: PairingPayload.Failure.missingField("n")) {
            try PairingPayload(uriString: "aeromesh://v1?x=\(identity.x25519PublicKeyRaw.base64URLEncodedString())&e=\(identity.ed25519PublicKeyRaw.base64URLEncodedString())")
        }
        #expect(throws: PairingPayload.Failure.invalidField("gk")) {
            try PairingPayload(uriString: good + "&g=\(UUID().uuidString)&gk=\(shortKey)")
        }
    }

    @Test("Byte helpers: hex, base64url e interi big/little endian")
    func byteHelpers() throws {
        let data = Data([0x00, 0xAB, 0xFF])
        #expect(data.hexString == "00abff")
        #expect(Data(hexString: "00ABff") == data)
        #expect(Data(hexString: "abc") == nil)
        #expect(Data(base64URLEncoded: data.base64URLEncodedString()) == data)
        #expect(Data([0xFB, 0xFF]).base64URLEncodedString() == "-_8")
        #expect(UInt32(0x01020304).bigEndianBytes == Data([1, 2, 3, 4]))
        #expect(UInt16(0x1234).littleEndianBytes == Data([0x34, 0x12]))
        #expect(UInt16(littleEndianBytes: Data([0x34, 0x12])) == 0x1234)
        #expect(Int64(bigEndianBytes: Data([0, 0, 0, 0, 0, 0, 1, 44])) == 300)
        #expect(UInt32(bigEndianBytes: Data([1, 2, 3])) == nil)
    }
}
