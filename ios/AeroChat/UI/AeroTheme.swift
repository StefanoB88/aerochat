import SwiftUI

/// Palette della cabina: segue l'aspetto di sistema (chiaro o scuro).
/// Scuro: OLED nero puro, superfici grafite, ciano elettrico e smeraldo.
/// Chiaro: fondo grigio "grouped", superfici bianche, ciano e smeraldo piu' scuri per il contrasto.
enum AeroTheme {

    static let background = Color(light: 0xF2F3F7, dark: 0x000000)
    static let surface = Color(light: 0xFFFFFF, dark: 0x121316)
    static let accentCyan = Color(light: 0x0091A8, dark: 0x00E5FF)
    static let accentEmerald = Color(light: 0x059669, dark: 0x10B981)
    static let textPrimary = Color(light: 0x0B0C0F, dark: 0xFFFFFF)
    static let textSecondary = Color(light: 0x5F6573, dark: 0xFFFFFF, darkOpacity: 0.6)

    /// Titoli in SF Pro Rounded.
    static func title(_ size: CGFloat = 28, weight: Font.Weight = .bold) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }

    static func monospaced(_ size: CGFloat = 13) -> Font {
        .system(size: size, weight: .regular, design: .monospaced)
    }

    static let cornerRadius: CGFloat = 20
}

extension Color {
    /// Colore da valore esadecimale `0xRRGGBB`.
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

extension Color {
    /// Colore dinamico che si risolve in base all'aspetto (chiaro/scuro) del contesto.
    init(light: UInt32, dark: UInt32, lightOpacity: Double = 1, darkOpacity: Double = 1) {
        let lightColor = UIColor(Color(hex: light, opacity: lightOpacity))
        let darkColor = UIColor(Color(hex: dark, opacity: darkOpacity))
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? darkColor : lightColor
        })
    }
}
