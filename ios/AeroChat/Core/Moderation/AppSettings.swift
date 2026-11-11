import Foundation

/// Preferenze locali dell'utente (UserDefaults). Le chiavi sono centralizzate qui cosi' le viste
/// (`@AppStorage`) e i servizi fuori dal main thread (router, filtro linguaggio) leggono lo stesso valore.
enum AppSettings {

    enum Key {
        /// Versione delle regole della Cabina Pubblica accettate dall'utente (0 = mai).
        static let loungeRulesAcceptedVersion = "lounge.rulesAcceptedVersion"
        /// Maschera le parolacce nei messaggi ricevuti in Cabina Pubblica.
        static let languageFilter = "moderation.languageFilter"
        /// Le foto inviate da contatti non verificati (mai scansionati di persona) restano sfocate finche' non vengono toccate.
        static let blurStrangerMedia = "moderation.blurStrangerMedia"
        /// Compagno simulato per la revisione dell'App Store (un solo dispositivo a disposizione).
        static let demoMode = "review.demoMode"
        /// Risposte rapide suggerite dal modello on-device (solo dove Apple Intelligence e' disponibile).
        static let smartReplies = "assistant.smartReplies"
        /// "Visibile alle persone vicine": invio del PRESENCE (si continua comunque a vedere gli altri).
        static let nearbyVisible = "nearby.visible"
        /// Nel PRESENCE annunciamo che accettiamo sfide a carte.
        static let openToGames = "nearby.openToGames"
        /// La rete cellulare e' stata vista almeno una volta (serve a riconoscere la modalita' aereo).
        static let hasSeenCellular = "flight.hasSeenCellular"
    }

    /// Da incrementare quando le regole della Cabina cambiano in modo sostanziale: l'utente le rivedra'.
    static let loungeRulesVersion = 1

    /// Valori di default registrati all'avvio (`UserDefaults.register`), cosi' `@AppStorage` e le letture
    /// dirette concordano anche prima che l'utente tocchi un interruttore.
    static func registerDefaults() {
        UserDefaults.standard.register(defaults: [
            Key.languageFilter: true,
            Key.blurStrangerMedia: true,
            Key.demoMode: false,
            Key.smartReplies: true,
            Key.nearbyVisible: true,
            Key.openToGames: true,
        ])
    }

    static var isNearbyVisible: Bool {
        UserDefaults.standard.bool(forKey: Key.nearbyVisible)
    }

    static var isOpenToGames: Bool {
        UserDefaults.standard.bool(forKey: Key.openToGames)
    }

    static var smartRepliesEnabled: Bool {
        UserDefaults.standard.bool(forKey: Key.smartReplies)
    }

    static var isLanguageFilterEnabled: Bool {
        UserDefaults.standard.bool(forKey: Key.languageFilter)
    }

    static var blursStrangerMedia: Bool {
        UserDefaults.standard.bool(forKey: Key.blurStrangerMedia)
    }

    static var isDemoModeEnabled: Bool {
        UserDefaults.standard.bool(forKey: Key.demoMode)
    }

    static var hasAcceptedLoungeRules: Bool {
        UserDefaults.standard.integer(forKey: Key.loungeRulesAcceptedVersion) >= loungeRulesVersion
    }
}

/// Riferimenti esterni mostrati nell'app (listino, legale, supporto).
enum AppLinks {
    static let website = URL(string: "https://aeromesh.app")!
    static let privacyPolicy = URL(string: "https://aeromesh.app/privacy")!
    static let termsOfUse = URL(string: "https://aeromesh.app/terms")!
    /// EULA standard di Apple, richiesta dalle linee guida per gli acquisti in-app.
    static let appleEULA = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    static let supportEmail = "support@aeromesh.app"
    static let safetyEmail = "safety@aeromesh.app"
}
