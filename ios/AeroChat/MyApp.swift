import SwiftUI
import UIKit
import UserNotifications

/// Avvio della mesh anche quando iOS rilancia l'app in background per un evento Bluetooth (State
/// Restoration: connessione pendente completata, dati da un compagno) con l'iPhone bloccato. In quel
/// caso nessuna scena viene mostrata e il `.task` della UI non parte: senza ricreare subito i
/// manager CoreBluetooth con lo stesso restore identifier gli eventi andrebbero persi e la mesh
/// resterebbe spenta fino all'apertura manuale dell'app.
final class AppDelegate: NSObject, UIApplicationDelegate {

    let environment = AppEnvironment()

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Prima del primo sblocco dopo il riavvio Keychain e database non sono leggibili: ci pensera' la UI.
        if application.isProtectedDataAvailable {
            let environment = environment
            Task { await environment.bootstrap() }
        }
        return true
    }
}

@main
struct MyApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    private var environment: AppEnvironment { appDelegate.environment }

    init() {
        // Banner anche con l'app in primo piano / a schermo appena bloccato (vedi delegate).
        UNUserNotificationCenter.current().delegate = NotificationPresentationDelegate.shared
        AppSettings.registerDefaults()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(environment)
                // Tutti i pulsanti dell'app vibrano in modo morbido al tocco.
                .buttonStyle(.haptic)
                .task {
                    await environment.bootstrap()
                }
                // Link di invito `aeromesh://` da Messaggi, AirDrop, Mail.
                .onOpenURL { url in
                    environment.handleIncomingURL(url)
                }
        }
    }
}
