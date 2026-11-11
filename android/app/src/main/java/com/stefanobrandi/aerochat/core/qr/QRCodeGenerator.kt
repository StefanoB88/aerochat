package com.stefanobrandi.aerochat.core.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import com.stefanobrandi.aerochat.core.crypto.PairingPayload
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Genera QR Code (pairing personale, invito gruppo, link agli store) con ZXing. */
object QRCodeGenerator {

    /** Link mostrato al gate finche' c'e' ancora rete: punta alla pagina con i badge degli store. */
    const val storeLandingURL = "https://aeromesh.app/get"

    /** I QR gia' generati restano in cache (la stessa stringa viene richiesta a ogni ricomposizione). */
    private val cache = LruCache<String, Bitmap>(16)

    /**
     * @param scale pixel per modulo (come la `CGAffineTransform` su iOS).
     * @param correctionLevel "L", "M", "Q" o "H".
     * @return bitmap con moduli neri su sfondo bianco, o `null` se la stringa non e' codificabile.
     */
    fun image(string: String, scale: Int = 12, correctionLevel: String = "M"): Bitmap? {
        val key = "$correctionLevel|$scale|$string"
        cache.get(key)?.let { return it }
        val level = when (correctionLevel.uppercase()) {
            "L" -> ErrorCorrectionLevel.L
            "Q" -> ErrorCorrectionLevel.Q
            "H" -> ErrorCorrectionLevel.H
            else -> ErrorCorrectionLevel.M
        }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to level,
            // UTF-8 come `Data(string.utf8)` su iOS.
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // CIQRCodeGenerator lascia un bordo di 1 modulo.
            EncodeHintType.MARGIN to 1,
        )
        val matrix = try {
            // Dimensione 0: ZXing restituisce la matrice con 1 pixel per modulo; scaliamo noi.
            QRCodeWriter().encode(string, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (_: WriterException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val s = scale.coerceAtLeast(1)
        val width = matrix.width * s
        val height = matrix.height * s
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val my = y / s
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x / s, my)) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        cache.put(key, bitmap)
        return bitmap
    }

    fun pairingImage(payload: PairingPayload, scale: Int = 12): Bitmap? = image(payload.uriString, scale)
}
