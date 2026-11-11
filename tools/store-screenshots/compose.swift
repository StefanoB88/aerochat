// Composizione delle immagini promozionali per App Store / Google Play.
//
// Prende uno screenshot reale e disegna: sfondo con sfumatura diagonale (#3D6BFF -> #0A1A4F, i colori
// dell'icona), titolo in grassetto bianco (font di sistema, adattato a massimo 2 righe), sottotitolo
// opzionale piu' chiaro e lo screenshot ridimensionato sotto, con angoli arrotondati, cornice scura e
// ombra morbida. Uscita PNG senza canale alfa e senza metadati.
//
// Compilazione: swiftc -O -module-cache-path /tmp/mc compose.swift -o /tmp/compose
// Uso:          /tmp/compose --input shot.png --output out.png --width 1320 --height 2868 \
//                            --title "Titolo" [--subtitle "Sottotitolo"] [--corner 0.12]
// Feature graphic di Google Play (icona a sinistra, nome e slogan, telefono inclinato a destra):
//               /tmp/compose --feature --icon icona.png --input shot.png --output feature.png \
//                            --width 1024 --height 500 --title "AeroChat" --subtitle "Slogan"
// Ricodifica senza alfa ne' metadati (icona dello store): /tmp/compose --reencode --input in.png --output out.png

import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct Options {
    var input = ""
    var output = ""
    var width = 0
    var height = 0
    var title = ""
    var subtitle: String?
    /// Raggio degli angoli in frazione della larghezza dello screenshot (nil = automatico).
    var corner: CGFloat?
    /// Feature graphic (Google Play) invece dello screenshot promozionale.
    var feature = false
    var icon: String?
    /// Solo ricodifica dell'immagine di ingresso (stesse dimensioni, senza alfa e metadati).
    var reencode = false
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("compose: \(message)\n".utf8))
    exit(1)
}

func parseOptions() -> Options {
    var options = Options()
    var arguments = Array(CommandLine.arguments.dropFirst())
    func value() -> String {
        guard !arguments.isEmpty else { fail("valore mancante") }
        return arguments.removeFirst()
    }
    while !arguments.isEmpty {
        let key = arguments.removeFirst()
        switch key {
        case "--input": options.input = value()
        case "--output": options.output = value()
        case "--width": options.width = Int(value()) ?? 0
        case "--height": options.height = Int(value()) ?? 0
        case "--title": options.title = value()
        case "--subtitle": options.subtitle = value()
        case "--corner": options.corner = Double(value()).map { CGFloat($0) }
        case "--feature": options.feature = true
        case "--icon": options.icon = value()
        case "--reencode": options.reencode = true
        case "-h", "--help":
            print("uso: compose --input IN --output OUT --width W --height H --title T [--subtitle S] [--corner 0.12]")
            exit(0)
        default: fail("opzione sconosciuta \(key)")
        }
    }
    if options.reencode {
        guard !options.input.isEmpty, !options.output.isEmpty else { fail("servono --input e --output") }
        return options
    }
    guard !options.input.isEmpty, !options.output.isEmpty, options.width > 0, options.height > 0, !options.title.isEmpty else {
        fail("servono --input, --output, --width, --height e --title")
    }
    return options
}

func color(_ hex: UInt32, alpha: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}

func loadImage(_ path: String) -> CGImage {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fail("impossibile leggere \(path)") }
    return image
}

/// Testo centrato in un riquadro largo `width`: font, numero di righe e altezza.
struct TextBlock {
    let attributed: NSAttributedString
    let framesetter: CTFramesetter
    let size: CGSize
    let lines: Int
}

