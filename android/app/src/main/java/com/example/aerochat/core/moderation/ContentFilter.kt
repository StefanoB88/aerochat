package com.example.aerochat.core.moderation

import java.text.Normalizer
import java.util.Locale

/**
 * Filtro linguaggio della Cabina Pubblica: la bacheca e' tra sconosciuti, quindi le parole piu' offensive
 * vengono rifiutate in invio e mascherate in ricezione (`c***`). Il confronto e' per parola intera,
 * senza accenti e maiuscole, nelle lingue dell'app. Le chat private non vengono filtrate.
 */
object ContentFilter {

    /**
     * Vocabolario minimo multilingue (it, en, de, fr, es, pt, nl, pl, sv, da, nb): solo insulti e volgarita'
     * forti, niente parole ambigue che bloccherebbero conversazioni normali. Copia esatta della lista iOS.
     */
    val blockedWords: Set<String> = setOf(
        // Italiano
        "cazzo", "cazzi", "stronzo", "stronza", "stronzi", "stronze", "merda", "merde", "vaffanculo", "fanculo",
        "coglione", "coglioni", "troia", "troie", "puttana", "puttane", "figa", "fica", "minchia", "porcodio",
        "porcamadonna", "bastardo", "bastarda", "bastardi", "frocio", "froci", "negro", "negri", "culo",
        // English
        "fuck", "fucking", "fucked", "fucker", "motherfucker", "shit", "bullshit", "asshole", "bitch", "bitches",
        "cunt", "dick", "dickhead", "pussy", "whore", "slut", "faggot", "nigger", "nigga", "retard", "wanker",
        // Deutsch
        "scheisse", "scheiss", "arschloch", "fotze", "hurensohn", "wichser", "schlampe", "ficken", "fick",
        // Français
        "putain", "merde", "connard", "connasse", "salope", "enculé", "encule", "nique", "niquer", "pute", "batard",
        // Español
        "mierda", "puta", "puto", "gilipollas", "cabron", "cabrón", "joder", "coño", "cono", "maricon", "maricón",
        "pendejo", "hijueputa",
        // Português
        "caralho", "porra", "foda", "foder", "filho da puta", "viado", "buceta", "cuzão", "cuzao",
        // Nederlands
        "kanker", "kut", "klootzak", "tering", "hoer", "neuken", "godverdomme", "flikker",
        // Polski
        "kurwa", "chuj", "pierdolić", "pierdolic", "jebać", "jebac", "jebany", "spierdalaj", "skurwysyn", "pizda",
        // Svenska
        "fitta", "kuk", "hora", "jävla", "javla", "knulla", "fan",
        // Dansk / Norsk
        "pikk", "fitte", "kuk", "faen", "helvete", "jævla", "jaevla", "luder", "pik",
    )

    /**
     * Come iOS: la parola normalizzata (minuscola, senza accenti) si confronta con le voci cosi' come sono;
     * per le voci accentate ("enculé") conta la variante senza accento, gia' in lista.
     */
    private fun isBlocked(normalized: String): Boolean = blockedWords.contains(normalized)

    /** `true` se il testo contiene almeno una parola vietata. */
    fun containsProfanity(text: String): Boolean = words(text).any { isBlocked(normalize(it)) }

    /** Sostituisce ogni parola vietata con l'iniziale seguita da asterischi, lasciando intatto il resto. */
    fun masked(text: String): String {
        if (!containsProfanity(text)) return text
        val result = StringBuilder(text.length)
        val word = StringBuilder()
        fun flushWord() {
            if (word.isEmpty()) return
            val current = word.toString()
            if (isBlocked(normalize(current))) {
                // Come `Character` di Swift: l'iniziale include gli eventuali segni combinanti che la seguono.
                val first = firstGraphemeLength(current)
                result.append(current, 0, first)
                result.append("*".repeat(maxOf(1, graphemeCount(current) - 1)))
            } else {
                result.append(current)
            }
            word.setLength(0)
        }
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val length = Character.charCount(codePoint)
            if (isWordCodePoint(codePoint, afterLetter = word.isNotEmpty())) {
                word.appendCodePoint(codePoint)
            } else {
                flushWord()
                result.appendCodePoint(codePoint)
            }
            index += length
        }
        flushWord()
        return result.toString()
    }

    /** Parole del testo (sequenze di lettere, segni combinanti inclusi). */
    private fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (isWordCodePoint(codePoint, afterLetter = word.isNotEmpty())) {
                word.appendCodePoint(codePoint)
            } else if (word.isNotEmpty()) {
                out += word.toString()
                word.setLength(0)
            }
            index += Character.charCount(codePoint)
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    /** Lettera, oppure segno combinante attaccato a una lettera (in Swift fa parte dello stesso `Character`). */
    private fun isWordCodePoint(codePoint: Int, afterLetter: Boolean): Boolean {
        if (Character.isLetter(codePoint)) return true
        if (!afterLetter) return false
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun isMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun graphemeCount(word: String): Int {
        var count = 0
        var index = 0
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            if (!isMark(codePoint)) count++
            index += Character.charCount(codePoint)
        }
        return count
    }

    private fun firstGraphemeLength(word: String): Int {
        var index = Character.charCount(word.codePointAt(0))
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            if (!isMark(codePoint)) break
            index += Character.charCount(codePoint)
        }
        return index
    }

    /** Minuscolo e senza diacritici: "Scheiße" -> "scheisse", "Puttána" -> "puttana". */
    private fun normalize(word: String): String {
        // NFKD = diacritici separati + forme a larghezza piena ricondotte a quelle normali (widthInsensitive).
        val decomposed = Normalizer.normalize(word, Normalizer.Form.NFKD)
        val stripped = StringBuilder(decomposed.length)
        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            if (!isMark(codePoint)) stripped.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return stripped.toString().lowercase(Locale.ROOT).replace("ß", "ss")
    }
}
