import SwiftUI

/// Palette OLED dark-first della cabina: nero puro, superfici grafite, ciano elettrico e smeraldo.
enum AeroTheme {

    static let background = Color(hex: 0x000000)
    static let surface = Color(hex: 0x121316)
    static let accentCyan = Color(hex: 0x00E5FF)
    static let accentEmerald = Color(hex: 0x10B981)
    static let textPrimary = Color.white
    static let textSecondary = Color.white.opacity(0.6)

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