func makeBlock(_ text: String, font: CTFont, color textColor: CGColor, width: CGFloat, lineSpacing: CGFloat,
               alignment textAlignment: CTTextAlignment = .center) -> TextBlock {
    var alignment = textAlignment
    var spacing = lineSpacing
    var breakMode = CTLineBreakMode.byWordWrapping
    // I puntatori devono restare validi fino alla creazione dello stile.
    let paragraph = withUnsafeBytes(of: &alignment) { alignmentPtr in
        withUnsafeBytes(of: &spacing) { spacingPtr in
            withUnsafeBytes(of: &breakMode) { breakPtr in
                let settings = [
                    CTParagraphStyleSetting(spec: .alignment, valueSize: alignmentPtr.count, value: alignmentPtr.baseAddress!),
                    CTParagraphStyleSetting(spec: .lineSpacingAdjustment, valueSize: spacingPtr.count, value: spacingPtr.baseAddress!),
                    CTParagraphStyleSetting(spec: .lineBreakMode, valueSize: breakPtr.count, value: breakPtr.baseAddress!),
                ]
                return CTParagraphStyleCreate(settings, settings.count)
            }
        }
    }
    let attributed = NSAttributedString(string: text, attributes: [
        NSAttributedString.Key(kCTFontAttributeName as String): font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): textColor,
        NSAttributedString.Key(kCTParagraphStyleAttributeName as String): paragraph,
    ])
    let framesetter = CTFramesetterCreateWithAttributedString(attributed)
    let constraint = CGSize(width: width, height: .greatestFiniteMagnitude)
    let size = CTFramesetterSuggestFrameSizeWithConstraints(framesetter, CFRange(location: 0, length: 0), nil, constraint, nil)
    let path = CGPath(rect: CGRect(origin: .zero, size: CGSize(width: width, height: 100_000)), transform: nil)
    let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: 0, length: 0), path, nil)
    let lines = CFArrayGetCount(CTFrameGetLines(frame))
    // Una parola piu' larga del riquadro andrebbe spezzata: conta come "non entra".
    var widest: CGFloat = 0
    for word in text.split(separator: " ") {
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: String(word), attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): font]))
        widest = max(widest, CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil)))
    }
    return TextBlock(attributed: attributed, framesetter: framesetter,
                     size: CGSize(width: width, height: ceil(size.height)), lines: widest > width ? Int.max : lines)
}

/// Il titolo piu' grande possibile (fino a `maxSize`) che sta in 2 righe.
func fitTitle(_ text: String, width: CGFloat, maxSize: CGFloat) -> TextBlock {
    var size = maxSize
    while true {
        let font = CTFontCreateUIFontForLanguage(.emphasizedSystem, size, nil)
            ?? CTFontCreateWithName("Helvetica-Bold" as CFString, size, nil)
        let block = makeBlock(text, font: font, color: color(0xFFFFFF), width: width, lineSpacing: size * 0.04)
        if block.lines <= 2 || size <= 20 { return block }
        size *= 0.95
    }
}

func draw(_ block: TextBlock, in context: CGContext, topY: CGFloat, x: CGFloat, canvasHeight: CGFloat) {
    // Coordinate CoreGraphics con origine in basso: il riquadro parte a `topY` dall'alto.
    let rect = CGRect(x: x, y: canvasHeight - topY - block.size.height, width: block.size.width, height: block.size.height)
    let frame = CTFramesetterCreateFrame(block.framesetter, CFRange(location: 0, length: 0), CGPath(rect: rect, transform: nil), nil)
    CTFrameDraw(frame, context)
}

func roundedRect(_ rect: CGRect, radius: CGFloat) -> CGPath {
    CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
}

/// PNG senza alfa e senza metadati (vedi `strippedPNG`).
func writePNG(_ image: CGImage, to path: String) {
    let encoded = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(encoded, UTType.png.identifier as CFString, 1, nil) else {
        fail("impossibile codificare il PNG")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fail("codifica PNG non riuscita") }
    do {
        try strippedPNG(encoded as Data).write(to: URL(fileURLWithPath: path))
    } catch {
        fail("scrittura di \(path) non riuscita: \(error)")
    }
}

