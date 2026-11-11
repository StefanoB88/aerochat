package com.stefanobrandi.aerochat.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

/**
 * Coppia di chiavi di identita' del nodo locale (X25519 per ECDH, Ed25519 per firma).
 * Le chiavi pubbliche sono calcolate una sola volta; i getter restituiscono copie.
 */
class IdentityKeys(
    val agreementKey: X25519PrivateKeyParameters,
    val signingKey: Ed25519PrivateKeyParameters,
) {
    private val x25519Public: ByteArray = agreementKey.generatePublicKey().encoded
    private val ed25519Public: ByteArray = signingKey.generatePublicKey().encoded
    private val nodeIDBytes: ByteArray = CryptoEngine.nodeID(forX25519PublicKey = x25519Public)

    /** Ricostruisce le chiavi dai 32 byte raw salvati nel [SecretStore]. */
    @Throws(CryptoEngine.Failure::class)
    constructor(x25519PrivateRaw: ByteArray, ed25519PrivateRaw: ByteArray) : this(
        agreementKey = x25519PrivateKey(x25519PrivateRaw),
        signingKey = ed25519PrivateKey(ed25519PrivateRaw),
    )

    /** Chiave pubblica X25519 raw (32 byte) – campo `x` del QR e `sender_x25519_pub`. */
    val x25519PublicKeyRaw: ByteArray get() = x25519Public.copyOf()

    /** Chiave pubblica Ed25519 raw (32 byte) – campo `e` del QR e `sender_ed25519_pub`. */
    val ed25519PublicKeyRaw: ByteArray get() = ed25519Public.copyOf()

    /** `node_id` locale: primi 8 byte di `SHA256(x25519_public_key_raw)`. */
    val nodeID: ByteArray get() = nodeIDBytes.copyOf()

    /** Chiave privata X25519 raw (32 byte), come `agreementKey.rawRepresentation`. */
    val x25519PrivateKeyRaw: ByteArray get() = agreementKey.encoded

    /** Seed privato Ed25519 raw (32 byte), come `signingKey.rawRepresentation`. */
    val ed25519PrivateKeyRaw: ByteArray get() = signingKey.encoded

    /** Chiave simmetrica della chat 1-a-1 con il peer indicato. */
    @Throws(CryptoEngine.Failure::class)
    fun directChatKey(peerX25519PublicKeyRaw: ByteArray): SymmetricKey =
        CryptoEngine.deriveDirectChatKey(agreementKey, peerX25519PublicKeyRaw)

    companion object {
        private val random = SecureRandom()

        fun generate(): IdentityKeys =
            IdentityKeys(X25519PrivateKeyParameters(random), Ed25519PrivateKeyParameters(random))

        private fun x25519PrivateKey(raw: ByteArray): X25519PrivateKeyParameters {
            if (raw.size != X25519PrivateKeyParameters.KEY_SIZE) {
                throw CryptoEngine.Failure.InvalidKeyLength(X25519PrivateKeyParameters.KEY_SIZE, raw.size)
            }
            return X25519PrivateKeyParameters(raw, 0)
        }

        private fun ed25519PrivateKey(raw: ByteArray): Ed25519PrivateKeyParameters {
            if (raw.size != Ed25519PrivateKeyParameters.KEY_SIZE) {
                throw CryptoEngine.Failure.InvalidKeyLength(Ed25519PrivateKeyParameters.KEY_SIZE, raw.size)
            }
            return Ed25519PrivateKeyParameters(raw, 0)
        }
    }
}

/** Custode delle chiavi di identita': le genera al primo avvio e le persiste nel [SecretStore]. */
class IdentityStore(private val keychain: SecretStore) {

    object Account {
        const val x25519 = "identity.x25519.private"
        const val ed25519 = "identity.ed25519.private"
    }

    private val mutex = Mutex()
    private var cached: IdentityKeys? = null

    /** Restituisce le chiavi esistenti o ne genera di nuove (onboarding zero-account). */
    // Keystore (binder) e file: sempre fuori dal main thread, il primo avvio genera anche la chiave AES.
    suspend fun loadOrCreateKeys(): IdentityKeys = mutex.withLock {
        cached?.let { return@withLock it }
        withContext(Dispatchers.IO) { loadOrCreateLocked() }
    }

    private fun loadOrCreateLocked(): IdentityKeys {
        cached?.let { return it }

        val x25519 = keychain.load(Account.x25519)
        val ed25519 = keychain.load(Account.ed25519)
        if (x25519 != null && ed25519 != null) {
            val keys = IdentityKeys(x25519PrivateRaw = x25519, ed25519PrivateRaw = ed25519)
            cached = keys
            return keys
        }

        val keys = IdentityKeys.generate()
        keychain.save(keys.x25519PrivateKeyRaw, Account.x25519)
        keychain.save(keys.ed25519PrivateKeyRaw, Account.ed25519)
        cached = keys
        return keys
    }

    /** `true` se nello store esiste gia' un'identita'. */
    suspend fun hasStoredIdentity(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            keychain.load(Account.x25519) != null && keychain.load(Account.ed25519) != null
        }
    }

    /** Cancella l'identita' (reset app). Le conversazioni cifrate con la vecchia chiave diventano illeggibili. */
    suspend fun reset(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            keychain.delete(Account.x25519)
            keychain.delete(Account.ed25519)
        }
        cached = null
    }
}
