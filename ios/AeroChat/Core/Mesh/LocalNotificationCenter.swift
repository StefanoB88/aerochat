import Foundation
import UserNotifications
import UIKit
import Observation

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
        await notify(message: message, conversation: conversation, badgeCount: nil)
    }

    func notify(message: MessageRecord, conversation: ConversationRecord, badgeCount: Int?) async {
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
        // Totale dei non letti: l'icona si aggiorna anche se l'app viene sospesa subito dopo.
        if let badgeCount {
            content.badge = NSNumber(value: badgeCount)
        }

        let request = UNNotificationRequest(identifier: message.msgID.hexString, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    /// Messaggio cancellato per tutti: via la notifica con il suo testo (identificatore = `msg_id` hex).
    func removeNotification(msgID: Data) async {
        let identifier = msgID.hexString
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
    }

    /// Badge dell'icona nella schermata Home: totale dei messaggi non letti (0 lo nasconde).
    static func setAppBadge(_ count: Int) async {
        try? await UNUserNotificationCenter.current().setBadgeCount(max(0, count))
    }

    /// Avviso di emergenza: notifica "time sensitive" (passa la modalita' Concentrazione) anche con l'app aperta.
    func notifyEmergency(_ alert: EmergencyAlert) async {
        let content = UNMutableNotificationContent()
        content.title = "\(EmergencyService.emoji(for: alert.status)) \(alert.statusTitle)"
        content.subtitle = alert.senderNickname
        content.body = alert.text.isEmpty ? String(localized: "Avviso da un passeggero vicino.") : alert.text
        content.sound = .defaultCritical
        content.interruptionLevel = .timeSensitive
        content.threadIdentifier = "emergency"
        content.userInfo = ["conversation_id": ConversationRecord.cabinLoungeID, "msg_id": alert.msgID.hexString, "emergency": true]
        let request = UNNotificationRequest(identifier: "emergency-\(alert.msgID.hexString)", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    /// "Marco ti saluta" / "Marco ti sfida a Scopa": il tocco apre "Persone vicine".
    func notifyContactRequest(_ request: IncomingContactRequest) async {
        let content = UNMutableNotificationContent()
        content.title = await MainActor.run { ContactRequestStore.title(for: request) }
        content.body = request.greeting.isEmpty
            ? String(localized: "Tocca per rispondere: Accetta o Rifiuta in Persone vicine.")
            : request.greeting
        content.sound = .default
        content.threadIdentifier = "contact-requests"
        content.userInfo = [DeepLink.userInfoKey: DeepLink.nearbyValue]
        let request = UNNotificationRequest(identifier: "contact-\(request.requestID.hexString)", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    /// Volo finito: una sola notifica per volo, il tocco apre la cartolina gia' compilata.
    func notifyTripCard(_ trip: FlightTrip) async {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "La tua cartolina di volo è pronta ✈️")
        content.body = String(localized: "Il volo \(trip.flightNumber) in numeri: persone incontrate, messaggi e partite. Tocca per vederla.")
        content.sound = .default
        content.threadIdentifier = "trip-card"
        content.userInfo = [DeepLink.userInfoKey: DeepLink.tripCardPrefix + trip.flightCode]
        let request = UNNotificationRequest(identifier: "trip-\(trip.flightCode)", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    /// Promemoria del giorno del volo (07:00, solo per partenze future): "Oggi voli con AZ204: invita ora chi
    /// viaggia con te...". Il tocco apre "Invita amici". Un solo promemoria per volo (stesso identificatore).
    @discardableResult
    func scheduleFlightReminder(_ pass: BoardingPass, now: Date = Date()) async -> Bool {
        guard let fire = FlightReminder.fireDate(departureDay: pass.departureDay, now: now) else { return false }
        let flight = FlightReminder.label(canonicalKey: pass.canonicalKey)
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Oggi voli con \(flight) ✈️")
        content.body = String(localized: "Oggi voli con \(flight): invita ora chi viaggia con te, in aereo non potrà scaricare AeroChat.")
        content.sound = .default
        content.threadIdentifier = "flight-reminder"
        content.userInfo = [DeepLink.userInfoKey: DeepLink.inviteValue]
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        let request = UNNotificationRequest(identifier: "flight-reminder-\(pass.canonicalKey)", content: content, trigger: trigger)
        return (try? await UNUserNotificationCenter.current().add(request)) != nil
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
        // Le emergenze si vedono sempre, anche con la Cabina aperta.
        if notification.request.content.userInfo["emergency"] as? Bool == true { return [.banner, .list, .sound] }
        let conversationID = notification.request.content.userInfo["conversation_id"] as? String
        let isShowing = await MainActor.run {
            conversationID.map(NotificationPresence.isShowing(conversationID:)) ?? false
        }
        return isShowing ? [] : [.banner, .list, .sound]
    }

    /// Tocco su una notifica: richieste di contatto -> "Persone vicine", volo finito -> cartolina.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let value = response.notification.request.content.userInfo[DeepLink.userInfoKey] as? String,
              let link = DeepLink(userInfoValue: value) else { return }
        await MainActor.run { DeepLinkCenter.shared.pending = link }
    }
}

/// Destinazioni aperte dal tocco su una notifica locale.
enum DeepLink: Equatable, Sendable {
    case nearby
    case tripCard(flightCode: String)
    /// "Invita amici" (promemoria del giorno del volo).
    case invite

    static let userInfoKey = "deep_link"
    static let nearbyValue = "nearby"
    static let inviteValue = "invite"
    static let tripCardPrefix = "trip:"

    init?(userInfoValue value: String) {
        if value == Self.nearbyValue {
            self = .nearby
        } else if value == Self.inviteValue {
            self = .invite
        } else if value.hasPrefix(Self.tripCardPrefix) {
            self = .tripCard(flightCode: String(value.dropFirst(Self.tripCardPrefix.count)))
        } else {
            return nil
        }
    }
}

/// Ultimo deep link da aprire (osservato dalla schermata principale).
@MainActor
@Observable
final class DeepLinkCenter {
    static let shared = DeepLinkCenter()
    var pending: DeepLink?
}