func makeOpaqueContext(width: Int, height: Int) -> (CGContext, CGColorSpace) {
    guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
          let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: colorSpace, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
        fail("impossibile creare il contesto grafico")
    }
    context.interpolationQuality = .high
    context.setShouldAntialias(true)
    return (context, colorSpace)
}

/// Il testo piu' grande possibile (fino a `maxSize`) che sta in `maxLines` righe, allineato a sinistra.
func fitLeft(_ text: String, width: CGFloat, maxSize: CGFloat, maxLines: Int, emphasized: Bool, alpha: CGFloat) -> TextBlock {
    var size = maxSize
    while true {
        let font = CTFontCreateUIFontForLanguage(emphasized ? .emphasizedSystem : .system, size, nil)
            ?? CTFontCreateWithName((emphasized ? "Helvetica-Bold" : "Helvetica") as CFString, size, nil)
        let block = makeBlock(text, font: font, color: color(0xFFFFFF, alpha: alpha), width: width,
                              lineSpacing: size * 0.08, alignment: .left)
        if block.lines <= maxLines || size <= 12 { return block }
        size *= 0.95
    }
}

/// Feature graphic: sfumatura, icona a sinistra, nome e slogan al centro, telefono inclinato a destra.
func composeFeatureGraphic(_ options: Options) {
    let width = CGFloat(options.width)
    let height = CGFloat(options.height)
    let (context, colorSpace) = makeOpaqueContext(width: options.width, height: options.height)
    let gradient = CGGradient(colorsSpace: colorSpace, colors: [color(0x3D6BFF), color(0x0A1A4F)] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: height), end: CGPoint(x: width, y: 0),
                               options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])

    // Telefono inclinato a destra (esce in basso dalla tela).
    let screenshot = loadImage(options.input)
    let phoneHeight = height * 1.15
    let phoneWidth = phoneHeight * CGFloat(screenshot.width) / CGFloat(screenshot.height)
    // Coordinate CoreGraphics (origine in basso): il centro sta sotto la meta', il bordo alto resta visibile.
    let center = CGPoint(x: width * 0.83, y: height * 0.36)
    context.saveGState()
    context.translateBy(x: center.x, y: center.y)
    context.rotate(by: -10 * .pi / 180)
    let shotRect = CGRect(x: -phoneWidth / 2, y: -phoneHeight / 2, width: phoneWidth, height: phoneHeight)
    let radius = phoneWidth * 0.125
    let bezel = phoneWidth * 0.022
    let bezelRect = shotRect.insetBy(dx: -bezel, dy: -bezel)
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -height * 0.012), blur: width * 0.03, color: color(0x000000, alpha: 0.55))
    context.addPath(roundedRect(bezelRect, radius: radius + bezel))
    context.setFillColor(color(0x05070D))
    context.fillPath()
    context.restoreGState()
    context.addPath(roundedRect(bezelRect.insetBy(dx: 1, dy: 1), radius: radius + bezel - 1))
    context.setStrokeColor(color(0xFFFFFF, alpha: 0.14))
    context.setLineWidth(2)
    context.strokePath()
    context.addPath(roundedRect(shotRect, radius: radius))
    context.clip()
    context.draw(screenshot, in: shotRect)
    context.restoreGState()

    // Icona con angoli arrotondati e ombra.
    let margin = height * 0.12
    let iconSize = height * 0.40
    let iconRect = CGRect(x: margin, y: (height - iconSize) / 2, width: iconSize, height: iconSize)
    if let iconPath = options.icon {
        let icon = loadImage(iconPath)
        context.saveGState()
        context.setShadow(offset: CGSize(width: 0, height: -height * 0.01), blur: height * 0.05, color: color(0x000000, alpha: 0.45))
        context.addPath(roundedRect(iconRect, radius: iconSize * 0.225))
        context.setFillColor(color(0x0A1A4F))
        context.fillPath()
        context.restoreGState()
        context.saveGState()
        context.addPath(roundedRect(iconRect, radius: iconSize * 0.225))
        context.clip()
        context.draw(icon, in: iconRect)
        context.restoreGState()
    }

    // Nome e slogan, centrati in verticale accanto all'icona.
    let textX = iconRect.maxX + height * 0.08
    let textWidth = width * 0.665 - textX
    let title = fitLeft(options.title, width: textWidth, maxSize: height * 0.19, maxLines: 1, emphasized: true, alpha: 1)
    let subtitleText = options.subtitle ?? ""
    let subtitle = fitLeft(subtitleText, width: textWidth, maxSize: height * 0.068, maxLines: 3, emphasized: false, alpha: 0.85)
    let gap = height * 0.03
    let total = title.size.height + (subtitleText.isEmpty ? 0 : gap + subtitle.size.height)
    let top = (height - total) / 2
    draw(title, in: context, topY: top, x: textX, canvasHeight: height)
    if !subtitleText.isEmpty {
        draw(subtitle, in: context, topY: top + title.size.height + gap, x: textX, canvasHeight: height)
    }
    guard let output = context.makeImage() else { fail("impossibile creare l'immagine") }
    writePNG(output, to: options.output)
}

