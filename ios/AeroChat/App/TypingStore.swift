import Foundation
import Observation

/// "Sta scrivendo" ricevuti (`TYPING`): solo in memoria, per conversazione e mittente. Ogni voce scade
/// da sola `lifetime` secondi dopo l'ultimo `true`; un `false` o un messaggio dello stesso mittente la
/// azzerano subito.
@MainActor
@Observable
final class TypingStore {

    struct Typer: Equatable, Sendable {
        let nodeID: Data
        var nickname: String
        var expiresAt: Date
    }

    /// Mittenti che stanno scrivendo, per `conversation.id`.
    private(set) var typersByConversation: [String: [Typer]] = [:]

    @ObservationIgnored let lifetime: TimeInterval
    @ObservationIgnored private let clock: @MainActor () -> Date
    @ObservationIgnored private var expiryTask: Task<Void, Never>?

    init(lifetime: TimeInterval = 6, clock: @escaping @MainActor () -> Date = { Date() }) {
        self.lifetime = lifetime
        self.clock = clock
    }

    func update(conversationID: String, senderNodeID: Data, nickname: String, isTyping: Bool) {
        var typers = typersByConversation[conversationID] ?? []
        if isTyping {
            let expiresAt = clock().addingTimeInterval(lifetime)
            if let index = typers.firstIndex(where: { $0.nodeID == senderNodeID }) {
                typers[index].nickname = nickname
                typers[index].expiresAt = expiresAt
            } else {
                typers.append(Typer(nodeID: senderNodeID, nickname: nickname, expiresAt: expiresAt))
            }
        } else {
            // Nessuna scrittura (ne' invalidazione della UI) se il mittente non stava scrivendo.
            guard typers.contains(where: { $0.nodeID == senderNodeID }) else { return }
            typers.removeAll { $0.nodeID == senderNodeID }
        }
        typersByConversation[conversationID] = typers.isEmpty ? nil : typers
        scheduleExpiry()
    }

    /// Mittenti ancora validi nella conversazione (quelli scaduti sono gia' esclusi).
    func typers(in conversationID: String) -> [Typer] {
        let now = clock()
        return (typersByConversation[conversationID] ?? []).filter { $0.expiresAt > now }
    }

    func isAnyoneTyping(in conversationID: String) -> Bool {
        !typers(in: conversationID).isEmpty
    }

    /// Rimuove le voci scadute (chiamato dal timer interno; pubblico per i test).
    func purgeExpired() {
        let now = clock()
        var updated: [String: [Typer]] = [:]
        for (id, typers) in typersByConversation {
            let alive = typers.filter { $0.expiresAt > now }
            if !alive.isEmpty { updated[id] = alive }
        }
        if updated != typersByConversation { typersByConversation = updated }
    }

    func removeAll() {
        expiryTask?.cancel()
        expiryTask = nil
        typersByConversation.removeAll()
    }

    /// Un solo timer per la prossima scadenza: la UI si aggiorna anche se il `false` va perso.
    private func scheduleExpiry() {
        expiryTask?.cancel()
        guard let next = typersByConversation.values.joined().map(\.expiresAt).min() else {
            expiryTask = nil
            return
        }
        let delay = max(0.05, next.timeIntervalSince(clock()))
        expiryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.purgeExpired()
            self.scheduleExpiry()
        }
    }

    // MARK: - Testi

    /// Sottotitolo dell'intestazione della chat ("sta scrivendo…", "Marco sta scrivendo…", "3 persone
    /// stanno scrivendo…"); nil se nessuno scrive.
    static func headerText(for typers: [Typer], isGroup: Bool) -> String? {
        switch typers.count {
        case 0:
            return nil
        case 1 where !isGroup:
            return String(localized: "sta scrivendo…")
        case 1:
            return String(localized: "\(typers[0].nickname) sta scrivendo…")
        default:
            return String(localized: "\(typers.count) persone stanno scrivendo…")
        }
    }
}

/// Lato invio del "sta scrivendo": `true` quando il testo diventa non vuoto, ripetuto al massimo ogni
/// `refreshInterval` mentre si scrive; `false` quando il testo torna vuoto, all'invio, all'uscita dalla
/// chat / in background (`stop()`) o dopo `idleTimeout` senza battiture.
@MainActor
final class TypingSender {

    let refreshInterval: TimeInterval
    let idleTimeout: TimeInterval
    private let send: @MainActor (Bool) -> Void
    private let clock: @MainActor () -> Date
    private(set) var isTyping = false
    private var lastSentAt: Date?
    private var idleTask: Task<Void, Never>?

    init(refreshInterval: TimeInterval = 4, idleTimeout: TimeInterval = 5,
         clock: @escaping @MainActor () -> Date = { Date() },
         send: @escaping @MainActor (Bool) -> Void) {
        self.refreshInterval = refreshInterval
        self.idleTimeout = idleTimeout
        self.clock = clock
        self.send = send
    }

    /// Testo della barra cambiato (battitura).
    func textChanged(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            stop()
            return
        }
        let now = clock()
        if !isTyping || now.timeIntervalSince(lastSentAt ?? .distantPast) >= refreshInterval {
            isTyping = true
            lastSentAt = now
            send(true)
        }
        scheduleIdle()
    }

    /// Invio, uscita dalla chat, background: se stavamo scrivendo parte un `false`.
    func stop() {
        idleTask?.cancel()
        idleTask = nil
        guard isTyping else { return }
        isTyping = false
        lastSentAt = nil
        send(false)
    }

    private func scheduleIdle() {
        idleTask?.cancel()
        let timeout = idleTimeout
        idleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            guard !Task.isCancelled, let self else { return }
            self.stop()
        }
    }
}
