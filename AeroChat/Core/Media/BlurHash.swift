import Foundation
import CoreGraphics

/// Encoder/decoder BlurHash (https://blurha.sh) – 4x3 componenti => 28 caratteri ASCII,
/// inviati nell'header del messaggio per l'anteprima istantanea prima dei chunk WebP.
enum BlurHash {

    enum Failure: Error, Equatable {
        case invalidComponents
        case invalidImage
        case invalidHash
    }

    static let defaultComponentsX = 4
    static let defaultComponentsY = 3

    private static let alphabet = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~")
    private static let alphabetIndex: [Character: Int] = {
        var map: [Character: Int] = [:]
        for (index, character) in alphabet.enumerated() { map[character] = index }
        return map
    }()

    // MARK: - Encode

    /// Calcola il BlurHash di un'immagine. L'immagine viene prima ridotta (max 32 px) per velocita'.
    static func encode(_ image: CGImage, componentsX: Int = defaultComponentsX, componentsY: Int = defaultComponentsY) throws -> String {
        guard (1...9).contains(componentsX), (1...9).contains(componentsY) else { throw Failure.invalidComponents }
        let pixels = try RGBAPixels(image: image, maxSide: 32)
        return try encode(pixels: pixels.data, width: pixels.width, height: pixels.height, componentsX: componentsX, componentsY: componentsY)
    }

    /// Encode su buffer RGBA (4 byte per pixel, riga per riga).
    static func encode(pixels: [UInt8], width: Int, height: Int, componentsX: Int, componentsY: Int) throws -> String {
        guard (1...9).contains(componentsX), (1...9).contains(componentsY) else { throw Failure.invalidComponents }
        guard width > 0, height > 0, pixels.count >= width * height * 4 else { throw Failure.invalidImage }

        // Tabella sRGB -> lineare per evitare pow() per pixel.
        let linearTable = (0..<256).map { sRGBToLinear(Double($0) / 255) }
        var linear = [Double](repeating: 0, count: width * height * 3)
        for i in 0..<(width * height) {
            linear[i * 3] = linearTable[Int(pixels[i * 4])]
            linear[i * 3 + 1] = linearTable[Int(pixels[i * 4 + 1])]
            linear[i * 3 + 2] = linearTable[Int(pixels[i * 4 + 2])]
        }

        var factors: [(Double, Double, Double)] = []
        factors.reserveCapacity(componentsX * componentsY)
        for y in 0..<componentsY {
            for x in 0..<componentsX {
                let normalisation: Double = (x == 0 && y == 0) ? 1 : 2
                var r = 0.0, g = 0.0, b = 0.0
                for py in 0..<height {
                    let cosY = cos(Double.pi * Double(y) * Double(py) / Double(height))
                    for px in 0..<width {
                        let basis = normalisation * cos(Double.pi * Double(x) * Double(px) / Double(width)) * cosY
                        let index = (py * width + px) * 3
                        r += basis * linear[index]
                        g += basis * linear[index + 1]
                        b += basis * linear[index + 2]
                    }
                }
                let scale = 1 / Double(width * height)
                factors.append((r * scale, g * scale, b * scale))
            }
        }

        let dc = factors[0]
        let ac = factors.dropFirst()

        var hash = encode83((componentsX - 1) + (componentsY - 1) * 9, length: 1)
        let maximumValue: Double
        if !ac.isEmpty {
            let actualMaximum = ac.map { max(abs($0.0), abs($0.1), abs($0.2)) }.max() ?? 0
            let quantised = Int(max(0, min(82, floor(actualMaximum * 166 - 0.5))))
            maximumValue = Double(quantised + 1) / 166
            hash += encode83(quantised, length: 1)
        } else {
            maximumValue = 1
            hash += encode83(0, length: 1)
        }
        hash += encode83(encodeDC(dc), length: 4)
        for factor in ac {
            hash += encode83(encodeAC(factor, maximumValue: maximumValue), length: 2)
        }
        return hash
    }

    // MARK: - Decode

    /// Decodifica in un buffer RGBA `width x height` (tipicamente 32x32, poi scalato dalla UI).
    static func decode(_ hash: String, width: Int, height: Int, punch: Double = 1) throws -> [UInt8] {
        let characters = Array(hash)
        guard characters.count >= 6 else { throw Failure.invalidHash }
        let sizeFlag = try decode83(characters[0...0])
        let componentsY = sizeFlag / 9 + 1
        let componentsX = sizeFlag % 9 + 1
        guard characters.count == 4 + 2 * componentsX * componentsY else { throw Failure.invalidHash }

        let quantisedMaximum = try decode83(characters[1...1])
        let maximumValue = Double(quantisedMaximum + 1) / 166

        var colors: [(Double, Double, Double)] = []
        colors.reserveCapacity(componentsX * componentsY)
        for i in 0..<(componentsX * componentsY) {
            if i == 0 {
                colors.append(decodeDC(try decode83(characters[2...5])))
            } else {
                let start = 4 + i * 2
                colors.append(decodeAC(try decode83(characters[start...(start + 1)]), maximumValue: maximumValue * punch))
            }
        }

        var pixels = [UInt8](repeating: 255, count: width * height * 4)
        for y in 0..<height {
            for x in 0..<width {
                var r = 0.0, g = 0.0, b = 0.0
                for j in 0..<componentsY {
                    let cosY = cos(Double.pi * Double(y) * Double(j) / Double(height))
                    for i in 0..<componentsX {
                        let basis = cos(Double.pi * Double(x) * Double(i) / Double(width)) * cosY
                        let color = colors[i + j * componentsX]
                        r += color.0 * basis
                        g += color.1 * basis
                        b += color.2 * basis
                    }
                }
                let index = (y * width + x) * 4
                pixels[index] = linearToSRGB(r)
                pixels[index + 1] = linearToSRGB(g)
                pixels[index + 2] = linearToSRGB(b)
            }
        }
        return pixels
    }