/// Stessa immagine, ridisegnata su fondo opaco (l'icona di Play non deve avere trasparenza).
func reencode(_ options: Options) {
    let image = loadImage(options.input)
    let (context, _) = makeOpaqueContext(width: image.width, height: image.height)
    let rect = CGRect(x: 0, y: 0, width: image.width, height: image.height)
    context.setFillColor(color(0x0A1A4F))
    context.fill(rect)
    context.draw(image, in: rect)
    guard let output = context.makeImage() else { fail("impossibile creare l'immagine") }
    writePNG(output, to: options.output)
}

// MARK: - Composizione

let options = parseOptions()
if options.reencode {
    reencode(options)
    exit(0)
}
if options.feature {
    composeFeatureGraphic(options)
    exit(0)
}
let screenshot = loadImage(options.input)
let width = CGFloat(options.width)
let height = CGFloat(options.height)

guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
      let context = CGContext(data: nil, width: options.width, height: options.height, bitsPerComponent: 8, bytesPerRow: 0,
                              space: colorSpace, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
    fail("impossibile creare il contesto grafico")
}
context.interpolationQuality = .high
context.setShouldAntialias(true)

// Sfondo: sfumatura diagonale dall'angolo in alto a sinistra a quello in basso a destra.
let gradient = CGGradient(colorsSpace: colorSpace, colors: [color(0x3D6BFF), color(0x0A1A4F)] as CFArray, locations: [0, 1])!
context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: height), end: CGPoint(x: width, y: 0), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])

// Testi.
let isPortraitPhone = height / width > 1.6
let sideMargin = width * 0.07
let textWidth = width - 2 * sideMargin
let topMargin = height * (isPortraitPhone ? 0.055 : 0.05)
let title = fitTitle(options.title, width: textWidth, maxSize: width * (isPortraitPhone ? 0.085 : 0.06))
draw(title, in: context, topY: topMargin, x: sideMargin, canvasHeight: height)
var textBottom = topMargin + title.size.height
if let subtitleText = options.subtitle, !subtitleText.isEmpty {
    let titleFontSize = CTFontGetSize(title.attributed.attribute(NSAttributedString.Key(kCTFontAttributeName as String), at: 0, effectiveRange: nil) as! CTFont)
    let size = titleFontSize * 0.48
    let font = CTFontCreateUIFontForLanguage(.system, size, nil) ?? CTFontCreateWithName("Helvetica" as CFString, size, nil)
    let subtitle = makeBlock(subtitleText, font: font, color: color(0xFFFFFF, alpha: 0.78), width: textWidth, lineSpacing: size * 0.1)
    let gap = titleFontSize * 0.3
    draw(subtitle, in: context, topY: textBottom + gap, x: sideMargin, canvasHeight: height)
    textBottom += gap + subtitle.size.height
}

