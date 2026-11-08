import Foundation
import UserNotifications
import UIKit

/// Chat attualmente sullo schermo: solo per quella (con iPhone sbloccato e app attiva) il banner e' superfluo.
@MainActor
enum NotificationPresence {

    static var visibleConversationID: String?

    /// `true` se l'utente sta gia' guardando la conversazione. A schermo bloccato (anche con l'app in
    /// primo piano prima del blocco) i dati protetti non sono disponibili e la notifica va sempre mostrata.
    static func isShowing(conversationID: String) -> Bool {
        let application = UIApplication.shared
        return application.applicationState == .active
            && application.isProtectedDataAvailable
            && visibleConversationID == conversationID
    }
}

/// Notifiche locali istantanee per i messaggi ricevuti via BLE: a schermo spento/bloccato, in
/// background e, in primo piano, per le chat diverse da quella aperta.
struct LocalNotificationCenter: MeshNotifier {

    /// Richiede l'autorizzazione (da chiamare dopo l'onboarding, non al primo avvio a freddo).
    func requestAuthorization() async -> Bool {
        let center = UNUserNotificationCenter.current()
        return (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Chiede il permesso solo se l'utente non ha ancora risposto (es. profili creati prima che
    /// l'onboarding lo chiedesse): non mostra nulla se il permesso e' gia' stato concesso o negato.
    func requestAuthorizationIfNeeded() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .notDetermined else { return }
        _ = await requestAuthorization()
    }

    func notify(message: MessageRecord, conversation: ConversationRecord) async {
        // Chat aperta e iPhone sbloccato: la UI mostra gia' il messaggio (con feedback aptico).
        let conversationID = conversation.id
        let isShowing = await MainActor.run { NotificationPresence.isShowing(conversationID: conversationID) }
        guard !isShowing else { return }

        let content = UNMutableNotificationContent()
        content.title = conversation.type == .direct ? (message.senderNickname ?? conversation.title) : conversation.title
        if conversation.type == .group, let sender = message.senderNickname {
            content.subtitle = sender
        }
        content.body = Self.preview(for: message)
        content.sound = .default
        content.threadIdentifier = conversation.id
        content.userInfo = ["conversation_id": conversation.id, "msg_id": message.msgID.hexString]

        let request = UNNotificationRequest(identifier: message.msgID.hexString, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    static func preview(for message: MessageRecord) -> String {
        switch message.contentType {
        case .text: return message.text ?? ""
        case .image: return String(localized: "📷 Foto")
        case .audio: return String(localized: "🎤 Nota vocale")
        case .game:
            // Carte: "🃏 Scopa", "🃏 Scopone", "🃏 Briscola"; gli altri giochi restano generici.
            if let data = message.gameState, let state = try? GameSessionState.decode(data), state.isCardGame {
                return GameService.title(for: state.gameKind)
            }
            return String(localized: "🎮 Mossa di gioco")
        case .system: return message.text ?? String(localized: "Aggiornamento")
        }
    }
}

/// Senza delegate iOS scarta in silenzio le notifiche arrivate mentre considera l'app in primo piano
/// (incluso il momento del blocco schermo): qui le mostriamo come banner + Centro Notifiche.
final class NotificationPresentationDelegate: NSObject, UNUserNotificationCenterDelegate, Sendable {

    static let shared = NotificationPresentationDelegate()

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let conversationID = notification.request.content.userInfo["conversation_id"] as? String
        let isShowing = await MainActor.run {
            conversationID.map(NotificationPresence.isShowing(conversationID:)) ?? false
        }
        return isShowing ? [] : [.banner, .list, .sound]
    }
}
