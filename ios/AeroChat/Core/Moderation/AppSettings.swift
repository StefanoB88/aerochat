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
        /// Card "Porta i tuoi amici a bordo" chiusa dall'utente: non ricompare piu' (quella in volo si').
        static let inviteCardDismissed = "home.inviteCardDismissed"
        /// "Mostra il mio posto ai vicini": `Presence.seat` (default spento).
        static let shareSeat = "nearby.shareSeat"
        /// Prefisso del posto salvato per volo: `flight.seat.<CARRIER|FLIGHT|yyyy-MM-dd>` -> "14C".
        static let seatPrefix = "flight.seat."
        /// Modalita' viaggio della card principale (`TravelMode.rawValue`).
        static let travelMode = "home.travelMode"
        /// Ultimo avviso di emergenza inviato in volo (secondi dal 1970): al massimo uno ogni 10 minuti.
        static let lastEmergencyAt = "emergency.lastSentAt"
        /// Richiesta di recensione: prima apertura, numero di aperture, ultima richiesta.
        static let firstOpenAt = "review.firstOpenAt"
        static let openCount = "review.openCount"
        static let lastReviewRequestAt = "review.lastRequestAt"
        /// Premio inviti (`ReferralProgram.State` in JSON).
        static let referralState = "referral.state"
        /// Partite gratis gia' usate (`flight:<code>` / `day:<yyyy-MM-dd>`).
        static let freeGamesUsed = "games.freeUsed"
        /// "Attivita' in tempo reale": Live Activity "A bordo" durante il volo (default attiva).
        static let liveActivity = "flight.liveActivity"
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
            Key.shareSeat: false,
            Key.liveActivity: true,
        ])
    }

    static var isSeatShared: Bool {
        UserDefaults.standard.bool(forKey: Key.shareSeat)
    }

    static var isLiveActivityEnabled: Bool {
        UserDefaults.standard.bool(forKey: Key.liveActivity)
    }

    /// Posto salvato per il volo (carta d'imbarco o inserito a mano).
    static func seat(forFlight flightCode: String?) -> String? {
        guard let flightCode, !flightCode.isEmpty else { return nil }
        return SeatCode.normalized(UserDefaults.standard.string(forKey: Key.seatPrefix + flightCode))
    }

    /// Salva (o cancella con nil) il posto per il volo; un valore non valido cancella.
    static func setSeat(_ seat: String?, forFlight flightCode: String) {
        let key = Key.seatPrefix + flightCode
        if let valid = SeatCode.normalized(seat) {
            UserDefaults.standard.set(valid, forKey: key)
        } else {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }

    static var travelMode: TravelMode {
        TravelMode(rawValue: UserDefaults.standard.string(forKey: Key.travelMode) ?? "") ?? .flight
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

/// Modalita' viaggio della card principale a terra (RELEASE_1_4_SPEC.md §B10): cambia titolo, sottotitolo e le
/// due azioni. Salvata in `AppSettings.Key.travelMode`.
enum TravelMode: String, CaseIterable, Identifiable, Sendable {
    case flight
    case ship
    case festival
    case mountain
    case abroad

    var id: String { rawValue }

    var title: String {
        switch self {
        case .flight: return String(localized: "Volo")
        case .ship: return String(localized: "Nave")
        case .festival: return String(localized: "Festival")
        case .mountain: return String(localized: "Montagna")
        case .abroad: return String(localized: "Estero")
        }
    }

    var systemImage: String {
        switch self {
        case .flight: return "airplane"
        case .ship: return "ferry.fill"
        case .festival: return "music.note"
        case .mountain: return "mountain.2.fill"
        case .abroad: return "globe"
        }
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
