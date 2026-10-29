import Testing
import Foundation
import CryptoKit
@testable import MyApp

@Suite("Monetizzazione – JWS offline, stato Pro, Flight Group Pass, feature gate")
struct EntitlementTests {

    /// Trust provider di test: la "catena" e' una sola chiave P-256 nota.
    struct StubTrust: JWSTrustProvider {
        let key: P256.Signing.PublicKey
        func verifiedSigningKey(x5c: [Data]) throws -> P256.Signing.PublicKey {
            guard !x5c.isEmpty else { throw JWSTransactionVerifier.Failure.missingCertificates }
            return key
        }
    }

    private func makeJWS(payload: [String: Any], signer: P256.Signing.PrivateKey, alg: String = "ES256") throws -> String {
        let header: [String: Any] = ["alg": alg, "x5c": [Data("fake-cert".utf8).base64EncodedString()]]
        let headerPart = try JSONSerialization.data(withJSONObject: header).base64URLEncodedString()
        let payloadPart = try JSONSerialization.data(withJSONObject: payload).base64URLEncodedString()
        let signature = try signer.signature(for: Data("\(headerPart).\(payloadPart)".utf8)).rawRepresentation
        return "\(headerPart).\(payloadPart).\(signature.base64URLEncodedString())"
    }

    @Test("JWS: firma ES256 verificata offline, payload decodificato, manomissioni e alg errati rifiutati")
    func jwsVerification() throws {
        let signer = P256.Signing.PrivateKey()
        let verifier = JWSTransactionVerifier(expectedBundleID: "com.aeromesh.app", trustProvider: StubTrust(key: signer.publicKey))
        let purchase = Date().unixMilliseconds - 3_600_000
        let payload: [String: Any] = [
            "transactionId": "2000000123", "originalTransactionId": "2000000123",
            "productId": MeshProtocol.ProductID.pass72h, "bundleId": "com.aeromesh.app",
            "purchaseDate": purchase, "type": "Consumable", "environment": "Production",
        ]
        let jws = try makeJWS(payload: payload, signer: signer)
        let verified = try verifier.verify(jws)
        #expect(verified.productId == MeshProtocol.ProductID.pass72h)
        #expect(verified.purchaseDate == purchase)

        let state = EntitlementManager.state(from: verified, now: Date(), source: .cachedReceipt)
        #expect(state.tier == .pass72h)
        #expect(state.isPro())
        #expect(state.validUntilMs == purchase + 72 * 3_600 * 1_000)
        #expect(EntitlementManager.state(from: verified, now: Date().addingTimeInterval(73 * 3_600), source: .cachedReceipt) == .free)

        // Firma di un'altra chiave.
        let forged = try makeJWS(payload: payload, signer: P256.Signing.PrivateKey())
        #expect(throws: JWSTransactionVerifier.Failure.invalidSignature) { try verifier.verify(forged) }
        // Payload alterato dopo la firma.
        var parts = jws.split(separator: ".").map(String.init)
        parts[1] = try JSONSerialization.data(withJSONObject: ["productId": MeshProtocol.ProductID.lifetime, "purchaseDate": purchase, "transactionId": "x"]).base64URLEncodedString()
        #expect(throws: JWSTransactionVerifier.Failure.invalidSignature) { try verifier.verify(parts.joined(separator: ".")) }
        // Algoritmo non supportato e bundle sbagliato.
        #expect(throws: JWSTransactionVerifier.Failure.unsupportedAlgorithm("HS256")) { try verifier.verify(try makeJWS(payload: payload, signer: signer, alg: "HS256")) }
        var wrongBundle = payload
        wrongBundle["bundleId"] = "com.other.app"
        #expect(throws: JWSTransactionVerifier.Failure.wrongBundle) { try verifier.verify(try makeJWS(payload: wrongBundle, signer: signer)) }
        #expect(throws: JWSTransactionVerifier.Failure.malformed) { try verifier.verify("non.un.jws.valido") }
        #expect(try JWSTransactionVerifier.unverifiedPayload(jws).transactionId == "2000000123")
    }

    @Test("Catena reale: il provider Apple rifiuta certificati finti senza crashare")
    func appleTrustProviderRejectsGarbage() {
        let provider = AppleChainTrustProvider()
        #expect(throws: JWSTransactionVerifier.Failure.missingCertificates) { try provider.verifiedSigningKey(x5c: [Data("garbage".utf8)]) }
        #expect(JWSTransactionVerifier.appleRootCAG3Fingerprint.count == 64)
    }

    @Test("EntitlementManager: stato libero di default, ricevuta cache-ata ripristinata al refresh, eventi")
    func entitlementManagerRefreshFromCachedReceipt() async throws {
        let database = try AppDatabase.makeTemporary()
        let identity = IdentityKeys.generate()
        try await database.identities.save(IdentityRecord(identity: identity, nickname: "Pro", avatarColorHex: "00E5FF"))
        let signer = P256.Signing.PrivateKey()
        let verifier = JWSTransactionVerifier(expectedBundleID: nil, trustProvider: StubTrust(key: signer.publicKey))
        let manager = EntitlementManager(database: database, verifier: verifier)
        await manager.refresh()
        #expect(await manager.state == .free)

        let jws = try makeJWS(payload: ["transactionId": "1", "productId": MeshProtocol.ProductID.lifetime,
                                        "purchaseDate": Date().unixMilliseconds], signer: signer)
        try await database.identities.updateProStatus(tier: .lifetime, validUntilMs: nil, receiptJWS: jws)
        let events = await manager.events()
        var iterator = events.makeAsyncIterator()
        _ = await iterator.next() // stato corrente (free)
        await manager.refresh()
        let next = await iterator.next()
        #expect(next?.tier == .lifetime)
        #expect(next?.source == .cachedReceipt)
        #expect(await manager.state.isPro())
    }

