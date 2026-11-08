import SwiftUI

/// Carta napoletana disegnata in codice (nessuna immagine): fondo avorio, valore negli angoli, simbolo del
/// seme al centro; le figure (F, C, R) hanno un emblema piu' grande dentro una cornice. Stesso disegno su Android.
struct NeapolitanCardView: View {

    /// Id 0..39; nil = dorso.
    let card: Int?
    var width: CGFloat = 60
    var highlighted = false
    var dimmed = false

    static let aspectRatio: CGFloat = 1.6

    var body: some View {
        let height = width * Self.aspectRatio
        let shape = RoundedRectangle(cornerRadius: width * 0.12, style: .continuous)
        Group {
            if let card, NeapolitanCards.isValid(card) {
                face(card, height: height)
            } else {
                CardBack()
            }
        }
        .frame(width: width, height: height)
        .clipShape(shape)
        .overlay(shape.strokeBorder(highlighted ? AeroTheme.accentCyan : Color.black.opacity(0.35),
                                    lineWidth: highlighted ? max(2, width * 0.05) : 0.8))
        .shadow(color: highlighted ? AeroTheme.accentCyan.opacity(0.6) : .black.opacity(0.45),
                radius: highlighted ? 8 : 3, y: highlighted ? 0 : 2)
        .opacity(dimmed ? 0.45 : 1)
        .accessibilityElement()
        .accessibilityLabel(card.map(NeapolitanCards.fullName) ?? String(localized: "Carta coperta"))
    }

    private func face(_ card: Int, height: CGFloat) -> some View {
        let suit = NeapolitanCards.suit(card)
        let color = SuitPalette.color(suit)
        let label = NeapolitanCards.cornerLabel(card)
        let figure = NeapolitanCards.isFigure(card)
        return ZStack {
            Color(hex: 0xF6F0DC)
            if figure {
                RoundedRectangle(cornerRadius: width * 0.06, style: .continuous)
                    .strokeBorder(color.opacity(0.75), lineWidth: max(1, width * 0.03))
                    .padding(width * 0.16)
                VStack(spacing: 0) {
                    SuitSymbol(suit: suit)
                        .frame(width: width * 0.5, height: height * 0.42)
                    Text(label)
                        .font(.system(size: width * 0.3, weight: .heavy, design: .serif))
                        .foregroundStyle(color)
                }
            } else {
                SuitSymbol(suit: suit)
                    .frame(width: width * 0.48, height: height * 0.42)
            }
            VStack {
                HStack {
                    cornerText(label, color: color)
                    Spacer()
                }
                Spacer()
                HStack {
                    Spacer()
                    cornerText(label, color: color).rotationEffect(.degrees(180))
                }
            }
            .padding(.horizontal, width * 0.07)
            .padding(.vertical, width * 0.05)
        }
    }

    private func cornerText(_ label: String, color: Color) -> some View {
        Text(label)
            .font(.system(size: width * 0.24, weight: .bold, design: .rounded))
            .foregroundStyle(color)
            .minimumScaleFactor(0.5)
            .lineLimit(1)
    }
}

/// Colori dei semi: denari oro, coppe rosso/arancio, spade blu, bastoni verde/marrone.
enum SuitPalette {
    static func color(_ suit: NeapolitanCards.Suit) -> Color {
        switch suit {
        case .denari: return Color(hex: 0xB8860B)
        case .coppe: return Color(hex: 0xC0392B)
        case .spade: return Color(hex: 0x1F4E9C)
        case .bastoni: return Color(hex: 0x2E7D32)
        }
    }
}

/// Simbolo del seme disegnato con `Canvas` nel rettangolo disponibile.
struct SuitSymbol: View {

    let suit: NeapolitanCards.Suit

    var body: some View {
        Canvas { context, size in
            let side = min(size.width, size.height / 1.3)
            let rect = CGRect(x: (size.width - side) / 2, y: (size.height - side * 1.3) / 2, width: side, height: side * 1.3)
            switch suit {
            case .denari: Self.drawCoin(in: rect, context: &context)
            case .coppe: Self.drawCup(in: rect, context: &context)
            case .spade: Self.drawSword(in: rect, context: &context)
            case .bastoni: Self.drawClub(in: rect, context: &context)
            }
        }
        .accessibilityHidden(true)
    }

