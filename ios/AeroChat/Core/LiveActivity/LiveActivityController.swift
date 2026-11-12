import Foundation
import ActivityKit

/// Avvia, aggiorna e chiude la Live Activity "A bordo": parte solo durante un volo (lo decide `AppEnvironment`:
/// Cabina del volo attiva o modalita' aereo probabile, impostazione "Attivita' in tempo reale" accesa) quando la
/// mesh ha almeno un vicino; si aggiorna con vicini / non letti / Cabina del volo; si chiude quando la mesh si
/// ferma, dopo 10 minuti senza vicini e senza non letti, alla terminazione dell'app e al ritorno a terra.
@MainActor
final class LiveActivityController {

    /// `Activity` non e' Sendable ma e' thread-safe per contratto di ActivityKit: il box permette di
    /// chiamarne i metodi async dal main actor senza avvisi di data race.
    private struct ActivityBox: @unchecked Sendable {
        let activity: Activity<MeshActivityAttributes>

        func update(_ content: ActivityContent<MeshActivityAttributes.ContentState>) async {
            await activity.update(content)
        }

        func end() async {
            await activity.end(nil, dismissalPolicy: .immediate)
        }

        var isLive: Bool { activity.activityState == .active || activity.activityState == .stale }
    }

    private var current: ActivityBox?
    private var lastState: MeshActivityAttributes.ContentState?
    /// Senza vicini per questo tempo l'attivita' si chiude da sola (niente vetrina vuota sulla Lock Screen).
    private var idleTimer: Task<Void, Never>?
    static let idleTimeout: TimeInterval = 10 * 60

    var isEnabled: Bool { ActivityAuthorizationInfo().areActivitiesEnabled }

    func update(nickname: String, neighborCount: Int, unreadCount: Int, flightTitle: String?, yourTurnIn: String?) async {
        guard isEnabled else { return }
        let state = MeshActivityAttributes.ContentState(neighborCount: neighborCount, unreadCount: unreadCount,
                                                        flightTitle: flightTitle, yourTurnIn: yourTurnIn, updatedAt: Date())
        if let last = lastState, last.neighborCount == state.neighborCount, last.unreadCount == state.unreadCount,
           last.flightTitle == state.flightTitle, last.yourTurnIn == state.yourTurnIn {
            return
        }
        lastState = state
        let content = ActivityContent(state: state, staleDate: Date().addingTimeInterval(Self.idleTimeout))

        if let current, current.isLive {
            await current.update(content)
        } else if neighborCount > 0 {
            // Si parte solo con qualcuno a portata: l'attivita' racconta la cabina, non la radio spenta.
            if let activity = try? Activity.request(attributes: MeshActivityAttributes(nickname: nickname), content: content, pushType: nil) {
                current = ActivityBox(activity: activity)
            }
        }
        scheduleIdleEnd(neighborCount: neighborCount, unreadCount: unreadCount)
    }

    private func scheduleIdleEnd(neighborCount: Int, unreadCount: Int) {
        idleTimer?.cancel()
        guard neighborCount == 0, unreadCount == 0 else { return }
        idleTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.idleTimeout))
            guard !Task.isCancelled else { return }
            await self?.end()
        }
    }

    func end() async {
        idleTimer?.cancel()
        idleTimer = nil
        guard let current else { return }
        await current.end()
        self.current = nil
        lastState = nil
    }

    /// Terminazione dell'app: iOS concede pochi istanti, quindi si chiudono tutte le attivita' aspettando al
    /// massimo 2 secondi (fuori dal main actor, che e' bloccato in attesa).
    nonisolated static func endAllBeforeTermination() {
        let semaphore = DispatchSemaphore(value: 0)
        Task.detached {
            for stale in Activity<MeshActivityAttributes>.activities.map(ActivityBox.init) {
                await stale.end()
            }
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 2)
    }

    /// All'avvio chiude eventuali attivita' rimaste da una sessione precedente.
    func cleanUpStale() async {
        for stale in Activity<MeshActivityAttributes>.activities.map(ActivityBox.init) {
            await stale.end()
        }
    }
}
