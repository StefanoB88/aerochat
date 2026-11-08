package com.example.aerochat.core.crypto

import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.base64URLDecoded
import com.example.aerochat.core.protocol.base64URLEncodedString
import com.example.aerochat.core.protocol.uuidFromString
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID

/**
 * Contenuto del QR Code di pairing / catena gruppi.
 *
 * Formato URI:
 * `aeromesh://v1?x=<base64url_x25519_32B>&e=<base64url_ed25519_32B>&n=<url_encoded_nick>&g=<optional_group_uuid>&gk=<optional_base64url_group_key>&gt=<optional_url_encoded_group_title>`
 *
 * `gt` e' opzionale e retrocompatibile: chi non lo conosce lo ignora e riceve il titolo dal Group Key Gossip.
 */
class PairingPayload(
    x25519PublicKeyRaw: ByteArray,
    ed25519PublicKeyRaw: ByteArray,
    val nickname: String,
    val groupID: UUID? = null,
    groupSecretKey: ByteArray? = null,
    groupTitle: String? = null,
) {

    sealed class Failure(message: String) : Exception(message) {
        data object InvalidScheme : Failure("invalid scheme") {
            private fun readResolve(): Any = InvalidScheme
        }
        data object UnsupportedVersion : Failure("unsupported version") {
            private fun readResolve(): Any = UnsupportedVersion
        }
        data class MissingField(val field: String) : Failure("missing field $field")
        data class InvalidField(val field: String) : Failure("invalid field $field")
    }

    private val x25519: ByteArray = x25519PublicKeyRaw.copyOf()
    private val ed25519: ByteArray = ed25519PublicKeyRaw.copyOf()
    private val groupKey: ByteArray? = groupSecretKey?.copyOf()

    val x25519PublicKeyRaw: ByteArray get() = x25519.copyOf()
    val ed25519PublicKeyRaw: ByteArray get() = ed25519.copyOf()
    val groupSecretKey: ByteArray? get() = groupKey?.copyOf()

    /** Nome del gruppo di viaggio, cosi' chi scansiona lo vede subito (senza attendere il gossip). */
    val groupTitle: String? = groupTitle
        ?.let { trimWhitespacesAndNewlines(it) }
        ?.let { if (it.isEmpty()) null else prefixCharacters(it, maxGroupTitleLength) }

    /** Payload personale a partire dall'identita' locale. */
    constructor(
        identity: IdentityKeys,
        nickname: String,
        groupID: UUID? = null,
        groupSecretKey: ByteArray? = null,
        groupTitle: String? = null,
    ) : this(identity.x25519PublicKeyRaw, identity.ed25519PublicKeyRaw, nickname, groupID, groupSecretKey, groupTitle)

    /** Parsing di un URI `aeromesh://v1?...` (Swift `init(uriString:)`). Lancia [Failure]. */
    @Throws(Failure::class)
    constructor(uriString: String) : this(parse(uriString))

    private constructor(other: PairingPayload) : this(
        other.x25519, other.ed25519, other.nickname, other.groupID, other.groupKey, other.groupTitle,
    )

    /** `node_id` derivato dalla chiave X25519 contenuta nel QR. */
    val nodeID: ByteArray get() = CryptoEngine.nodeID(forX25519PublicKey = x25519)

    /** `true` se il QR trasporta anche l'invito a un gruppo di viaggio. */
    val isGroupInvite: Boolean get() = groupID != null && groupKey != null

    // MARK: - Encoding

    val uriString: String
        get() {
            val items = mutableListOf(
                "x" to x25519.base64URLEncodedString(),
                "e" to ed25519.base64URLEncodedString(),
                "n" to percentEncode(nickname),
            )
            groupID?.let { items.add("g" to it.toString().lowercase(Locale.ROOT)) }
            groupKey?.let { items.add("gk" to it.base64URLEncodedString()) }
            groupTitle?.let { items.add("gt" to percentEncode(it)) }
            val query = items.joinToString("&") { "${it.first}=${it.second}" }
            return "${MeshProtocol.qrScheme}://${MeshProtocol.qrVersion}?$query"
        }

    /** Swift `var url: URL?`: `null` se [uriString] non e' un URI sintatticamente valido. */
    val url: URI?
        get() = try {
            URI(uriString)
        } catch (_: URISyntaxException) {
            null
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingPayload) return false
        return x25519.contentEquals(other.x25519) &&
            ed25519.contentEquals(other.ed25519) &&
            nickname == other.nickname &&
            groupID == other.groupID &&
            (groupKey?.let { k -> other.groupKey?.contentEquals(k) ?: false } ?: (other.groupKey == null)) &&
            groupTitle == other.groupTitle
    }

    override fun hashCode(): Int {
        var result = x25519.contentHashCode()
        result = 31 * result + ed25519.contentHashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + (groupID?.hashCode() ?: 0)
        result = 31 * result + (groupKey?.contentHashCode() ?: 0)
        result = 31 * result + (groupTitle?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "PairingPayload($uriString)"

    companion object {
        /** Lunghezza massima del titolo nel QR (in caratteri/grafemi, come `String.prefix` di Swift). */
        const val maxGroupTitleLength = 64

        private fun parse(uriString: String): PairingPayload {
            val trimmed = trimWhitespacesAndNewlines(uriString)
            val prefix = "${MeshProtocol.qrScheme}://"
            if (!trimmed.lowercase(Locale.ROOT).startsWith(prefix)) throw Failure.InvalidScheme

            val afterScheme = trimmed.substring(prefix.length)
            val parts = afterScheme.split("?", limit = 2)
            val version = parts.first()
            if (version.lowercase(Locale.ROOT) != MeshProtocol.qrVersion) throw Failure.UnsupportedVersion
            if (parts.size != 2) throw Failure.MissingField("query")

            val fields = mutableMapOf<String, String>()
            for (pair in parts[1].split("&")) {
                if (pair.isEmpty()) continue
                val kv = pair.split("=", limit = 2)
                if (kv.size != 2) continue
                fields[kv[0]] = kv[1]
            }

            val xRaw = fields["x"] ?: throw Failure.MissingField("x")
            val x = xRaw.base64URLDecoded()?.takeIf { it.size == MeshProtocol.publicKeyLength }
                ?: throw Failure.InvalidField("x")
            val eRaw = fields["e"] ?: throw Failure.MissingField("e")
            val e = eRaw.base64URLDecoded()?.takeIf { it.size == MeshProtocol.publicKeyLength }
                ?: throw Failure.InvalidField("e")
            val nRaw = fields["n"] ?: throw Failure.MissingField("n")
            // `URLEncoder` su Android codifica lo spazio come "+": lo normalizziamo prima del percent-decoding.
            val nickname = percentDecode(nRaw.replace("+", " "))?.takeIf { it.isNotEmpty() }
                ?: throw Failure.InvalidField("n")

            var groupID: UUID? = null
            val gRaw = fields["g"]
            if (!gRaw.isNullOrEmpty()) {
                groupID = uuidFromString(gRaw) ?: throw Failure.InvalidField("g")
            }
            var groupSecretKey: ByteArray? = null
            val gkRaw = fields["gk"]
            if (!gkRaw.isNullOrEmpty()) {
                groupSecretKey = gkRaw.base64URLDecoded()?.takeIf { it.size == MeshProtocol.symmetricKeyLength }
                    ?: throw Failure.InvalidField("gk")
            }
            // Titolo facoltativo: se non decodificabile si ignora (il gossip lo correggera').
            val groupTitle = fields["gt"]?.let { percentDecode(it.replace("+", " ")) }

            return PairingPayload(x, e, nickname, groupID, groupSecretKey, groupTitle)
        }

        // MARK: - Percent encoding compatibile con Foundation

        /**
         * Come `addingPercentEncoding(withAllowedCharacters: .alphanumerics + "-._~")` di Foundation:
         * restano in chiaro gli scalari Unicode delle categorie L*, M*, N* (quindi anche lettere
         * accentate, CJK, ecc.) e `-._~`; tutto il resto (spazio, emoji, punteggiatura) diventa
         * `%XX` maiuscolo sui byte UTF-8.
         */
        internal fun percentEncode(string: String): String {
            val out = StringBuilder(string.length)
            var i = 0
            while (i < string.length) {
                val cp = string.codePointAt(i)
                val count = Character.charCount(cp)
                if (isAllowed(cp)) {
                    out.appendCodePoint(cp)
                } else {
                    for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                        out.append('%')
                        out.append(HEX_UPPER[(b.toInt() shr 4) and 0x0F])
                        out.append(HEX_UPPER[b.toInt() and 0x0F])
                    }
                }
                i += count
            }
            return out.toString()
        }

        private const val HEX_UPPER = "0123456789ABCDEF"

        private fun isAllowed(cp: Int): Boolean {
            if (cp == '-'.code || cp == '.'.code || cp == '_'.code || cp == '~'.code) return true
            return when (Character.getType(cp).toByte()) {
                Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                Character.MODIFIER_LETTER, Character.OTHER_LETTER,
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
                else -> false
            }
        }

        /**
         * Come `removingPercentEncoding` di Foundation: `null` se una sequenza `%` non e' seguita da due
         * cifre esadecimali o se i byte risultanti non sono UTF-8 valido.
         */
        internal fun percentDecode(string: String): String? {
            val bytes = ByteArrayOutputStream(string.length)
            var i = 0
            while (i < string.length) {
                val c = string[i]
                if (c == '%') {
                    if (i + 2 >= string.length) return null
                    if (!isAsciiHex(string[i + 1]) || !isAsciiHex(string[i + 2])) return null
                    val high = Character.digit(string[i + 1], 16)
                    val low = Character.digit(string[i + 2], 16)
                    bytes.write((high shl 4) or low)
                    i += 3
                } else {
                    val cp = string.codePointAt(i)
                    val encoded = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    bytes.write(encoded, 0, encoded.size)
                    i += Character.charCount(cp)
                }
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }

        private fun isAsciiHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        /** `trimmingCharacters(in: .whitespacesAndNewlines)`. */
        internal fun trimWhitespacesAndNewlines(string: String): String = string.trim { it.isWhitespace() }

        /** `String.prefix(n)` di Swift: primi [n] grafemi (cluster di caratteri estesi). */
        internal fun prefixCharacters(string: String, n: Int): String {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(string)
            var end = 0
            for (k in 0 until n) {
                val next = iterator.next()
                if (next == BreakIterator.DONE) return string
                end = next
            }
            return string.substring(0, end)
        }
    }
}