    /// Denari: moneta d'oro a cerchi concentrici.
    private static func drawCoin(in rect: CGRect, context: inout GraphicsContext) {
        let side = rect.width
        let circle = CGRect(x: rect.midX - side / 2, y: rect.midY - side / 2, width: side, height: side)
        let gold = Color(hex: 0xE0A82E)
        let dark = Color(hex: 0x8A5A00)
        context.fill(Path(ellipseIn: circle), with: .color(gold))
        context.stroke(Path(ellipseIn: circle.insetBy(dx: side * 0.03, dy: side * 0.03)), with: .color(dark), lineWidth: side * 0.05)
        context.stroke(Path(ellipseIn: circle.insetBy(dx: side * 0.2, dy: side * 0.2)), with: .color(dark), lineWidth: side * 0.04)
        context.fill(Path(ellipseIn: circle.insetBy(dx: side * 0.36, dy: side * 0.36)), with: .color(Color(hex: 0xC0392B)))
        for index in 0..<8 {
            let angle = Double(index) * .pi / 4
            let radius = side * 0.39
            let dot = CGRect(x: circle.midX + CGFloat(cos(angle)) * radius - side * 0.035,
                             y: circle.midY + CGFloat(sin(angle)) * radius - side * 0.035,
                             width: side * 0.07, height: side * 0.07)
            context.fill(Path(ellipseIn: dot), with: .color(dark))
        }
    }

    /// Coppe: calice rosso con bordo arancio, stelo e base.
    private static func drawCup(in rect: CGRect, context: inout GraphicsContext) {
        let w = rect.width
        let h = rect.height
        let red = Color(hex: 0xC0392B)
        let orange = Color(hex: 0xF39C12)
        let top = rect.minY + h * 0.1
        let bowlBottom = rect.minY + h * 0.55
        var bowl = Path()
        bowl.move(to: CGPoint(x: rect.minX + w * 0.08, y: top))
        bowl.addLine(to: CGPoint(x: rect.maxX - w * 0.08, y: top))
        bowl.addQuadCurve(to: CGPoint(x: rect.midX, y: bowlBottom), control: CGPoint(x: rect.maxX - w * 0.05, y: bowlBottom))
        bowl.addQuadCurve(to: CGPoint(x: rect.minX + w * 0.08, y: top), control: CGPoint(x: rect.minX + w * 0.05, y: bowlBottom))
        bowl.closeSubpath()
        context.fill(bowl, with: .color(red))
        let rim = CGRect(x: rect.minX + w * 0.08, y: top - h * 0.04, width: w * 0.84, height: h * 0.08)
        context.fill(Path(ellipseIn: rim), with: .color(orange))
        let stem = CGRect(x: rect.midX - w * 0.06, y: bowlBottom - h * 0.02, width: w * 0.12, height: h * 0.25)
        context.fill(Path(roundedRect: stem, cornerRadius: w * 0.03), with: .color(red))
        let knot = CGRect(x: rect.midX - w * 0.12, y: bowlBottom + h * 0.08, width: w * 0.24, height: h * 0.07)
        context.fill(Path(ellipseIn: knot), with: .color(orange))
        let base = CGRect(x: rect.midX - w * 0.3, y: rect.minY + h * 0.78, width: w * 0.6, height: h * 0.12)
        context.fill(Path(ellipseIn: base), with: .color(red))
        context.stroke(Path(ellipseIn: base), with: .color(orange), lineWidth: w * 0.03)
    }

    /// Spade: spada blu verticale con elsa e pomolo.
    private static func drawSword(in rect: CGRect, context: inout GraphicsContext) {
        let w = rect.width
        let h = rect.height
        let blue = Color(hex: 0x1F4E9C)
        let steel = Color(hex: 0x8FB3E8)
        let guardY = rect.minY + h * 0.68
        var blade = Path()
        blade.move(to: CGPoint(x: rect.midX, y: rect.minY + h * 0.02))
        blade.addLine(to: CGPoint(x: rect.midX + w * 0.11, y: rect.minY + h * 0.14))
        blade.addLine(to: CGPoint(x: rect.midX + w * 0.09, y: guardY))
        blade.addLine(to: CGPoint(x: rect.midX - w * 0.09, y: guardY))
        blade.addLine(to: CGPoint(x: rect.midX - w * 0.11, y: rect.minY + h * 0.14))
        blade.closeSubpath()
        context.fill(blade, with: .color(steel))
        context.stroke(blade, with: .color(blue), lineWidth: w * 0.035)
        var ridge = Path()
        ridge.move(to: CGPoint(x: rect.midX, y: rect.minY + h * 0.08))
        ridge.addLine(to: CGPoint(x: rect.midX, y: guardY))
        context.stroke(ridge, with: .color(blue), lineWidth: w * 0.025)
        let crossGuard = CGRect(x: rect.midX - w * 0.38, y: guardY - h * 0.01, width: w * 0.76, height: h * 0.06)
        context.fill(Path(roundedRect: crossGuard, cornerRadius: h * 0.03), with: .color(blue))
        let grip = CGRect(x: rect.midX - w * 0.06, y: guardY + h * 0.05, width: w * 0.12, height: h * 0.17)
        context.fill(Path(roundedRect: grip, cornerRadius: w * 0.03), with: .color(Color(hex: 0x6D4C41)))
        let pommel = CGRect(x: rect.midX - w * 0.1, y: guardY + h * 0.21, width: w * 0.2, height: w * 0.2)
        context.fill(Path(ellipseIn: pommel), with: .color(blue))
    }