    @Test("ProPassGrant: firma su group_uuid||valid_until BE, durata massima 72h, scadenza")
    func proPassGrantSignature() throws {
        let issuer = IdentityKeys.generate()
        let groupID = UUID()
        let now = Date()
        let validUntil = now.addingTimeInterval(72 * 3_600).unixMilliseconds
        let grant = try ProPassService.makeGrant(groupID: groupID, validUntilMs: validUntil,
                                                 productID: MeshProtocol.ProductID.pass72h, identity: issuer)
        #expect(grant.signature.count == 64)
        #expect(ProPassService.signedMessage(groupUUID: grant.groupUuid, validUntilMs: validUntil).count == 24)
        #expect(ProPassService.verify(grant, now: now))
        #expect(!ProPassService.verify(grant, now: now.addingTimeInterval(73 * 3_600)), "scaduto")

        var tooLong = grant
        tooLong.validUntilMs = now.addingTimeInterval(10 * 24 * 3_600).unixMilliseconds
        tooLong.signature = try CryptoEngine.sign(message: ProPassService.signedMessage(groupUUID: grant.groupUuid, validUntilMs: tooLong.validUntilMs), with: issuer.signingKey)
        #expect(!ProPassService.verify(tooLong, now: now), "oltre 72h + tolleranza")

        var forged = grant
        forged.issuerEd25519Pub = IdentityKeys.generate().ed25519PublicKeyRaw
        #expect(!ProPassService.verify(forged, now: now))

        let roundTrip = try MeshWire.parseProPassGrant(try MeshWire.serialize(grant))
        #expect(roundTrip == grant)
    }

    @Test("1 paga, il gruppo usa: il grant del membro Pro sblocca la conversazione sui dispositivi gratuiti")
    func proPassPropagatesOverMesh() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)

        // Alice e' Pro (stato iniettato come dopo un acquisto verificato).
        let aliceEntitlements = EntitlementManager(database: alice.database)
        await aliceEntitlements.applyVerifiedState(EntitlementState(tier: .lifetime, validUntilMs: nil, source: .storeKit), receiptJWS: nil)
        let bobEntitlements = EntitlementManager(database: bob.database)
        let aliceService = ProPassService(router: alice.router, database: alice.database, entitlements: aliceEntitlements, identity: alice.identity, nickname: "Alice")
        let bobService = ProPassService(router: bob.router, database: bob.database, entitlements: bobEntitlements, identity: bob.identity, nickname: "Bob")
        await aliceService.start()
        await bobService.start()
        let bobEvents = await bobService.events()

        let group = try await alice.router.createGroup(title: "Volo")
        let invite = try #require(await alice.router.groupInvitePayload(for: group))
        let bobGroup = try await bob.router.pair(with: invite)
        #expect(bobGroup.isProUnlocked() == false)

        #expect(try await aliceService.broadcastGrantIfEntitled(for: group))
        #expect(try await alice.database.conversations.fetch(id: group.id)?.isProUnlocked() == true)

        var iterator = bobEvents.makeAsyncIterator()
        let event = await iterator.next()
        guard case .unlocked(let conversationID, let until, let issuer)? = event else { Issue.record("atteso unlocked"); return }
        #expect(conversationID == bobGroup.id)
        #expect(issuer == alice.nodeID)
        #expect(until > Date().unixMilliseconds)
        #expect(try await bob.database.conversations.fetch(id: bobGroup.id)?.isProUnlocked() == true)

        // Bob gratuito: non emette grant.
        #expect(try await bobService.broadcastGrantIfEntitled(for: bobGroup) == false)
    }

    @Test("FeatureGate: regole free/pro, foto solo in diretta, Lounge solo testo")
    func featureGateRules() async throws {
        let peer = IdentityKeys.generate()
        let peerRecord = PeerRecord(nodeID: peer.nodeID, x25519Pub: peer.x25519PublicKeyRaw, ed25519Pub: peer.ed25519PublicKeyRaw, nickname: "P")
        var direct = ConversationRecord.direct(with: peerRecord)
        let lounge = ConversationRecord.cabinLounge()

        #expect(FeatureGate.decision(for: .photo, userIsPro: false, conversation: direct, reachability: .direct(rssi: -50)) == .requiresPass)
        #expect(FeatureGate.decision(for: .photo, userIsPro: true, conversation: direct, reachability: .direct(rssi: -50)) == .allowed)
        #expect(FeatureGate.decision(for: .photo, userIsPro: true, conversation: direct, reachability: .viaBridge(hops: 2)) == .photosNeedDirectLink)
        #expect(FeatureGate.decision(for: .voiceNote, userIsPro: true, conversation: direct, reachability: .viaBridge(hops: 3)) == .allowed)
        #expect(FeatureGate.decision(for: .voiceNote, userIsPro: true, conversation: direct, reachability: .outOfRange) == .peerOutOfRange)
        #expect(FeatureGate.decision(for: .game, userIsPro: true, conversation: direct, reachability: .outOfRange) == .allowed)
        #expect(FeatureGate.decision(for: .photo, userIsPro: true, conversation: lounge, reachability: .direct(rssi: nil)) == .loungeTextOnly)

        direct.proUnlockedUntilMs = Date().addingTimeInterval(3_600).unixMilliseconds
        #expect(FeatureGate.decision(for: .game, userIsPro: false, conversation: direct, reachability: .direct(rssi: nil)) == .allowed)
        #expect(FeatureGate.decision(for: .groupChat, userIsPro: false, conversation: direct, reachability: .direct(rssi: nil)).isAllowed)
    }
}
