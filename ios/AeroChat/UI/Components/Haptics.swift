import SwiftUI
import UIKit

/// Feedback aptico centralizzato (tocchi sui pulsanti, ricezione messaggi, pairing riuscito, errori).
@MainActor
enum Haptics {

    /// Generatore condiviso e gia' "preparato": il tocco sui pulsanti deve essere immediato e morbido.
    private static let softGenerator: UIImpactFeedbackGenerator = {
        let generator = UIImpactFeedbackGenerator(style: .soft)
        generator.prepare()
        return generator
    }()

    /// Vibrazione leggera e morbida per il tocco di un pulsante.
    static func tap() {
        softGenerator.impactOccurred(intensity: 0.6)
        softGenerator.prepare()
    }

    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    static func warning() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }

    static func error() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }
}

/// Stile dei pulsanti dell'app: aspetto "plain" con tinta d'accento, leggera compressione alla
/// pressione e vibrazione morbida nel momento in cui il dito tocca il pulsante.
struct HapticButtonStyle: ButtonStyle {

    /// Area di tocco minima (44 pt secondo le Human Interface Guidelines); 0 per le celle delle griglie.
    var minimumTapSize: CGFloat = 44

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            // Testi lunghi (traduzioni, barra di navigazione stretta): si riducono invece di troncarsi.
            .minimumScaleFactor(0.75)
            .frame(minWidth: minimumTapSize, minHeight: minimumTapSize)
            .foregroundStyle(configuration.role == .destructive ? AnyShapeStyle(Color.red) : AnyShapeStyle(.tint))
            .opacity(configuration.isPressed ? 0.6 : 1)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
            .contentShape(Rectangle())
            .onChange(of: configuration.isPressed) { _, pressed in
                if pressed { Haptics.tap() }
            }
    }
}

extension ButtonStyle where Self == HapticButtonStyle {
    static var haptic: HapticButtonStyle { HapticButtonStyle() }
    /// Senza area minima: per le celle fitte delle griglie di gioco.
    static var hapticCompact: HapticButtonStyle { HapticButtonStyle(minimumTapSize: 0) }
}

extension View {
    /// Vibrazione morbida al tocco per i pulsanti con uno stile di sistema (es. `.borderedProminent`)
    /// e per le voci di menu, che non passano da `HapticButtonStyle`.
    func hapticOnTap() -> some View {
        simultaneousGesture(TapGesture().onEnded { Haptics.tap() })
    }
}
