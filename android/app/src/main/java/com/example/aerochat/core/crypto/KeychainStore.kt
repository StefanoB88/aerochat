package com.example.aerochat.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Archivio minimale di segreti (chiavi private raw), con la stessa forma del `KeychainStore` iOS. */
interface SecretStore {
    /** Salva (o sovrascrive) [data] per [account]. */
    @Throws(KeychainStore.Failure::class)
    fun save(data: ByteArray, account: String)

    /** Restituisce i byte salvati per [account], oppure `null` se assenti. */
    @Throws(KeychainStore.Failure::class)
    fun load(account: String): ByteArray?

    /** Elimina l'item (nessun errore se non esiste). */
    @Throws(KeychainStore.Failure::class)
    fun delete(account: String)
}

/** Implementazione in memoria (test e anteprime). */
class InMemorySecretStore : SecretStore {
    private val items = ConcurrentHashMap<String, ByteArray>()
    override fun save(data: ByteArray, account: String) { items[account] = data.copyOf() }
    override fun load(account: String): ByteArray? = items[account]?.copyOf()
    override fun delete(account: String) { items.remove(account) }
}

/**
 * Segreti dell'app cifrati con AES-256-GCM. La chiave AES vive nell'Android Keystore (non esportabile)
 * con alias [keyAlias]; i blob cifrati `[12B IV][ciphertext||tag]` sono salvati in
 * `noBackupFilesDir/<service>/` (esclusa dal backup, come `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`:
 * il segreto non viene mai migrato su un altro dispositivo). L'account e' usato come AAD.
 */
class KeychainStore(
    context: Context,
    val service: String = "com.aeromesh.identity",
    private val keyAlias: String = "aerochat.identity",
) : SecretStore {

    sealed class Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class UnexpectedStatus(val status: String, cause: Throwable? = null) : Failure("unexpected status: $status", cause)
        data object UnexpectedItemType : Failure("unexpected item type") {
            private fun readResolve(): Any = UnexpectedItemType
        }
    }

    private val directory = File(context.applicationContext.noBackupFilesDir, sanitize(service))
    private val lock = Any()

    override fun save(data: ByteArray, account: String) = synchronized(lock) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(create = true))
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            if (iv.size != IV_LENGTH) throw Failure.UnexpectedStatus("iv length ${iv.size}")
            val sealed = iv + cipher.doFinal(data)

            if (!directory.exists() && !directory.mkdirs()) throw Failure.UnexpectedStatus("mkdirs")
            val file = AtomicFile(fileFor(account))
            val stream = file.startWrite()
            try {
                stream.write(sealed)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            throw Failure.UnexpectedStatus(e.javaClass.simpleName, e)
        }
    }

    override fun load(account: String): ByteArray? = synchronized(lock) {
        val sealed = try {
            AtomicFile(fileFor(account)).readFully()
        } catch (_: FileNotFoundException) {
            return null
        } catch (e: Exception) {
            throw Failure.UnexpectedStatus(e.javaClass.simpleName, e)
        }
        if (sealed.size <= IV_LENGTH + TAG_LENGTH_BYTES) throw Failure.UnexpectedItemType
        try {
            // Chiave assente (es. dati ripristinati senza la chiave del Keystore): l'item e' orfano.
            val key = secretKey(create = false) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BYTES * 8, sealed, 0, IV_LENGTH))
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
        } catch (e: GeneralSecurityException) {
            throw Failure.UnexpectedStatus(e.javaClass.simpleName, e)
        }
    }

    override fun delete(account: String) = synchronized(lock) {
        try {
            AtomicFile(fileFor(account)).delete()
        } catch (e: Exception) {
            throw Failure.UnexpectedStatus(e.javaClass.simpleName, e)
        }
    }

    private fun fileFor(account: String): File = File(directory, sanitize(account) + ".bin")

    private fun secretKey(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BYTES = 16

        fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
