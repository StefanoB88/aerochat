import SwiftUI

/// Forma d'onda stile WhatsApp dai 32 campioni `UInt8` dell'header del messaggio.
struct WaveformView: View {

    let samples: Data
    var progress: Double = 0
    var tint: Color = AeroTheme.accentCyan

    private var values: [CGFloat] {
        let raw = samples.isEmpty ? Data(repeating: 40, count: 32) : samples
        return raw.map { max(0.12, CGFloat($0) / 255) }
    }

    var body: some View {
        GeometryReader { geometry in
            let bars = values
            let spacing: CGFloat = 2
            let barWidth = max(1.5, (geometry.size.width - spacing * CGFloat(bars.count - 1)) / CGFloat(bars.count))
            HStack(alignment: .center, spacing: spacing) {
                ForEach(Array(bars.enumerated()), id: \.offset) { index, value in
                    let played = Double(index) / Double(max(1, bars.count)) < progress
                    Capsule()
                        .fill(played ? tint : tint.opacity(0.35))
                        .frame(width: barWidth, height: max(3, geometry.size.height * value))
                }
            }
            .frame(maxHeight: .infinity, alignment: .center)
        }
        .accessibilityHidden(true)
    }
}
