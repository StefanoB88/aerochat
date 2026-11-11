package com.stefanobrandi.aerochat.core.crypto

import com.stefanobrandi.aerochat.core.protocol.base64URLDecoded
import com.stefanobrandi.aerochat.core.protocol.base64URLEncodedString
import com.stefanobrandi.aerochat.core.protocol.bigEndianBytes
import com.stefanobrandi.aerochat.core.protocol.hexString
import com.stefanobrandi.aerochat.core.protocol.hexToByteArray
import com.stefanobrandi.aerochat.core.protocol.longFromBigEndianBytes
import com.stefanobrandi.aerochat.core.protocol.uInt16FromLittleEndianBytes
import com.stefanobrandi.aerochat.core.protocol.uInt16LittleEndianBytes
import com.stefanobrandi.aerochat.core.protocol.uInt32FromBigEndianBytes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Port di `IdentityPairingTests.swift`. Il `KeychainStore` reale richiede l'Android Keystore
 * (assente su JVM/Robolectric): il contratto save/load/overwrite/delete e' verificato su
 * [InMemorySecretStore], che e' anche lo store iniettato in [IdentityStore].
 */
class IdentityPairingTests {

    @Test
    fun secretStoreRoundTrip() {
        val store: SecretStore = InMemorySecretStore()
        val account = "unit-test"
        assertNull(store.load(account))

        store.save(byteArrayOf(1, 2, 3), account)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.load(account))

        store.save(byteArrayOf(9, 9), account)
        assertArrayEquals(byteArrayOf(9, 9), store.load(account))

