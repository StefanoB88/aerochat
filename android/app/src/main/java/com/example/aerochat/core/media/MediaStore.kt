package com.example.aerochat.core.media

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Posizione su disco dei media ricevuti/inviati (`context.filesDir/media`; su iOS
 * `Application Support/AeroMesh/Media`). Il path relativo salvato in `media_local_path` e' lo stesso
 * schema iOS: solo il nome file `<mediaID sanificato>.<webp|opus>`.
 *
 * Da non confondere con `android.provider.MediaStore` (galleria di sistema, vedi [PhotoLibrarySaver]).
 */
data class MediaStore(val directory: File) {

    enum class Kind(val rawValue: String) {
        image("image"),
        audio("audio");

        val fileExtension: String
            get() = when (this) {
                image -> "webp"
                audio -> "opus"
            }

        val mimeType: String
            get() = when (this) {
                image -> webpMimeType
                audio -> opusMimeType
            }

        companion object {
            /** `Kind(mimeType:)` su iOS: `null` se il MIME type non e' supportato. */
            operator fun invoke(mimeType: String): Kind? = when (mimeType.lowercase()) {
                webpMimeType -> image
                opusMimeType, "audio/ogg", "audio/ogg; codecs=opus" -> audio
                else -> null
            }
        }
    }

    /** `url(mediaID:kind:)` su iOS. */
    fun url(mediaID: String, kind: Kind): File = File(directory, "${sanitize(mediaID)}.${kind.fileExtension}")

    /** Scrive atomicamente e ritorna il path relativo alla directory media (salvato in `media_local_path`). */
    fun write(data: ByteArray, mediaID: String, kind: Kind): String {
        val target = url(mediaID, kind)
        directory.mkdirs()
        val temp = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(data)
                stream.fd.sync()
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("rename fallito: ${target.path}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        return target.name
    }

    fun read(relativePath: String): ByteArray = absoluteURL(relativePath).readBytes()

    fun absoluteURL(relativePath: String): File = File(directory, relativePath)

    fun exists(relativePath: String): Boolean = absoluteURL(relativePath).exists()

    /** Elimina il file (es. quando si cancella la chat); un file gia' assente non e' un errore. */
    fun remove(relativePath: String) {
        absoluteURL(relativePath).delete()
    }

    companion object {
        const val webpMimeType = "image/webp"
        const val opusMimeType = "audio/opus"

        fun makeShared(context: Context): MediaStore {
            val directory = File(context.filesDir, "media")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("impossibile creare ${directory.path}")
            return MediaStore(directory)
        }

        /** Directory temporanea univoca (`java.io.tmpdir`, cioe' `cacheDir` su Android). */
        fun makeTemporary(): MediaStore {
            val base = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            val directory = File(base, "AeroMeshMedia-${UUID.randomUUID().toString().uppercase()}")
            if (!directory.mkdirs()) throw IOException("impossibile creare ${directory.path}")
            return MediaStore(directory)
        }

        /** Come `CharacterSet.alphanumerics` (lettere, segni, numeri Unicode) + `-` e `_`; il resto diventa `_`. */
        private fun sanitize(mediaID: String): String {
            val out = StringBuilder(mediaID.length)
            var index = 0
            while (index < mediaID.length) {
                val codePoint = mediaID.codePointAt(index)
                if (isAllowed(codePoint)) out.appendCodePoint(codePoint) else out.append('_')
                index += Character.charCount(codePoint)
            }
            return out.toString()
        }

        private fun isAllowed(codePoint: Int): Boolean {
            if (codePoint == '-'.code || codePoint == '_'.code) return true
            return when (Character.getType(codePoint).toByte()) {
                Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                Character.MODIFIER_LETTER, Character.OTHER_LETTER,
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
                else -> false
            }
        }
    }
}