    /// Bastoni: randello verde nodoso con contorno marrone.
    private static func drawClub(in rect: CGRect, context: inout GraphicsContext) {
        let w = rect.width
        let h = rect.height
        let green = Color(hex: 0x2E7D32)
        let brown = Color(hex: 0x6D4C41)
        var club = Path()
        club.move(to: CGPoint(x: rect.midX - w * 0.08, y: rect.maxY - h * 0.04))
        club.addLine(to: CGPoint(x: rect.midX - w * 0.2, y: rect.minY + h * 0.14))
        club.addQuadCurve(to: CGPoint(x: rect.midX + w * 0.2, y: rect.minY + h * 0.14),
                          control: CGPoint(x: rect.midX, y: rect.minY - h * 0.04))
        club.addLine(to: CGPoint(x: rect.midX + w * 0.08, y: rect.maxY - h * 0.04))
        club.addQuadCurve(to: CGPoint(x: rect.midX - w * 0.08, y: rect.maxY - h * 0.04),
                          control: CGPoint(x: rect.midX, y: rect.maxY + h * 0.02))
        club.closeSubpath()
        context.fill(club, with: .color(green))
        context.stroke(club, with: .color(brown), lineWidth: w * 0.04)
        // Nodi sporgenti alternati.
        let knots: [(CGFloat, CGFloat)] = [(-0.2, 0.3), (0.19, 0.45), (-0.16, 0.62), (0.14, 0.76)]
        for (dx, dy) in knots {
            let knot = CGRect(x: rect.midX + w * dx - w * 0.07, y: rect.minY + h * dy - w * 0.07, width: w * 0.14, height: w * 0.14)
            context.fill(Path(ellipseIn: knot), with: .color(green))
            context.stroke(Path(ellipseIn: knot), with: .color(brown), lineWidth: w * 0.03)
        }
    }
}

/// Dorso: blu notte con reticolo rosso scuro e filetto ciano AeroChat.
struct CardBack: View {
    var body: some View {
        Canvas { context, size in
            let rect = CGRect(origin: .zero, size: size)
            context.fill(Path(rect), with: .color(Color(hex: 0x0F1B3D)))
            let step = max(6, size.width / 6)
            var lattice = Path()
            var x = -size.height
            while x < size.width + size.height {
                lattice.move(to: CGPoint(x: x, y: 0))
                lattice.addLine(to: CGPoint(x: x + size.height, y: size.height))
                lattice.move(to: CGPoint(x: x + size.height, y: 0))
                lattice.addLine(to: CGPoint(x: x, y: size.height))
                x += step
            }
            context.stroke(lattice, with: .color(Color(hex: 0x8E1B2E)), lineWidth: max(1, size.width * 0.025))
            let inset = size.width * 0.09
            let frame = rect.insetBy(dx: inset, dy: inset)
            context.stroke(Path(roundedRect: frame, cornerRadius: size.width * 0.07), with: .color(AeroTheme.accentCyan),
                           lineWidth: max(1, size.width * 0.03))
            let emblemSide = size.width * 0.36
            let emblem = CGRect(x: rect.midX - emblemSide / 2, y: rect.midY - emblemSide / 2, width: emblemSide, height: emblemSide)
            context.fill(Path(ellipseIn: emblem), with: .color(Color(hex: 0x0F1B3D)))
            context.stroke(Path(ellipseIn: emblem), with: .color(AeroTheme.accentCyan), lineWidth: max(1, size.width * 0.03))
        }
    }
}