    /// Colore medio (DC) del BlurHash come componenti sRGB 0...1.
    static func averageColor(_ hash: String) throws -> (red: Double, green: Double, blue: Double) {
        let characters = Array(hash)
        guard characters.count >= 6 else { throw Failure.invalidHash }
        let dc = decodeDC(try decode83(characters[2...5]))
        return (Double(linearToSRGB(dc.0)) / 255, Double(linearToSRGB(dc.1)) / 255, Double(linearToSRGB(dc.2)) / 255)
    }

    /// `CGImage` RGBA dal BlurHash (per `Image(decorative:)` in SwiftUI).
    static func image(_ hash: String, width: Int = 32, height: Int = 32, punch: Double = 1) -> CGImage? {
        guard let pixels = try? decode(hash, width: width, height: height, punch: punch) else { return nil }
        return RGBAPixels.makeImage(from: pixels, width: width, height: height)
    }

    static func isValid(_ hash: String) -> Bool {
        (try? decode(hash, width: 1, height: 1)) != nil
    }

    // MARK: - Base 83 e colori

    private static func encode83(_ value: Int, length: Int) -> String {
        var result = ""
        for i in 1...length {
            let digit = (value / Int(pow(83, Double(length - i)))) % 83
            result.append(alphabet[digit])
        }
        return result
    }

    private static func decode83(_ characters: ArraySlice<Character>) throws -> Int {
        var value = 0
        for character in characters {
            guard let digit = alphabetIndex[character] else { throw Failure.invalidHash }
            value = value * 83 + digit
        }
        return value
    }

    private static func encodeDC(_ value: (Double, Double, Double)) -> Int {
        (Int(linearToSRGB(value.0)) << 16) + (Int(linearToSRGB(value.1)) << 8) + Int(linearToSRGB(value.2))
    }

    private static func decodeDC(_ value: Int) -> (Double, Double, Double) {
        (sRGBToLinear(Double(value >> 16) / 255), sRGBToLinear(Double((value >> 8) & 255) / 255), sRGBToLinear(Double(value & 255) / 255))
    }

    private static func encodeAC(_ value: (Double, Double, Double), maximumValue: Double) -> Int {
        func quantise(_ component: Double) -> Int {
            Int(max(0, min(18, floor(signPow(component / maximumValue, 0.5) * 9 + 9.5))))
        }
        return quantise(value.0) * 19 * 19 + quantise(value.1) * 19 + quantise(value.2)
    }

    private static func decodeAC(_ value: Int, maximumValue: Double) -> (Double, Double, Double) {
        let quantR = value / (19 * 19)
        let quantG = (value / 19) % 19
        let quantB = value % 19
        return (signPow((Double(quantR) - 9) / 9, 2) * maximumValue,
                signPow((Double(quantG) - 9) / 9, 2) * maximumValue,
                signPow((Double(quantB) - 9) / 9, 2) * maximumValue)
    }

    private static func signPow(_ value: Double, _ exponent: Double) -> Double {
        copysign(pow(abs(value), exponent), value)
    }

    private static func sRGBToLinear(_ value: Double) -> Double {
        value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
    }

    private static func linearToSRGB(_ value: Double) -> UInt8 {
        let v = max(0, min(1, value))
        let srgb = v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1 / 2.4) - 0.055
        return UInt8(max(0, min(255, (srgb * 255 + 0.5).rounded(.down))))
    }
}

/// Buffer RGBA 8 bit estratto da un `CGImage` (opzionalmente ridotto a `maxSide`).
struct RGBAPixels {
    let width: Int
    let height: Int
    let data: [UInt8]

    init(image: CGImage, maxSide: Int? = nil) throws {
        var width = image.width
        var height = image.height
        if let maxSide, max(width, height) > maxSide {
            let scale = Double(maxSide) / Double(max(width, height))
            width = max(1, Int(Double(width) * scale))
            height = max(1, Int(Double(height) * scale))
        }
        var buffer = [UInt8](repeating: 0, count: width * height * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        let drawn = buffer.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: colorSpace, bitmapInfo: bitmapInfo) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { throw BlurHash.Failure.invalidImage }
        self.width = width
        self.height = height
        self.data = buffer
    }

    static func makeImage(from pixels: [UInt8], width: Int, height: Int) -> CGImage? {
        guard pixels.count >= width * height * 4 else { return nil }
        let data = Data(pixels)
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: bitmapInfo, provider: provider,
                       decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}
