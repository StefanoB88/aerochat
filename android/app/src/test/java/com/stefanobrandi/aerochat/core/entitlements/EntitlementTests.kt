package com.stefanobrandi.aerochat.core.entitlements

import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.mesh.FakeMeshNetwork
import com.stefanobrandi.aerochat.core.mesh.MeshTestCase
import com.stefanobrandi.aerochat.core.mesh.Reachability
import com.stefanobrandi.aerochat.core.mesh.TestNode
import com.stefanobrandi.aerochat.core.mesh.eventually
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.base64EncodedString
import com.stefanobrandi.aerochat.core.protocol.base64URLEncodedString
import com.stefanobrandi.aerochat.core.protocol.hexToByteArray
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.IdentityRecord
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.data.records.ProTier
import com.google.protobuf.ByteString
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gateway Play finto: acquisti in memoria, registra acknowledge / consume. */
class FakeBillingGateway : BillingGateway {
    var available = true
    var products = listOf(
        StoreProduct(MeshProtocol.ProductID.lifetime, "Lifetime", "Pro per sempre", "€ 14,99", 14_990_000, "EUR"),
        StoreProduct(MeshProtocol.ProductID.pass72h, "Pass 72h", "Pro per 72 ore", "€ 2,99", 2_990_000, "EUR"),
    )
    val owned = mutableListOf<BillingPurchase>()
    var nextOutcome: ((String) -> BillingPurchaseOutcome)? = null
    val acknowledged = mutableListOf<String>()
    val consumed = mutableListOf<String>()
    val updates = MutableSharedFlow<List<BillingPurchase>>(extraBufferCapacity = 16)

    override suspend fun connect(): Boolean = available
    override fun disconnect() {}
    override fun purchaseUpdates(): Flow<List<BillingPurchase>> = updates

    override suspend fun queryProducts(productIDs: List<String>): List<StoreProduct> {
        if (!available) throw EntitlementManager.Failure.BillingError(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "offline")
        return products.filter { it.id in productIDs }
    }

    override suspend fun queryPurchases(): List<BillingPurchase> {
        if (!available) throw EntitlementManager.Failure.BillingError(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "offline")
        return owned.toList()
    }

    override suspend fun launchPurchase(productID: String, activity: Activity): BillingPurchaseOutcome {
        nextOutcome?.let { return it(productID) }
        val purchase = purchase(productID)
        owned += purchase
        return BillingPurchaseOutcome.Success(listOf(purchase))
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        acknowledged += purchaseToken
        val index = owned.indexOfFirst { it.purchaseToken == purchaseToken }
        if (index >= 0) owned[index] = owned[index].copy(isAcknowledged = true)
        return true
    }

    override suspend fun consume(purchaseToken: String): Boolean {
        consumed += purchaseToken
        owned.removeAll { it.purchaseToken == purchaseToken }
        return true
    }

    companion object {
        fun purchase(
            productID: String,
            purchaseTime: Long = System.currentTimeMillis(),
            state: BillingPurchase.State = BillingPurchase.State.purchased,
            token: String = "token-" + UUID.randomUUID(),
            signature: String = "",
        ): BillingPurchase {
            val json = JSONObject()
                .put("orderId", "GPA.1234")
                .put("packageName", "com.stefanobrandi.aerochat")
                .put("productId", productID)
                .put("purchaseTime", purchaseTime)
                .put("purchaseState", 0)
                .put("purchaseToken", token)
                .put("acknowledged", false)
                .toString()
            return BillingPurchase(listOf(productID), token, purchaseTime, state, false, "GPA.1234", json, signature)
        }
    }
}

