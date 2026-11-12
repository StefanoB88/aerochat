import UIKit
import AVFoundation
import Photos

/// Permessi di sistema usati dall'app. Una volta negato, iOS non mostra piu' la sua richiesta: l'unica
/// strada e' l'app Impostazioni. Per questo ogni azione che ne ha bisogno, se il permesso e' negato,
/// spiega a cosa serve e offre "Apri Impostazioni" (ogni volta, non solo la prima).
enum AppPermission: Sendable {
    case bluetooth
    case camera
    case microphone
    case photos
    case location
    case notifications
    case nearbyInteraction

    var title: String {
        switch self {
        case .bluetooth: return String(localized: "Serve il Bluetooth")
        case .camera: return String(localized: "Serve la fotocamera")
        case .microphone: return String(localized: "Serve il microfono")
        case .photos: return String(localized: "Serve l'accesso alle Foto")
        case .location: return String(localized: "Serve la posizione")
        case .notifications: return String(localized: "Notifiche disattivate")
        case .nearbyInteraction: return String(localized: "Serve \"Interazioni nelle vicinanze\"")
        }
    }

    var message: String {
        switch self {
        case .bluetooth:
            return String(localized: "AeroChat parla con i telefoni vicini solo via Bluetooth: senza Bluetooth chat, giochi e radar non funzionano. Consentilo ad AeroChat nelle Impostazioni.")
        case .camera:
            return String(localized: "La fotocamera serve per scattare foto e inquadrare QR e carte d'imbarco. Consentila ad AeroChat nelle Impostazioni.")
        case .microphone:
            return String(localized: "Il microfono serve per le note vocali e le chiamate. Consentilo ad AeroChat nelle Impostazioni.")
        case .photos:
            return String(localized: "Per salvare le foto nel rullino consenti ad AeroChat di aggiungere foto nelle Impostazioni.")
        case .location:
            return String(localized: "La posizione serve solo quando la condividi in una chat o la alleghi all'avviso di emergenza. Consentila ad AeroChat nelle Impostazioni.")
        case .notifications:
            return String(localized: "Senza notifiche non saprai quando arriva un messaggio con l'app chiusa. Attivale per AeroChat nelle Impostazioni.")
        case .nearbyInteraction:
            return String(localized: "Serve per misurare con precisione distanza e direzione degli iPhone vicini sul radar. Consentila ad AeroChat nelle Impostazioni.")
        }
    }
}

@MainActor
enum PermissionPrompt {

    /// Spiega il permesso negato e offre di aprire le Impostazioni dell'app. Si presenta sopra la
    /// schermata in primo piano (anche dentro un foglio o la schermata di chiamata).
    static func showDenied(_ permission: AppPermission, attempt: Int = 0) {
        guard let presenter = topViewController() else { return }
        // Chiamato dall'apparizione di un foglio: aspettiamo che l'animazione finisca, altrimenti UIKit
        // rifiuta la presentazione.
        if presenter.isBeingPresented || presenter.isBeingDismissed || presenter.view.window == nil {
            guard attempt < 5 else { return }
            Task {
                try? await Task.sleep(for: .milliseconds(350))
                showDenied(permission, attempt: attempt + 1)
            }
            return
        }
        // Niente avvisi impilati: se ce n'e' gia' uno a schermo, basta quello.
        guard !(presenter is UIAlertController) else { return }
        let alert = UIAlertController(title: permission.title, message: permission.message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "Non ora"), style: .cancel))
        let open = UIAlertAction(title: String(localized: "Apri Impostazioni"), style: .default) { _ in
            openSettings(for: permission)
        }
        alert.addAction(open)
        alert.preferredAction = open
        presenter.present(alert, animated: true)
    }

    static func openSettings(for permission: AppPermission) {
        let string = permission == .notifications ? UIApplication.openNotificationSettingsURLString : UIApplication.openSettingsURLString
        guard let url = URL(string: string) else { return }
        UIApplication.shared.open(url)
    }

    // MARK: - Verifica e richiesta

    /// Fotocamera: la richiesta di sistema se non e' mai stata fatta, altrimenti (negata in passato)
    /// l'avviso con "Apri Impostazioni". `true` = si puo' usare. Stessa logica per gli altri `ensure`.
    static func ensureCamera() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        // Prima volta: la richiesta di sistema; se l'utente dice no adesso, non insistiamo subito.
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default:
            showDenied(.camera)
            return false
        }
    }

    /// Microfono (note vocali, chiamate).
    static func ensureMicrophone() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .undetermined: return await AVAudioApplication.requestRecordPermission()
        default:
            showDenied(.microphone)
            return false
        }
    }

    /// Aggiunta di foto al rullino.
    static func ensurePhotosAddOnly() async -> Bool {
        switch PHPhotoLibrary.authorizationStatus(for: .addOnly) {
        case .authorized, .limited: return true
        case .notDetermined:
            let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            return status == .authorized || status == .limited
        default:
            showDenied(.photos)
            return false
        }
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.filter { $0.activationState == .foregroundActive }.flatMap(\.windows).first(where: \.isKeyWindow)
            ?? scenes.flatMap(\.windows).first(where: \.isKeyWindow)
        var top = window?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}
