package com.example.aerochat.data.records

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.example.aerochat.core.crypto.IdentityKeys

/** Livello Pro cache-ato localmente (verificato offline tramite ricevuta dello store). */
enum class ProTier(val rawValue: String) {
    free("free"),
    pass72h("pass_72h"),
    lifetime("lifetime");

    companion object {
        fun fromRawValue(rawValue: String): ProTier? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Riga unica (`id = 1`) con l'identita' locale. */
@Entity(tableName = IdentityRecord.databaseTableName)
data class IdentityRecord(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long = singletonID,
    @ColumnInfo(name = "node_id")
    val nodeID: ByteArray,
    @ColumnInfo(name = "x25519_pub")
    val x25519Pub: ByteArray,
    @ColumnInfo(name = "ed25519_pub")
    val ed25519Pub: ByteArray,
    @ColumnInfo(name = "nickname")
    val nickname: String,
    @ColumnInfo(name = "avatar_color_hex")
    val avatarColorHex: String,
    @ColumnInfo(name = "pro_tier", defaultValue = "free")
    val proTier: ProTier = ProTier.free,
    @ColumnInfo(name = "pro_valid_until_ms")
    val proValidUntilMs: Long? = null,
    @ColumnInfo(name = "pro_receipt_jws")
    val proReceiptJWS: String? = null,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    /** Foto profilo WebP 128x128 condivisa con i contatti (null = solo iniziale su colore). */
    @ColumnInfo(name = "avatar_image")
    val avatarImage: ByteArray? = null,
    /** Versione del profilo pubblico (nickname, colore, foto) annunciata con `PROFILE_UPDATE`. */
    @ColumnInfo(name = "profile_updated_at_ms")
    val profileUpdatedAtMs: Long? = null,
) {

    /**
     * Crea il record dall'identita' crittografica e dal nickname scelto nell'onboarding.
     * @param createdAt epoch ms.
     */
    @Ignore
    constructor(
        identity: IdentityKeys,
        nickname: String,
        avatarColorHex: String,
        createdAt: Long = System.currentTimeMillis(),
    ) : this(
        id = singletonID,
        nodeID = identity.nodeID,
        x25519Pub = identity.x25519PublicKeyRaw,
        ed25519Pub = identity.ed25519PublicKeyRaw,
        nickname = nickname,
        avatarColorHex = avatarColorHex,
        proTier = ProTier.free,
        proValidUntilMs = null,
        proReceiptJWS = null,
        createdAtMs = createdAt,
        avatarImage = null,
        profileUpdatedAtMs = createdAt,
    )

    /** `true` se l'utente e' Pro in questo istante (lifetime o pass non scaduto). `now` in epoch ms. */
    fun isPro(now: Long = System.currentTimeMillis()): Boolean = when (proTier) {
        ProTier.lifetime -> true
        ProTier.pass72h -> (proValidUntilMs ?: 0L) > now
        ProTier.free -> false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityRecord) return false
        return id == other.id &&
            nodeID.contentEquals(other.nodeID) &&
            x25519Pub.contentEquals(other.x25519Pub) &&
            ed25519Pub.contentEquals(other.ed25519Pub) &&
            nickname == other.nickname &&
            avatarColorHex == other.avatarColorHex &&
            proTier == other.proTier &&
            proValidUntilMs == other.proValidUntilMs &&
            proReceiptJWS == other.proReceiptJWS &&
            createdAtMs == other.createdAtMs &&
            avatarImage.contentEquals(other.avatarImage) &&
            profileUpdatedAtMs == other.profileUpdatedAtMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + nodeID.contentHashCode()
        result = 31 * result + x25519Pub.contentHashCode()
        result = 31 * result + ed25519Pub.contentHashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + avatarColorHex.hashCode()
        result = 31 * result + proTier.hashCode()
        result = 31 * result + (proValidUntilMs?.hashCode() ?: 0)
        result = 31 * result + (proReceiptJWS?.hashCode() ?: 0)
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + avatarImage.contentHashCode()
        result = 31 * result + (profileUpdatedAtMs?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val databaseTableName = "identity"
        const val singletonID: Long = 1L
    }
}