        store.delete(account)
        assertNull(store.load(account))
        store.delete(account) // idempotente
    }

    @Test
    fun identityStorePersistsAcrossInstances() = runTest {
        val keychain = InMemorySecretStore()
        val first = IdentityStore(keychain)
        assertFalse(first.hasStoredIdentity())
        val keys = first.loadOrCreateKeys()
        assertEquals(8, keys.nodeID.size)
        assertEquals(32, keys.x25519PublicKeyRaw.size)
        assertEquals(32, keys.ed25519PublicKeyRaw.size)
        assertTrue(first.hasStoredIdentity())

        val second = IdentityStore(keychain)
        val reloaded = second.loadOrCreateKeys()
        assertArrayEquals(keys.nodeID, reloaded.nodeID)
        assertArrayEquals(keys.ed25519PublicKeyRaw, reloaded.ed25519PublicKeyRaw)

        second.reset()
        val regenerated = second.loadOrCreateKeys()
        assertFalse(regenerated.nodeID.contentEquals(keys.nodeID))
        second.reset()
    }

    @Test
    fun pairingURIRoundTrip() {
        val identity = IdentityKeys.generate()
        val groupID = UUID.randomUUID()
        val groupKey = CryptoEngine.randomGroupSecretKey()
        val payload = PairingPayload(identity, nickname = "Stefano Rossi ✈", groupID = groupID, groupSecretKey = groupKey)

        val uri = payload.uriString
        assertTrue(uri.startsWith("aeromesh://v1?x="))
        assertFalse(uri.contains(" "))
        assertFalse(uri.contains("+"))
        // base64url senza padding: '=' compare solo come separatore chiave=valore, mai dentro i valori.
        val query = uri.split("?", limit = 2).last()
        for (pair in query.split("&")) {
            val value = pair.split("=", limit = 2).last()
            assertFalse("padding '=' inatteso in $pair", value.contains("="))
        }

        val parsed = PairingPayload(uriString = uri)
        assertEquals(payload, parsed)
        assertArrayEquals(identity.nodeID, parsed.nodeID)
        assertTrue(parsed.isGroupInvite)
        assertNotNull(parsed.url)
    }

    @Test
    fun pairingURICarriesGroupTitle() {
        val identity = IdentityKeys.generate()
        val payload = PairingPayload(
            identity, nickname = "Bob", groupID = UUID.randomUUID(),
            groupSecretKey = CryptoEngine.randomGroupSecretKey(), groupTitle = "  Volo FR 1234 & co ✈ ",
        )
        assertEquals("Volo FR 1234 & co ✈", payload.groupTitle)
        val uri = payload.uriString
        assertTrue(uri.contains("&gt=Volo%20FR%201234%20%26%20co%20"))
        assertEquals(payload, PairingPayload(uriString = uri))

        // Android con `URLEncoder` ("+" per lo spazio) e QR legacy senza 'gt'.
        val android = PairingPayload(uriString = uri.replace("Volo%20FR%201234", "Volo+FR+1234"))
        assertEquals("Volo FR 1234 & co ✈", android.groupTitle)
        val legacy = PairingPayload(uriString = uri.substring(0, uri.indexOf("&gt=")))
        assertNull(legacy.groupTitle)
        assertTrue(legacy.isGroupInvite)
    }

    @Test
    fun pairingURIAndroidCompatibility() {
        val identity = IdentityKeys.generate()
        val x = identity.x25519PublicKeyRaw.base64URLEncodedString()
        val e = identity.ed25519PublicKeyRaw.base64URLEncodedString()
        val androidURI = "aeromesh://v1?x=$x&e=$e&n=Mario+Rossi%21"
        val parsed = PairingPayload(uriString = androidURI)
        assertEquals("Mario Rossi!", parsed.nickname)
        assertNull(parsed.groupID)
        assertNull(parsed.groupSecretKey)
        assertFalse(parsed.isGroupInvite)
    }

    @Test
    fun pairingURIValidation() {
        val identity = IdentityKeys.generate()
        val good = PairingPayload(identity, nickname = "ok").uriString
        assertEquals(
            PairingPayload.Failure.InvalidScheme,
            assertThrows(PairingPayload.Failure::class.java) { PairingPayload(uriString = "https://example.com") },
        )
        assertEquals(
            PairingPayload.Failure.UnsupportedVersion,
            assertThrows(PairingPayload.Failure::class.java) { PairingPayload(uriString = good.replace("v1?", "v9?")) },
        )
        val shortKey = ByteArray(16) { 1 }.base64URLEncodedString()
        assertEquals(
            PairingPayload.Failure.InvalidField("x"),
            assertThrows(PairingPayload.Failure::class.java) {
                PairingPayload(uriString = "aeromesh://v1?x=$shortKey&e=${identity.ed25519PublicKeyRaw.base64URLEncodedString()}&n=a")
            },
        )
        assertEquals(
            PairingPayload.Failure.MissingField("n"),
            assertThrows(PairingPayload.Failure::class.java) {
                PairingPayload(
                    uriString = "aeromesh://v1?x=${identity.x25519PublicKeyRaw.base64URLEncodedString()}" +
                        "&e=${identity.ed25519PublicKeyRaw.base64URLEncodedString()}",
                )
            },
        )
        assertEquals(
            PairingPayload.Failure.InvalidField("gk"),
            assertThrows(PairingPayload.Failure::class.java) {
                // Foundation `uuidString` e' maiuscolo: il parser deve accettarlo.
                PairingPayload(uriString = good + "&g=${UUID.randomUUID().toString().uppercase()}&gk=$shortKey")
            },
        )
        // UUID non canonico (accettato da java.util.UUID.fromString ma non da Foundation) rifiutato.
        assertEquals(
            PairingPayload.Failure.InvalidField("g"),
            assertThrows(PairingPayload.Failure::class.java) { PairingPayload(uriString = "$good&g=1-2-3-4-5") },
        )
    }

    @Test
    fun nicknamePercentEncodingMatchesFoundation() {
        // Foundation `.alphanumerics` include le lettere Unicode: restano in chiaro; spazio, simboli ed emoji no.
        assertEquals("José", PairingPayload.percentEncode("José"))
        assertEquals("Stefano%20Rossi%20%E2%9C%88", PairingPayload.percentEncode("Stefano Rossi ✈"))
        assertEquals("a-b._~%2B%26%3D%3F", PairingPayload.percentEncode("a-b._~+&=?"))
        assertEquals("%F0%9F%98%80", PairingPayload.percentEncode("😀"))
        assertEquals("José ✈", PairingPayload.percentDecode("Jos%C3%A9%20%E2%9C%88"))
        assertNull(PairingPayload.percentDecode("bad%2"))
        assertNull(PairingPayload.percentDecode("bad%zz"))
        assertNull(PairingPayload.percentDecode("%FF"))
    }

    @Test
    fun byteHelpers() {
        val data = byteArrayOf(0x00, 0xAB.toByte(), 0xFF.toByte())
        assertEquals("00abff", data.hexString)
        assertArrayEquals(data, "00ABff".hexToByteArray())
        assertNull("abc".hexToByteArray())
        assertArrayEquals(data, data.base64URLEncodedString().base64URLDecoded())
        assertEquals("-_8", byteArrayOf(0xFB.toByte(), 0xFF.toByte()).base64URLEncodedString())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), 0x01020304.bigEndianBytes)
        assertArrayEquals(byteArrayOf(0x34, 0x12), uInt16LittleEndianBytes(0x1234))
        assertEquals(0x1234, uInt16FromLittleEndianBytes(byteArrayOf(0x34, 0x12)))
        assertEquals(300L, longFromBigEndianBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 44)))
        assertNull(uInt32FromBigEndianBytes(byteArrayOf(1, 2, 3)))
    }
}
