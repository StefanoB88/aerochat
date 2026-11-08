package com.example.aerochat.core.entitlements

import com.example.aerochat.core.protocol.base64Decoded
import com.example.aerochat.core.protocol.base64URLDecoded
import com.example.aerochat.core.protocol.toHex
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.json.JSONException
import org.json.JSONObject

/**
 * Payload della transazione StoreKit 2 (JWS firmato da Apple) – solo i campi che ci servono.
 * Su Android serve a validare ricevute di origine iOS (le ricevute Play usano [PlayReceipt]).
 */
data class JWSTransactionPayload(
    val transactionId: String,
    val originalTransactionId: String? = null,
    val productId: String,
    val bundleId: String? = null,
    /** Millisecondi Unix. */
    val purchaseDate: Long,
    val expiresDate: Long? = null,
    val revocationDate: Long? = null,
    val type: String? = null,
    val environment: String? = null,
) {
    companion object {
        /** Decodifica come `JSONDecoder` di Swift: campi obbligatori mancanti -> eccezione. */
        @Throws(JSONException::class)
        fun fromJSON(data: ByteArray): JWSTransactionPayload {
            val json = JSONObject(String(data, Charsets.UTF_8))
            return JWSTransactionPayload(
                transactionId = json.getString("transactionId"),
                originalTransactionId = json.optStringOrNull("originalTransactionId"),
                productId = json.getString("productId"),
                bundleId = json.optStringOrNull("bundleId"),
                purchaseDate = json.getLong("purchaseDate"),
                expiresDate = json.optLongOrNull("expiresDate"),
                revocationDate = json.optLongOrNull("revocationDate"),
                type = json.optStringOrNull("type"),
                environment = json.optStringOrNull("environment"),
            )
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

/** Fornisce la chiave pubblica del firmatario dopo aver validato la catena `x5c` (DER). */
fun interface JWSTrustProvider {
    @Throws(JWSTransactionVerifier.Failure::class)
    fun verifiedSigningKey(x5c: List<ByteArray>): ECPublicKey
}

/**
 * Verifica **offline** della ricevuta JWS di StoreKit 2: catena di certificati `x5c`, pin sulla root
 * Apple, firma ES256 su `header.payload`.
 */
class JWSTransactionVerifier(
    val expectedBundleID: String? = iOSBundleID,
    val trustProvider: JWSTrustProvider = AppleChainTrustProvider(),
) {

    sealed class Failure(message: String) : Exception(message) {
        data object Malformed : Failure("malformed") {
            private fun readResolve(): Any = Malformed
        }
        data class UnsupportedAlgorithm(val alg: String) : Failure("unsupported algorithm $alg")
        data object MissingCertificates : Failure("missing certificates") {
            private fun readResolve(): Any = MissingCertificates
        }
        data class UntrustedChain(val reason: String) : Failure("untrusted chain: $reason")
        data class RootNotPinned(val fingerprint: String) : Failure("root not pinned: $fingerprint")
        data object InvalidSignature : Failure("invalid signature") {
            private fun readResolve(): Any = InvalidSignature
        }
        data object WrongBundle : Failure("wrong bundle") {
            private fun readResolve(): Any = WrongBundle
        }
        data object Revoked : Failure("revoked") {
            private fun readResolve(): Any = Revoked
        }
    }

    data class Header(val alg: String, val x5c: List<String>?)

    /** Verifica firma e catena; ritorna il payload decodificato. */
    @Throws(Failure::class)
    fun verify(jws: String): JWSTransactionPayload {
        val parts = jws.split(".")
        if (parts.size != 3) throw Failure.Malformed
        val headerData = parts[0].base64URLDecoded() ?: throw Failure.Malformed
        val payloadData = parts[1].base64URLDecoded() ?: throw Failure.Malformed
        val signature = parts[2].base64URLDecoded() ?: throw Failure.Malformed
        val header = decodeHeader(headerData)
        if (header.alg != "ES256") throw Failure.UnsupportedAlgorithm(header.alg)
        val x5c = header.x5c
        if (x5c.isNullOrEmpty()) throw Failure.MissingCertificates
        val certificates = x5c.map { encoded -> encoded.base64Decoded() ?: encoded.base64URLDecoded() ?: throw Failure.Malformed }

        val key = trustProvider.verifiedSigningKey(certificates)
        val signedMessage = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
        if (signature.size != 64 || !verifyES256(key, signedMessage, signature)) throw Failure.InvalidSignature

        val payload = decode { JWSTransactionPayload.fromJSON(payloadData) }
        val bundle = payload.bundleId
        if (expectedBundleID != null && bundle != null && bundle != expectedBundleID) throw Failure.WrongBundle
        if (payload.revocationDate != null) throw Failure.Revoked
        return payload
    }

    private fun decodeHeader(data: ByteArray): Header = decode {
        val json = JSONObject(String(data, Charsets.UTF_8))
        val array = json.optJSONArray("x5c")
        Header(alg = json.getString("alg"), x5c = array?.let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    companion object {
        /** SHA-256 di "Apple Root CA - G3" (DER). Pin aggiuntivo oltre alla valutazione della catena. */
        const val appleRootCAG3Fingerprint = "63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179"

        /** Bundle identifier dell'app iOS (le ricevute StoreKit portano questo `bundleId`). */
        const val iOSBundleID = "com.stefanobrandipersonalteam.AeroChat"

        /** Decodifica il payload senza verificarlo (diagnostica / UI "acquistato il ..."). */
        @Throws(Failure::class)
        fun unverifiedPayload(jws: String): JWSTransactionPayload {
            val parts = jws.split(".")
            if (parts.size != 3) throw Failure.Malformed
            val payloadData = parts[1].base64URLDecoded() ?: throw Failure.Malformed
            return decode { JWSTransactionPayload.fromJSON(payloadData) }
        }

        private inline fun <T> decode(block: () -> T): T =
            try {
                block()
            } catch (_: JSONException) {
                throw Failure.Malformed
            } catch (_: RuntimeException) {
                throw Failure.Malformed
            }

        /** ES256: firma raw `r || s` (64 byte, formato JWS) convertita in DER per `SHA256withECDSA`. */
        internal fun verifyES256(key: ECPublicKey, message: ByteArray, rawSignature: ByteArray): Boolean =
            try {
                val r = BigInteger(1, rawSignature.copyOfRange(0, 32))
                val s = BigInteger(1, rawSignature.copyOfRange(32, 64))
                val der = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).encoded
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(key)
                verifier.update(message)
                verifier.verify(der)
            } catch (_: Exception) {
                false
            }
    }
}

/**
 * Valuta la catena `x5c` (ogni certificato firmato dal successivo, date valide, root auto-firmata) e,
 * se richiesto, verifica che la root coincida con il pin Apple Root CA - G3. Senza pin la catena viene
 * valutata dal trust store di sistema. Funziona offline (nessun fetch di rete / OCSP).
 */
class AppleChainTrustProvider(
    val pinnedRootFingerprints: Set<String> = setOf(JWSTransactionVerifier.appleRootCAG3Fingerprint),
    val requirePinnedRoot: Boolean = true,
) : JWSTrustProvider {

    override fun verifiedSigningKey(x5c: List<ByteArray>): ECPublicKey {
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = x5c.mapNotNull { der ->
            try {
                factory.generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
            } catch (_: Exception) {
                null
            }
        }
        if (certificates.size != x5c.size || certificates.isEmpty()) {
            throw JWSTransactionVerifier.Failure.MissingCertificates
        }
        val leaf = certificates.first()

        try {
            for (index in certificates.indices) {
                val certificate = certificates[index]
                certificate.checkValidity()
                val issuer = certificates.getOrNull(index + 1) ?: certificate
                certificate.verify(issuer.publicKey)
            }
        } catch (error: Exception) {
            throw JWSTransactionVerifier.Failure.UntrustedChain(error.message ?: "evaluate")
        }

        if (requirePinnedRoot) {
            val root = certificates.last()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(root.encoded).toHex()
            if (fingerprint !in pinnedRootFingerprints) throw JWSTransactionVerifier.Failure.RootNotPinned(fingerprint)
        } else {
            try {
                val factoryTM = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                factoryTM.init(null as KeyStore?)
                val trustManager = factoryTM.trustManagers.filterIsInstance<X509TrustManager>().first()
                trustManager.checkServerTrusted(certificates.toTypedArray(), "ECDHE_ECDSA")
            } catch (error: Exception) {
                throw JWSTransactionVerifier.Failure.UntrustedChain(error.message ?: "evaluate")
            }
        }

        return leaf.publicKey as? ECPublicKey ?: throw JWSTransactionVerifier.Failure.InvalidSignature
    }
}
