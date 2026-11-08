import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let size = 1024
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                    space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
func color(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: a)
}
// CoreGraphics: origine in basso a sinistra. Lavoriamo con y verso il basso per comodita'.
ctx.translateBy(x: 0, y: CGFloat(size)); ctx.scaleBy(x: 1, y: -1)

// 1. Cielo notturno (OLED): gradiente verticale blu notte -> quasi nero.
let sky = CGGradient(colorsSpace: space, colors: [color(0x0B2447), color(0x050A18), color(0x000000)] as CFArray,
                     locations: [0, 0.6, 1])!
ctx.drawLinearGradient(sky, start: CGPoint(x: 512, y: 0), end: CGPoint(x: 512, y: 1024), options: [])

// 2. Costellazione mesh: nodi collegati, discreti, dietro la bolla.
let nodes: [CGPoint] = [CGPoint(x: 120, y: 170), CGPoint(x: 300, y: 95), CGPoint(x: 560, y: 120),
                        CGPoint(x: 800, y: 80), CGPoint(x: 925, y: 250), CGPoint(x: 905, y: 560),
                        CGPoint(x: 950, y: 860), CGPoint(x: 700, y: 940), CGPoint(x: 90, y: 520)]
let edges = [(0,1),(1,2),(2,3),(3,4),(4,5),(5,6),(6,7),(0,8)]
ctx.setStrokeColor(color(0x00E5FF, 0.22)); ctx.setLineWidth(5)
for (a, b) in edges { ctx.move(to: nodes[a]); ctx.addLine(to: nodes[b]) }
ctx.strokePath()
for (i, n) in nodes.enumerated() {
    let r: CGFloat = i % 3 == 0 ? 15 : 10
    ctx.setFillColor(color(0x00E5FF, i % 3 == 0 ? 0.75 : 0.45))
    ctx.fillEllipse(in: CGRect(x: n.x - r, y: n.y - r, width: 2 * r, height: 2 * r))
}

// 3. Bolla chat con coda in basso a sinistra.
let bubbleRect = CGRect(x: 170, y: 230, width: 684, height: 540)
let body = CGPath(roundedRect: bubbleRect, cornerWidth: 190, cornerHeight: 190, transform: nil)
let tail = CGMutablePath()
tail.move(to: CGPoint(x: 280, y: 690))
tail.addQuadCurve(to: CGPoint(x: 205, y: 885), control: CGPoint(x: 290, y: 820))
tail.addQuadCurve(to: CGPoint(x: 460, y: 740), control: CGPoint(x: 340, y: 860))
tail.closeSubpath()
// Corpo e coda riempiti separatamente: come unico path i versi opposti si annullerebbero nella sovrapposizione.
let shapes: [CGPath] = [body, tail]

// Alone luminoso
ctx.saveGState()
ctx.setShadow(offset: .zero, blur: 70, color: color(0x00E5FF, 0.55))
ctx.beginTransparencyLayer(auxiliaryInfo: nil)
ctx.setFillColor(color(0x00E5FF))
for shape in shapes { ctx.addPath(shape); ctx.fillPath() }
ctx.endTransparencyLayer()
ctx.restoreGState()
// Gradiente ciano -> smeraldo (stesse coordinate per corpo e coda: nessuna giunzione visibile)
let fill = CGGradient(colorsSpace: space, colors: [color(0x2EF2FF), color(0x00C8F0), color(0x10B981)] as CFArray,
                      locations: [0, 0.45, 1])!
let gloss = CGGradient(colorsSpace: space, colors: [color(0xFFFFFF, 0.28), color(0xFFFFFF, 0)] as CFArray, locations: [0, 1])!
for shape in shapes {
    ctx.saveGState()
    ctx.addPath(shape); ctx.clip()
    ctx.drawLinearGradient(fill, start: CGPoint(x: 200, y: 230), end: CGPoint(x: 830, y: 900), options: [])
    ctx.drawLinearGradient(gloss, start: CGPoint(x: 512, y: 230), end: CGPoint(x: 512, y: 520), options: [])
    ctx.restoreGState()
}

// 4. Aereo (vista dall'alto), disegnato a mano, inclinato verso l'alto a destra.
let half: [CGPoint] = [
    CGPoint(x: 0.50, y: 0), CGPoint(x: 0.43, y: 0.05), CGPoint(x: 0.10, y: 0.05),
    CGPoint(x: -0.14, y: 0.44), CGPoint(x: -0.25, y: 0.44), CGPoint(x: -0.10, y: 0.05),
    CGPoint(x: -0.34, y: 0.05), CGPoint(x: -0.44, y: 0.18), CGPoint(x: -0.51, y: 0.18),
    CGPoint(x: -0.46, y: 0.035), CGPoint(x: -0.50, y: 0),
]
var outline = half
outline += half.dropFirst().dropLast().reversed().map { CGPoint(x: $0.x, y: -$0.y) }
let plane = CGMutablePath()
let scale: CGFloat = 460
let angle = -CGFloat.pi / 4  // muso verso l'alto a destra
let center = CGPoint(x: 512, y: 495)
for (i, p) in outline.enumerated() {
    let x = p.x * cos(angle) - p.y * sin(angle)
    let y = p.x * sin(angle) + p.y * cos(angle)
    let pt = CGPoint(x: center.x + x * scale, y: center.y + y * scale)
    i == 0 ? plane.move(to: pt) : plane.addLine(to: pt)
}
plane.closeSubpath()
ctx.saveGState()
ctx.setShadow(offset: CGSize(width: 0, height: 10), blur: 18, color: color(0x003040, 0.35))
ctx.addPath(plane)
ctx.setFillColor(color(0x061A33))
ctx.setStrokeColor(color(0x061A33))
ctx.setLineWidth(22); ctx.setLineJoin(.round)
ctx.drawPath(using: .fillStroke)
ctx.restoreGState()

let image = ctx.makeImage()!
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, image, nil)
CGImageDestinationFinalize(dest)
