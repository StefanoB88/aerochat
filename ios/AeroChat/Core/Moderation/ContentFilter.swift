import Foundation

/// Filtro linguaggio della Cabina Pubblica: la bacheca e' tra sconosciuti, quindi le parole piu' offensive
/// vengono rifiutate in invio e mascherate in ricezione (`c***`). Il confronto e' per parola intera,
/// senza accenti e maiuscole, nelle lingue dell'app. Le chat private non vengono filtrate.
enum ContentFilter {

    /// Vocabolario minimo multilingue (it, en, de, fr, es, pt, nl, pl, sv, da, nb): solo insulti e volgarita'
    /// forti, niente parole ambigue che bloccherebbero conversazioni normali.
    static let blockedWords: Set<String> = [
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
    ]

    /// `true` se il testo contiene almeno una parola vietata.
    static func containsProfanity(_ text: String) -> Bool {
        tokens(in: text).contains { blockedWords.contains($0) }
    }

    /// Sostituisce ogni parola vietata con l'iniziale seguita da asterischi, lasciando intatto il resto.
    static func masked(_ text: String) -> String {
        guard containsProfanity(text) else { return text }
        var result = ""
        var word = ""
        func flushWord() {
            guard !word.isEmpty else { return }
            if blockedWords.contains(normalize(word)) {
                result.append(String(word.prefix(1)))
                result.append(String(repeating: "*", count: max(1, word.count - 1)))
            } else {
                result.append(word)
            }
            word = ""
        }
        for character in text {
            if character.isLetter {
                word.append(character)
            } else {
                flushWord()
                result.append(character)
            }
        }
        flushWord()
        return result
    }

    private static func tokens(in text: String) -> [String] {
        text.split { !$0.isLetter }.map { normalize(String($0)) }
    }

    /// Minuscolo e senza diacritici: "Scheiße" -> "scheisse", "Puttána" -> "puttana".
    private static func normalize(_ word: String) -> String {
        word.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: nil)
            .replacingOccurrences(of: "ß", with: "ss")
            .lowercased()
    }
}