// Screenshot: tutto lo spazio rimasto sotto ai testi, proporzioni originali.
let shotTop = textBottom + height * 0.035
let bottomMargin = height * 0.045
let availableHeight = height - shotTop - bottomMargin
let availableWidth = width - 2 * sideMargin * (isPortraitPhone ? 1.1 : 1.0)
let imageWidth = CGFloat(screenshot.width)
let imageHeight = CGFloat(screenshot.height)
let scale = min(availableWidth / imageWidth, availableHeight / imageHeight)
let shotSize = CGSize(width: imageWidth * scale, height: imageHeight * scale)
let shotRect = CGRect(x: (width - shotSize.width) / 2, y: bottomMargin + (availableHeight - shotSize.height),
                      width: shotSize.width, height: shotSize.height)
let cornerRatio = options.corner ?? (imageHeight / imageWidth > 1.6 ? 0.125 : 0.035)
let radius = shotSize.width * cornerRatio
let bezel = shotSize.width * (imageHeight / imageWidth > 1.6 ? 0.022 : 0.016)
let bezelRect = shotRect.insetBy(dx: -bezel, dy: -bezel)

// Cornice scura con ombra morbida.
context.saveGState()
context.setShadow(offset: CGSize(width: 0, height: -height * 0.008), blur: width * 0.05, color: color(0x000000, alpha: 0.55))
context.addPath(roundedRect(bezelRect, radius: radius + bezel))
context.setFillColor(color(0x05070D))
context.fillPath()
context.restoreGState()
context.addPath(roundedRect(bezelRect.insetBy(dx: 1, dy: 1), radius: radius + bezel - 1))
context.setStrokeColor(color(0xFFFFFF, alpha: 0.14))
context.setLineWidth(max(2, width * 0.0015))
context.strokePath()

// Screenshot con gli angoli arrotondati.
context.saveGState()
context.addPath(roundedRect(shotRect, radius: radius))
context.clip()
context.draw(screenshot, in: shotRect)
context.restoreGState()

// PNG senza alfa e senza metadati: ImageIO scrive comunque un blocco eXIf (risoluzione), quindi il PNG
// viene ricostruito tenendo solo i blocchi necessari all'immagine (IHDR, sRGB, IDAT, IEND).
func strippedPNG(_ data: Data) -> Data {
    let bytes = [UInt8](data)
    let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    guard bytes.count > 8, Array(bytes[0..<8]) == signature else { fail("PNG non valido") }
    let keep: Set<String> = ["IHDR", "sRGB", "IDAT", "IEND"]
    var output = Data(signature)
    var index = 8
    while index + 12 <= bytes.count {
        let length = Int(bytes[index]) << 24 | Int(bytes[index + 1]) << 16 | Int(bytes[index + 2]) << 8 | Int(bytes[index + 3])
        let type = String(decoding: bytes[(index + 4)..<(index + 8)], as: UTF8.self)
        let end = index + 12 + length
        guard end <= bytes.count else { fail("PNG troncato") }
        if keep.contains(type) { output.append(contentsOf: bytes[index..<end]) }
        index = end
    }
    return output
}

let encoded = NSMutableData()
guard let output = context.makeImage(),
      let destination = CGImageDestinationCreateWithData(encoded, UTType.png.identifier as CFString, 1, nil) else {
    fail("impossibile codificare il PNG")
}
CGImageDestinationAddImage(destination, output, nil)
guard CGImageDestinationFinalize(destination) else { fail("codifica PNG non riuscita") }
do {
    try strippedPNG(encoded as Data).write(to: URL(fileURLWithPath: options.output))
} catch {
    fail("scrittura di \(options.output) non riuscita: \(error)")
}
