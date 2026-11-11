package com.stefanobrandi.aerochat.core.media

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Encoder/decoder BlurHash (https://blurha.sh) – 4x3 componenti => 28 caratteri ASCII,
 * inviati nell'header del messaggio per l'anteprima istantanea prima dei chunk WebP.
 *
 * Porting 1:1 dell'algoritmo iOS: a parita' di pixel RGBA produce la stessa stringa.
 * Si usa `StrictMath` (fdlibm) per `cos`/`pow`, cosi' il risultato e' identico su ogni dispositivo Android.
 */
object BlurHash {

    sealed class Failure(message: String) : Exception(message) {
        data object invalidComponents : Failure("invalidComponents")
        data object invalidImage : Failure("invalidImage")
        data object invalidHash : Failure("invalidHash")
    }

    /** Colore medio sRGB 0...1 (tupla `(red, green, blue)` su iOS). */
    data class AverageColor(val red: Double, val green: Double, val blue: Double)

    const val defaultComponentsX = 4
    const val defaultComponentsY = 3

    private const val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"
    private val alphabetIndex: Map<Char, Int> = alphabet.withIndex().associate { it.value to it.index }

    /** Tabella sRGB -> lineare per evitare pow() per pixel. */
    private val linearTable: DoubleArray by lazy { DoubleArray(256) { sRGBToLinear(it.toDouble() / 255) } }

    // MARK: - Encode

    /** Calcola il BlurHash di un'immagine. L'immagine viene prima ridotta (max 32 px) per velocita'. */
    fun encode(image: Bitmap, componentsX: Int = defaultComponentsX, componentsY: Int = defaultComponentsY): String {
        if (componentsX !in 1..9 || componentsY !in 1..9) throw Failure.invalidComponents
        val pixels = RGBAPixels(image, maxSide = 32)
        return encode(pixels.data, pixels.width, pixels.height, componentsX, componentsY)
    }

    /** Encode su buffer RGBA (4 byte per pixel, riga per riga). */
    fun encode(pixels: ByteArray, width: Int, height: Int, componentsX: Int, componentsY: Int): String {
        if (componentsX !in 1..9 || componentsY !in 1..9) throw Failure.invalidComponents
        if (width <= 0 || height <= 0 || pixels.size < width * height * 4) throw Failure.invalidImage

        val table = linearTable
        val linear = DoubleArray(width * height * 3)
        for (i in 0 until width * height) {
            linear[i * 3] = table[pixels[i * 4].toInt() and 0xFF]
            linear[i * 3 + 1] = table[pixels[i * 4 + 1].toInt() and 0xFF]
            linear[i * 3 + 2] = table[pixels[i * 4 + 2].toInt() and 0xFF]
        }

        val factors = ArrayList<DoubleArray>(componentsX * componentsY)
        for (y in 0 until componentsY) {
            for (x in 0 until componentsX) {
                val normalisation: Double = if (x == 0 && y == 0) 1.0 else 2.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (py in 0 until height) {
                    val cosY = StrictMath.cos(Math.PI * y.toDouble() * py.toDouble() / height.toDouble())
                    for (px in 0 until width) {
                        val basis = normalisation * StrictMath.cos(Math.PI * x.toDouble() * px.toDouble() / width.toDouble()) * cosY
                        val index = (py * width + px) * 3
                        r += basis * linear[index]
                        g += basis * linear[index + 1]
                        b += basis * linear[index + 2]
                    }
                }
                val scale = 1 / (width * height).toDouble()
                factors.add(doubleArrayOf(r * scale, g * scale, b * scale))
            }
        }

        val dc = factors[0]
        val ac = factors.drop(1)

        val hash = StringBuilder(encode83((componentsX - 1) + (componentsY - 1) * 9, 1))
        val maximumValue: Double
        if (ac.isNotEmpty()) {
            val actualMaximum = ac.maxOf { max(max(abs(it[0]), abs(it[1])), abs(it[2])) }
            val quantised = max(0.0, min(82.0, floor(actualMaximum * 166 - 0.5))).toInt()
            maximumValue = (quantised + 1).toDouble() / 166
            hash.append(encode83(quantised, 1))
        } else {
            maximumValue = 1.0
            hash.append(encode83(0, 1))
        }
        hash.append(encode83(encodeDC(dc), 4))
        for (factor in ac) {
            hash.append(encode83(encodeAC(factor, maximumValue), 2))
        }
        return hash.toString()
    }

    // MARK: - Decode

    /** Decodifica in un buffer RGBA `width x height` (tipicamente 32x32, poi scalato dalla UI). */
    fun decode(hash: String, width: Int, height: Int, punch: Double = 1.0): ByteArray {
        if (hash.length < 6) throw Failure.invalidHash
        val sizeFlag = decode83(hash, 0, 1)
        val componentsY = sizeFlag / 9 + 1
        val componentsX = sizeFlag % 9 + 1
        if (hash.length != 4 + 2 * componentsX * componentsY) throw Failure.invalidHash

        val quantisedMaximum = decode83(hash, 1, 2)
        val maximumValue = (quantisedMaximum + 1).toDouble() / 166

        val colors = ArrayList<DoubleArray>(componentsX * componentsY)
        for (i in 0 until componentsX * componentsY) {
            if (i == 0) {
                colors.add(decodeDC(decode83(hash, 2, 6)))
            } else {
                val start = 4 + i * 2
                colors.add(decodeAC(decode83(hash, start, start + 2), maximumValue * punch))
            }
        }

        val pixels = ByteArray(width * height * 4) { 255.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (j in 0 until componentsY) {
                    val cosY = StrictMath.cos(Math.PI * y.toDouble() * j.toDouble() / height.toDouble())
                    for (i in 0 until componentsX) {
                        val basis = StrictMath.cos(Math.PI * x.toDouble() * i.toDouble() / width.toDouble()) * cosY
                        val color = colors[i + j * componentsX]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                val index = (y * width + x) * 4
                pixels[index] = linearToSRGB(r).toByte()
                pixels[index + 1] = linearToSRGB(g).toByte()
                pixels[index + 2] = linearToSRGB(b).toByte()
            }
        }
        return pixels
    }

    /** Colore medio (DC) del BlurHash come componenti sRGB 0...1. */
    fun averageColor(hash: String): AverageColor {
        if (hash.length < 6) throw Failure.invalidHash
        val dc = decodeDC(decode83(hash, 2, 6))
        return AverageColor(
            linearToSRGB(dc[0]).toDouble() / 255,
            linearToSRGB(dc[1]).toDouble() / 255,
            linearToSRGB(dc[2]).toDouble() / 255,
        )
    }

    /** `Bitmap` ARGB_8888 opaca dal BlurHash (per la UI Compose). */
    fun image(hash: String, width: Int = 32, height: Int = 32, punch: Double = 1.0): Bitmap? {
        val pixels = try {
            decode(hash, width, height, punch)
        } catch (_: Failure) {
            return null
        }
        return RGBAPixels.makeImage(pixels, width, height)
    }

    fun isValid(hash: String): Boolean = try {
        decode(hash, 1, 1)
        true
    } catch (_: Failure) {
        false
    }

    // MARK: - Base 83 e colori

    private fun encode83(value: Int, length: Int): String {
        val result = StringBuilder(length)
        for (i in 1..length) {
            var divisor = 1
            repeat(length - i) { divisor *= 83 }
            val digit = (value / divisor) % 83
            result.append(alphabet[digit])
        }
        return result.toString()
    }

    /** Decodifica i caratteri `[start, end)` di `hash`. */
    private fun decode83(hash: String, start: Int, end: Int): Int {
        var value = 0
        for (index in start until end) {
            val digit = alphabetIndex[hash[index]] ?: throw Failure.invalidHash
            value = value * 83 + digit
        }
        return value
    }

    private fun encodeDC(value: DoubleArray): Int =
        (linearToSRGB(value[0]) shl 16) + (linearToSRGB(value[1]) shl 8) + linearToSRGB(value[2])

    private fun decodeDC(value: Int): DoubleArray = doubleArrayOf(
        sRGBToLinear((value shr 16).toDouble() / 255),
        sRGBToLinear(((value shr 8) and 255).toDouble() / 255),
        sRGBToLinear((value and 255).toDouble() / 255),
    )

    private fun encodeAC(value: DoubleArray, maximumValue: Double): Int {
        fun quantise(component: Double): Int =
            max(0.0, min(18.0, floor(signPow(component / maximumValue, 0.5) * 9 + 9.5))).toInt()
        return quantise(value[0]) * 19 * 19 + quantise(value[1]) * 19 + quantise(value[2])
    }

    private fun decodeAC(value: Int, maximumValue: Double): DoubleArray {
        val quantR = value / (19 * 19)
        val quantG = (value / 19) % 19
        val quantB = value % 19
        return doubleArrayOf(
            signPow((quantR.toDouble() - 9) / 9, 2.0) * maximumValue,
            signPow((quantG.toDouble() - 9) / 9, 2.0) * maximumValue,
            signPow((quantB.toDouble() - 9) / 9, 2.0) * maximumValue,
        )
    }

    private fun signPow(value: Double, exponent: Double): Double =
        Math.copySign(StrictMath.pow(abs(value), exponent), value)

    private fun sRGBToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else StrictMath.pow((value + 0.055) / 1.055, 2.4)

    /** `UInt8` su iOS: valore 0...255. */
    private fun linearToSRGB(value: Double): Int {
        val v = max(0.0, min(1.0, value))
        val srgb = if (v <= 0.0031308) v * 12.92 else 1.055 * StrictMath.pow(v, 1 / 2.4) - 0.055
        return max(0.0, min(255.0, floor(srgb * 255 + 0.5))).toInt()
    }
}

/**
 * Buffer RGBA 8 bit (non premoltiplicato) estratto da una `Bitmap` (opzionalmente ridotta a `maxSide`).
 */
class RGBAPixels(image: Bitmap, maxSide: Int? = null) {
    val width: Int
    val height: Int
    val data: ByteArray

    init {
        var width = image.width
        var height = image.height
        if (width <= 0 || height <= 0) throw BlurHash.Failure.invalidImage
        if (maxSide != null && max(width, height) > maxSide) {
            val scale = maxSide.toDouble() / max(width, height).toDouble()
            width = max(1, (width.toDouble() * scale).toInt())
            height = max(1, (height.toDouble() * scale).toInt())
        }
        val source = if (image.config == Bitmap.Config.HARDWARE) {
            image.copy(Bitmap.Config.ARGB_8888, false) ?: throw BlurHash.Failure.invalidImage
        } else {
            image
        }
        val scaled = if (width != source.width || height != source.height) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else {
            source
        }
        val argb = IntArray(width * height)
        scaled.getPixels(argb, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        if (source !== image) source.recycle()
        val buffer = ByteArray(width * height * 4)
        for (i in argb.indices) {
            val pixel = argb[i]
            val alpha = pixel ushr 24
            var r = (pixel shr 16) and 0xFF
            var g = (pixel shr 8) and 0xFF
            var b = pixel and 0xFF
            if (alpha != 255) {
                // iOS disegna in un contesto `premultipliedLast`: stesso risultato per le immagini trasparenti.
                r = (r * alpha + 127) / 255
                g = (g * alpha + 127) / 255
                b = (b * alpha + 127) / 255
            }
            buffer[i * 4] = r.toByte()
            buffer[i * 4 + 1] = g.toByte()
            buffer[i * 4 + 2] = b.toByte()
            buffer[i * 4 + 3] = alpha.toByte()
        }
        this.width = width
        this.height = height
        this.data = buffer
    }

    companion object {
        /** Bitmap opaca ARGB_8888 da un buffer RGBA (l'alfa viene ignorato, come `noneSkipLast` su iOS). */
        fun makeImage(pixels: ByteArray, width: Int, height: Int): Bitmap? {
            if (width <= 0 || height <= 0 || pixels.size < width * height * 4) return null
            val argb = IntArray(width * height) { i ->
                (0xFF shl 24) or
                    ((pixels[i * 4].toInt() and 0xFF) shl 16) or
                    ((pixels[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    (pixels[i * 4 + 2].toInt() and 0xFF)
            }
            return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
