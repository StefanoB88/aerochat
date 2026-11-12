import Foundation

/// Premio inviti (RELEASE_1_4_SPEC.md §B4): i contatti nuovi arrivati con il nostro QR/link (`PAIR_HELLO` da un
/// `node_id` mai visto) si contano una volta; al **3°** distinto si sblocca, **una volta per installazione**, un
/// Pass locale di **24 h** solo per uso personale (non autorizza a regalare il Pass ai gruppi).
enum ReferralProgram {

    static let goal = 3
    static let rewardDuration: TimeInterval = 24 * 3_600

    /// Stato del programma (persistito in `UserDefaults`).
    struct State: Codable, Equatable, Sendable {
        /// `node_id` (hex) contati.
        var invited: [String] = []
        var rewardGranted = false
        /// Scadenza del Pass inviti (epoch ms), 0 = mai sbloccato.
        var passUntilMs: Int64 = 0

        /// Amici contati per il contatore "2 su 3", al massimo `goal`.
        var progress: Int { min(invited.count, ReferralProgram.goal) }

        func isPassActive(now: Date = Date()) -> Bool { passUntilMs > now.unixMilliseconds }
    }

    /// Esito della registrazione: stato nuovo e se il Pass e' stato appena sbloccato.
    struct Outcome: Equatable, Sendable {
        var state: State
        var unlocked: Bool
    }

    /// Nuovo contatto arrivato con il nostro QR. `isNewPeer` = `node_id` mai visto prima nel database (un contatto
    /// gia' noto che riscansiona non conta).
    static func register(_ nodeHex: String, isNewPeer: Bool, state: State, now: Date = Date()) -> Outcome {
        guard isNewPeer, !state.invited.contains(nodeHex) else { return Outcome(state: state, unlocked: false) }
        var next = state
        // Dopo il traguardo basta ricordarlo: niente lista che cresce per sempre.
        if next.invited.count < goal { next.invited.append(nodeHex) }
        guard !next.rewardGranted, next.invited.count >= goal else { return Outcome(state: next, unlocked: false) }
        next.rewardGranted = true
        next.passUntilMs = now.addingTimeInterval(rewardDuration).unixMilliseconds
        return Outcome(state: next, unlocked: true)
    }

    // MARK: Persistenza

    static func load(defaults: UserDefaults = .standard) -> State {
        guard let data = defaults.data(forKey: AppSettings.Key.referralState),
              let state = try? JSONDecoder().decode(State.self, from: data) else { return State() }
        return state
    }

    static func save(_ state: State, defaults: UserDefaults = .standard) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        defaults.set(data, forKey: AppSettings.Key.referralState)
    }
}