/** Port di `EntitlementTests.swift`: JWS offline, stato Pro, Flight Group Pass, feature gate (+ Play Billing). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntitlementTests : MeshTestCase() {

    /** Trust provider di test: la "catena" e' una sola chiave P-256 nota. */
    class StubTrust(private val key: ECPublicKey) : JWSTrustProvider {
        override fun verifiedSigningKey(x5c: List<ByteArray>): ECPublicKey {
            if (x5c.isEmpty()) throw JWSTransactionVerifier.Failure.MissingCertificates
            return key
        }
    }

    private fun p256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Firma ES256 in formato JWS (raw `r || s`, 64 byte). */
    private fun es256(message: ByteArray, signer: KeyPair): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private)
            update(message)
            sign()
        }
        val sequence = ASN1Sequence.getInstance(der)
        fun fixed(i: Int): ByteArray {
            val bytes = (sequence.getObjectAt(i) as ASN1Integer).positiveValue.toByteArray()
            val trimmed = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
            return ByteArray(32 - trimmed.size) + trimmed
        }
        return fixed(0) + fixed(1)
    }

    private fun makeJWS(payload: JSONObject, signer: KeyPair, alg: String = "ES256"): String {
        val header = JSONObject().put("alg", alg).put("x5c", JSONArray().put("fake-cert".toByteArray().base64EncodedString()))
        val headerPart = header.toString().toByteArray().base64URLEncodedString()
        val payloadPart = payload.toString().toByteArray().base64URLEncodedString()
        val signature = es256("$headerPart.$payloadPart".toByteArray(), signer)
        return "$headerPart.$payloadPart.${signature.base64URLEncodedString()}"
    }

    private inline fun <reified T : Throwable> expectThrows(expected: T, block: () -> Unit) {
        try {
            block()
            fail("attesa eccezione $expected")
        } catch (error: Throwable) {
            assertEquals(expected, error)
        }
    }

    private val activity: Activity get() = Robolectric.buildActivity(Activity::class.java).setup().get()

    private suspend fun makeDatabase(): AppDatabase {
        val database = AppDatabase.makeTemporary(context)
        database.identities.save(IdentityRecord(identity = IdentityKeys.generate(), nickname = "Pro", avatarColorHex = "00E5FF"))
        return database
    }

    // MARK: - JWS

    @Test
    fun jwsVerification() {
        val signer = p256()
        val verifier = JWSTransactionVerifier(expectedBundleID = "com.aeromesh.app", trustProvider = StubTrust(signer.public as ECPublicKey))
        val purchase = System.currentTimeMillis() - 3_600_000
        fun payload() = JSONObject()
            .put("transactionId", "2000000123").put("originalTransactionId", "2000000123")
            .put("productId", MeshProtocol.ProductID.pass72h).put("bundleId", "com.aeromesh.app")
            .put("purchaseDate", purchase).put("type", "Consumable").put("environment", "Production")
        val jws = makeJWS(payload(), signer)
        val verified = verifier.verify(jws)
        assertEquals("com.aeromesh.pass72h", verified.productId)
        assertEquals(purchase, verified.purchaseDate)

        val state = EntitlementManager.state(from = verified, now = System.currentTimeMillis(), source = EntitlementState.Source.cachedReceipt)
        assertEquals(ProTier.pass72h, state.tier)
        assertTrue(state.isPro())
        assertEquals(purchase + 72 * 3_600 * 1_000L, state.validUntilMs)
        assertEquals(
            EntitlementState.free,
            EntitlementManager.state(from = verified, now = System.currentTimeMillis() + 73 * 3_600_000L, source = EntitlementState.Source.cachedReceipt),
        )

        // Firma di un'altra chiave.
        expectThrows(JWSTransactionVerifier.Failure.InvalidSignature) { verifier.verify(makeJWS(payload(), p256())) }
        // Payload alterato dopo la firma.
        val parts = jws.split(".").toMutableList()
        parts[1] = JSONObject().put("productId", "com.aeromesh.lifetime").put("purchaseDate", purchase).put("transactionId", "x")
            .toString().toByteArray().base64URLEncodedString()
        expectThrows(JWSTransactionVerifier.Failure.InvalidSignature) { verifier.verify(parts.joinToString(".")) }
        // Algoritmo non supportato e bundle sbagliato.
        expectThrows(JWSTransactionVerifier.Failure.UnsupportedAlgorithm("HS256")) { verifier.verify(makeJWS(payload(), signer, alg = "HS256")) }
        expectThrows(JWSTransactionVerifier.Failure.WrongBundle) { verifier.verify(makeJWS(payload().put("bundleId", "com.other.app"), signer)) }
        expectThrows(JWSTransactionVerifier.Failure.Malformed) { verifier.verify("non.un.jws.valido") }
        expectThrows(JWSTransactionVerifier.Failure.Revoked) { verifier.verify(makeJWS(payload().put("revocationDate", purchase), signer)) }
        assertEquals("2000000123", JWSTransactionVerifier.unverifiedPayload(jws).transactionId)
    }

    @Test
    fun appleTrustProviderRejectsGarbage() {
        val provider = AppleChainTrustProvider()
        expectThrows(JWSTransactionVerifier.Failure.MissingCertificates) { provider.verifiedSigningKey(listOf("garbage".toByteArray())) }
        assertEquals(64, JWSTransactionVerifier.appleRootCAG3Fingerprint.length)
    }

    // MARK: - EntitlementManager

    @Test
    fun entitlementManagerRefreshFromCachedReceipt() = meshTest {
        val database = makeDatabase()
        val signer = p256()
        val verifier = JWSTransactionVerifier(expectedBundleID = null, trustProvider = StubTrust(signer.public as ECPublicKey))
        val manager = EntitlementManager(context, database, verifier = verifier, billing = FakeBillingGateway())
        manager.refresh()
        assertEquals(EntitlementState.free, manager.state)

        val jws = makeJWS(
            JSONObject().put("transactionId", "1").put("productId", "com.aeromesh.lifetime").put("purchaseDate", System.currentTimeMillis()),
            signer,
        )
        database.identities.updateProStatus(tier = ProTier.lifetime, validUntilMs = null, receiptJWS = jws)
        val events = async(start = CoroutineStart.UNDISPATCHED) { manager.events().take(2).toList() }
        manager.refresh()
        val received = withTimeout(5_000) { events.await() }
        assertEquals(EntitlementState.free, received[0]) // stato corrente
        assertEquals(ProTier.lifetime, received[1].tier)
        assertEquals(EntitlementState.Source.cachedReceipt, received[1].source)
        assertTrue(manager.state.isPro())
        manager.close()
        database.close()
    }

    @Test
    fun playPurchaseLifetimeAcknowledgedAndPersisted() = meshTest {
        val database = makeDatabase()
        val billing = FakeBillingGateway()
        val manager = EntitlementManager(context, database, billing = billing)
        manager.start()
        assertEquals(listOf("com.aeromesh.pass72h", "com.aeromesh.lifetime"), manager.products.value.map { it.id })
        assertEquals("€ 2,99", manager.product("com.aeromesh.pass72h")?.displayPrice)

        val state = manager.purchase("com.aeromesh.lifetime", activity)
        assertEquals(ProTier.lifetime, state.tier)
        assertEquals(EntitlementState.Source.storeKit, state.source)
        assertEquals(1, billing.acknowledged.size)
        assertTrue("lifetime mai consumato", billing.consumed.isEmpty())
        val record = database.identities.fetch()!!
        assertEquals(ProTier.lifetime, record.proTier)
        assertNotNull(PlayReceipt.decode(record.proReceiptJWS!!))

        // Nuova installazione logica: il lifetime torna dagli acquisti Play anche senza ricevuta.
        database.identities.updateProStatus(ProTier.free, null, null)
        val restored = EntitlementManager(context, database, billing = billing)
        restored.restorePurchases()
        assertEquals(ProTier.lifetime, restored.state.tier)
        assertEquals(1, billing.acknowledged.size) // gia' acknowledged: non ripetuto
        manager.close()
        restored.close()
        database.close()
    }

    @Test
    fun playPurchasePass72hConsumedAndRestoredFromReceipt() = meshTest {
        val database = makeDatabase()
        val billing = FakeBillingGateway()
        val purchaseTime = System.currentTimeMillis() - 3_600_000
        billing.nextOutcome = { id -> BillingPurchaseOutcome.Success(listOf(FakeBillingGateway.purchase(id, purchaseTime, token = "pass-1"))) }
        val manager = EntitlementManager(context, database, billing = billing)
        manager.start()
        val state = manager.purchase("com.aeromesh.pass72h", activity)
        assertEquals(ProTier.pass72h, state.tier)
        assertEquals(purchaseTime + 72 * 3_600_000L, state.validUntilMs)
        assertEquals(listOf("pass-1"), billing.consumed)
        assertEquals(purchaseTime + 72 * 3_600_000L, database.identities.fetch()?.proValidUntilMs)

        // Il pass consumato non e' piu' negli acquisti Play: lo stato torna dalla ricevuta salvata.
        val restored = EntitlementManager(context, database, billing = billing)
        restored.refresh()
        assertEquals(ProTier.pass72h, restored.state.tier)
        assertEquals(EntitlementState.Source.cachedReceipt, restored.state.source)
        restored.refresh(now = purchaseTime + 73 * 3_600_000L)
        assertEquals(EntitlementState.free, restored.state)
        manager.close()
        restored.close()
        database.close()
    }

    @Test
    fun playPurchaseFailuresAndUnfinishedPurchases() = meshTest {
        val database = makeDatabase()
        val billing = FakeBillingGateway()
        val manager = EntitlementManager(context, database, billing = billing)
        manager.start()

        billing.nextOutcome = { BillingPurchaseOutcome.Cancelled }
        expectFailure(EntitlementManager.Failure.Cancelled) { manager.purchase("com.aeromesh.pass72h", activity) }
        billing.nextOutcome = { id -> BillingPurchaseOutcome.Success(listOf(FakeBillingGateway.purchase(id, state = BillingPurchase.State.pending))) }
        expectFailure(EntitlementManager.Failure.Pending) { manager.purchase("com.aeromesh.pass72h", activity) }
        expectFailure(EntitlementManager.Failure.ProductNotFound("com.other")) { manager.purchase("com.other", activity) }
        billing.nextOutcome = { BillingPurchaseOutcome.Failed(BillingClient.BillingResponseCode.NETWORK_ERROR, "rete") }
        expectFailure(EntitlementManager.Failure.BillingError(BillingClient.BillingResponseCode.NETWORK_ERROR, "rete")) {
            manager.purchase("com.aeromesh.pass72h", activity)
        }
        assertEquals(EntitlementState.free, manager.state)
        assertTrue(billing.consumed.isEmpty())

        // Pass comprato ma non consumato (app chiusa prima del consume): applicato e consumato al refresh.
        billing.owned += FakeBillingGateway.purchase("com.aeromesh.pass72h", token = "pending-finish")
        manager.refresh()
        assertEquals(ProTier.pass72h, manager.state.tier)
        assertEquals(listOf("pending-finish"), billing.consumed)

        // Acquisto arrivato fuori dal flusso (pending completato): applicato dal listener.
        billing.updates.emit(listOf(FakeBillingGateway.purchase("com.aeromesh.lifetime", token = "late")))
        assertTrue(eventually { manager.state.tier == ProTier.lifetime })
        assertTrue(eventually { billing.acknowledged == listOf("late") })

        // Offline: refresh senza Play usa la ricevuta salvata.
        billing.available = false
        val offline = EntitlementManager(context, database, billing = billing)
        offline.start()
        assertEquals(ProTier.lifetime, offline.state.tier)
        assertEquals(EntitlementState.Source.cachedReceipt, offline.state.source)
        manager.close()
        offline.close()
        database.close()
    }

    @Test
    fun playPurchaseSignatureVerifier() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val json = FakeBillingGateway.purchase("com.aeromesh.lifetime").originalJson
        val signature = Signature.getInstance("SHA1withRSA").run {
            initSign(keys.private)
            update(json.toByteArray())
            sign()
        }.base64EncodedString()
        val verifier = PlayPurchaseVerifier(keys.public.encoded.base64EncodedString())
        assertTrue(verifier.verify(json, signature))
        assertFalse(verifier.verify(json.replace("lifetime", "pass72h"), signature))
        assertFalse(verifier.verify(json, ""))
        assertTrue("senza chiave: accettato", PlayPurchaseVerifier().verify(json, ""))

        val receipt = PlayReceipt.encode(json, signature)
        assertEquals(json to signature, PlayReceipt.decode(receipt))
        assertNull("un JWS non e' una ricevuta Play", PlayReceipt.decode("a.b.c"))
        val payload = PlayReceipt.parse(json)!!
        assertEquals(listOf("com.aeromesh.lifetime"), payload.productIDs)
        assertEquals(ProTier.lifetime, EntitlementManager.state(from = payload, now = 0, source = EntitlementState.Source.cachedReceipt).tier)
    }

    private suspend fun expectFailure(expected: EntitlementManager.Failure, block: suspend () -> Unit) {
        try {
            block()
            fail("attesa $expected")
        } catch (error: EntitlementManager.Failure) {
            assertEquals(expected, error)
        }
    }

    // MARK: - Pro Pass

    @Test
    fun proPassGrantSignature() {
        val issuer = IdentityKeys.generate()
        val groupID = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val validUntil = now + 72 * 3_600_000L
        val grant = ProPassService.makeGrant(groupID = groupID, validUntilMs = validUntil, productID = "com.aeromesh.pass72h", identity = issuer)
        assertEquals(64, grant.signature.size())
        assertEquals(24, ProPassService.signedMessage(grant.groupUuid.toByteArray(), validUntil).size)
        assertTrue(ProPassService.verify(grant, now))
        assertFalse("scaduto", ProPassService.verify(grant, now + 73 * 3_600_000L))

        val tooLongUntil = now + 10 * 24 * 3_600_000L
        val tooLong = grant.toBuilder()
            .setValidUntilMs(tooLongUntil)
            .setSignature(ByteString.copyFrom(CryptoEngine.sign(ProPassService.signedMessage(grant.groupUuid.toByteArray(), tooLongUntil), issuer.signingKey)))
            .build()
        assertFalse("oltre 72h + tolleranza", ProPassService.verify(tooLong, now))

        val forged = grant.toBuilder().setIssuerEd25519Pub(ByteString.copyFrom(IdentityKeys.generate().ed25519PublicKeyRaw)).build()
        assertFalse(ProPassService.verify(forged, now))

        val roundTrip = MeshWire.parseProPassGrant(MeshWire.serialize(grant))
        assertEquals(grant, roundTrip)
    }

    /** Compatibilita' byte-a-byte con iOS: `group_uuid (16) || Int64 big-endian`. */
    @Test
    fun proPassSignedMessageLayoutMatchesIOS() {
        val groupID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val raw = "00112233445566778899aabbccddeeff".hexToByteArray()!!
        val message = ProPassService.signedMessage(raw, 0x0102030405060708L)
        assertEquals("00112233445566778899aabbccddeeff0102030405060708", message.toHex())
        val grant = ProPassService.makeGrant(groupID, 0x0102030405060708L, "com.aeromesh.lifetime", IdentityKeys.generate())
        assertArrayEquals(raw, grant.groupUuid.toByteArray())
        assertTrue(CryptoEngine.verify(grant.signature.toByteArray(), message, grant.issuerEd25519Pub.toByteArray()))
    }

    @Test
    fun proPassPropagatesOverMesh() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)

        // Alice e' Pro (stato iniettato come dopo un acquisto verificato).
        val aliceEntitlements = EntitlementManager(context, alice.database, billing = FakeBillingGateway())
        aliceEntitlements.applyVerifiedState(EntitlementState(ProTier.lifetime, null, EntitlementState.Source.storeKit), receiptJWS = null)
        val bobEntitlements = EntitlementManager(context, bob.database, billing = FakeBillingGateway())
        val aliceService = ProPassService(alice.router, alice.database, aliceEntitlements, alice.identity, "Alice")
        val bobService = ProPassService(bob.router, bob.database, bobEntitlements, bob.identity, "Bob")
        aliceService.start()
        bobService.start()
        val bobEvents = bobService.events()

        val group = alice.router.createGroup(title = "Volo")
        val invite = requireNotNull(alice.router.groupInvitePayload(group))
        val bobGroup = bob.router.pair(with = invite)
        assertFalse(bobGroup.isProUnlocked())

        assertTrue(aliceService.broadcastGrantIfEntitled(group))
        assertEquals(true, alice.database.conversations.fetch(id = group.id)?.isProUnlocked())

        val event = withTimeout(10_000) { bobEvents.first() }
        val unlocked = event as ProPassService.Event.Unlocked
        assertEquals(bobGroup.id, unlocked.conversationID)
        assertArrayEquals(alice.nodeID, unlocked.issuerNodeID)
        assertTrue(unlocked.validUntilMs > System.currentTimeMillis())
        assertEquals(true, bob.database.conversations.fetch(id = bobGroup.id)?.isProUnlocked())

        // Bob gratuito: non emette grant.
        assertFalse(bobService.broadcastGrantIfEntitled(bobGroup))
        aliceService.stop()
        bobService.stop()
        aliceEntitlements.close()
        bobEntitlements.close()
    }

    // MARK: - Feature gate

    @Test
    fun featureGateRules() {
        val peer = IdentityKeys.generate()
        val peerRecord = PeerRecord(nodeID = peer.nodeID, x25519Pub = peer.x25519PublicKeyRaw, ed25519Pub = peer.ed25519PublicKeyRaw, nickname = "P")
        var direct = ConversationRecord.direct(with = peerRecord)
        val lounge = ConversationRecord.cabinLounge()

        assertEquals(FeatureDecision.requiresPass, FeatureGate.decision(ProFeature.photo, false, direct, Reachability.Direct(rssi = -50)))
        assertEquals(FeatureDecision.allowed, FeatureGate.decision(ProFeature.photo, true, direct, Reachability.Direct(rssi = -50)))
        assertEquals(FeatureDecision.photosNeedDirectLink, FeatureGate.decision(ProFeature.photo, true, direct, Reachability.ViaBridge(hops = 2)))
        assertEquals(FeatureDecision.allowed, FeatureGate.decision(ProFeature.voiceNote, true, direct, Reachability.ViaBridge(hops = 3)))
        assertEquals(FeatureDecision.peerOutOfRange, FeatureGate.decision(ProFeature.voiceNote, true, direct, Reachability.OutOfRange))
        assertEquals(FeatureDecision.allowed, FeatureGate.decision(ProFeature.game, true, direct, Reachability.OutOfRange))
        assertEquals(FeatureDecision.loungeTextOnly, FeatureGate.decision(ProFeature.photo, true, lounge, Reachability.Direct(rssi = null)))
        assertEquals(FeatureDecision.callSignalTooWeak, FeatureGate.decision(ProFeature.call, true, direct, Reachability.Direct(rssi = -95)))
        assertEquals(FeatureDecision.callNeedsDirectLink, FeatureGate.decision(ProFeature.call, true, direct, Reachability.ViaBridge(hops = 2)))

        direct = direct.copy(proUnlockedUntilMs = System.currentTimeMillis() + 3_600_000)
        assertEquals(FeatureDecision.allowed, FeatureGate.decision(ProFeature.game, false, direct, Reachability.Direct(rssi = null)))
        assertTrue(FeatureGate.decision(ProFeature.groupChat, false, direct, Reachability.Direct(rssi = null)).isAllowed)
    }
}
