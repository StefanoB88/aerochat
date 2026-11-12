import Foundation

/// Emergenza nella Cabina del volo (RELEASE_1_4_SPEC.md §B12), valida solo in volo (Cabina del volo attiva o
/// modalita' aereo probabile): "Ho bisogno di aiuto" richiede almeno 10 caratteri e una conferma esplicita,
/// e si invia al massimo un avviso ogni 10 minuti, per qualunque stato.
enum EmergencySafety {

    static let minimumHelpTextLength = 10
    static let cooldown: TimeInterval = 10 * 60

    /// `lastSent` nil = mai inviato.
    static func canSend(lastSent: Date?, now: Date = Date()) -> Bool {
        guard let lastSent else { return true }
        return now.timeIntervalSince(lastSent) >= cooldown
    }

    /// Minuti (arrotondati per eccesso) prima del prossimo avviso; 0 se si puo' gia' inviare.
    static func minutesUntilNext(lastSent: Date?, now: Date = Date()) -> Int {
        guard let lastSent else { return 0 }
        let remaining = cooldown - now.timeIntervalSince(lastSent)
        guard remaining > 0 else { return 0 }
        return Int((remaining / 60).rounded(.up))
    }

    /// Testo sufficiente per "Ho bisogno di aiuto" in volo (dopo il trim).
    static func isHelpTextSufficient(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).count >= minimumHelpTextLength
    }

    /// Le regole in volo valgono per questo stato? (solo "Ho bisogno di aiuto" chiede testo e conferma).
    static func requiresConfirmation(_ status: EmergencyStatus, inFlight: Bool) -> Bool {
        inFlight && status == .needHelp
    }

    static var lastSentAt: Date? {
        (UserDefaults.standard.object(forKey: AppSettings.Key.lastEmergencyAt) as? Double).map(Date.init(timeIntervalSince1970:))
    }

    static func recordSent(now: Date = Date()) {
        UserDefaults.standard.set(now.timeIntervalSince1970, forKey: AppSettings.Key.lastEmergencyAt)
    }
}
