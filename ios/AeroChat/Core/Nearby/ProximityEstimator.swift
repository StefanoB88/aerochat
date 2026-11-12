import Foundation

/// Filtro di Kalman 1D sull'RSSI di un link: l'RSSI grezzo del Bluetooth oscilla di ±6-10 dBm da una
/// lettura all'altra (corpi, sedili, riflessioni sulla fusoliera), il filtro lo rende stabile senza
/// ritardare troppo i movimenti reali. Il rumore di processo cresce col tempo trascorso dall'ultima misura.
struct RSSIFilter: Sendable, Equatable {

    private(set) var estimate: Double?
    private var variance: Double = 0
    private var updatedAt: Date?

    /// Varianza del rumore di misura (dBm²): deviazione tipica ~4 dBm.
    static let measurementNoise: Double = 16
    /// Varianza aggiunta per secondo trascorso (dBm²/s): quanto velocemente puo' cambiare la distanza.
    static let processNoisePerSecond: Double = 2
    /// Scarto oltre il quale una singola lettura viene considerata un picco e pesata come rumore.
    static let outlierThreshold: Double = 15

    /// Aggiunge una lettura e ritorna la stima filtrata (dBm arrotondati).
    @discardableResult
    mutating func add(_ rssi: Int, at date: Date = Date()) -> Int {
        let measurement = Double(rssi)
        guard let previous = estimate, let updatedAt else {
            estimate = measurement
            variance = Self.measurementNoise
            self.updatedAt = date
            return rssi
        }
        let elapsed = min(30, max(0.1, date.timeIntervalSince(updatedAt)))
        let predictedVariance = variance + Self.processNoisePerSecond * elapsed
        // Un picco isolato (es. qualcuno passa nel corridoio) pesa quattro volte meno.
        let innovation = measurement - previous
        let noise = abs(innovation) > Self.outlierThreshold ? Self.measurementNoise * 4 : Self.measurementNoise
        let gain = predictedVariance / (predictedVariance + noise)
        let next = previous + gain * innovation
        estimate = next
        variance = (1 - gain) * predictedVariance
        self.updatedAt = date
        return Int(next.rounded())
    }
}

/// Misura UWB (Nearby Interaction) verso un iPhone vicino: distanza al centimetro e, se il chip la
/// fornisce, direzione orizzontale.
struct UWBMeasurement: Sendable, Equatable {
    var meters: Double
    /// Angolo orizzontale in radianti: 0 = davanti alla parte alta dell'iPhone, positivo a destra.
    var azimuth: Double?
    var updatedAt: Date
}

/// Stima della distanza dal segnale radio, con lo stesso linguaggio per UWB e Bluetooth.
enum ProximityEstimator {

    /// RSSI tipico a 1 m tra due telefoni (iPhone e Android recenti trasmettono a potenze simili).
    static let rssiAtOneMeter: Double = -59
    /// Esponente di attenuazione: 2 nello spazio libero, piu' alto in cabina tra sedili e persone.
    static let pathLossExponent: Double = 2.3

    /// Distanza stimata dall'RSSI filtrato con il modello log-distance.
    static func meters(rssi: Int) -> Double {
        let exponent = (rssiAtOneMeter - Double(rssi)) / (10 * pathLossExponent)
        return min(60, max(0.3, pow(10, exponent)))
    }

    /// Distanza mostrata sul radar e nella lista: misurata (UWB) se c'e', altrimenti stimata dall'RSSI.
    static func meters(rssi: Int?, uwb: UWBMeasurement?) -> Double? {
        if let uwb { return uwb.meters }
        return rssi.map(meters(rssi:))
    }

    /// Raggio sul radar (0.1 centro ... 1 bordo) in scala logaritmica: 0,3 m al centro, 30 m al bordo.
    /// Gli anelli cadono cosi' a ~1 m, ~5 m e 30 m. Distanza sconosciuta = bordo.
    static func radiusFraction(meters: Double?) -> CGFloat {
        guard let meters else { return 1 }
        let clamped = min(farMeters, max(nearMeters, meters))
        return CGFloat(0.1 + 0.9 * log(clamped / nearMeters) / log(farMeters / nearMeters))
    }

    static let nearMeters: Double = 0.3
    static let farMeters: Double = 30
    /// Distanze degli anelli del radar.
    static let ringMeters: [Double] = [1, 5, 30]

    /// "1,4 m" (UWB, un decimale sotto i 10 m) o "≈ 3 m" (Bluetooth, arrotondato: la stima e' grossolana).
    static func formatted(meters: Double, precise: Bool) -> String {
        let style: Measurement<UnitLength>.FormatStyle
        if precise {
            style = .measurement(width: .abbreviated, usage: .asProvided,
                                 numberFormatStyle: .number.precision(.fractionLength(meters < 10 ? 1 : 0)))
            return Measurement(value: meters, unit: UnitLength.meters).formatted(style)
        }
        // Bluetooth: 1 m sotto i 5 m, poi a passi di 5 m.
        let rounded = meters < 5 ? max(1, meters.rounded()) : (meters / 5).rounded() * 5
        style = .measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(0)))
        return "≈ " + Measurement(value: rounded, unit: UnitLength.meters).formatted(style)
    }
}
