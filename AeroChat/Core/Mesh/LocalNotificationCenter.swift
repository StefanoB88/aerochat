import Foundation
import UserNotifications
import UIKit

/// Notifiche locali istantanee a schermo spento per i messaggi ricevuti via BLE in background.
struct LocalNotificationCenter: MeshNotifier {

    /// Richiede l'autorizzazione (da chiamare dopo l'onboarding, non al primo avvio a freddo).
    func requestAuthorization() async -> Bool {
        let center = UNUserNotificationCenter.current()
        return (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    func notify(message: MessageRecord, conversation: ConversationRecord) async {
        // In foreground la UI mostra gia' il messaggio (con feedback aptico): niente banner doppio.
        let isActive = await MainActor.run { UIApplication.shared.applicationState == .active }
        guard !isActive else { return }

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
        case .game: return String(localized: "🎮 Mossa di gioco")
        case .system: return message.text ?? String(localized: "Aggiornamento")
        }
    }
}
