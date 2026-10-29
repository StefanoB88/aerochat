import Foundation
#if canImport(WiFiAware)
import WiFiAware
#endif

/// Trasporto ad alta velocita' per file HD non compressi (> 1 MB), complementare al BLE.
/// Oggi nessuna implementazione e' operativa: l'app usa sempre il BLE; il router interroga
/// `HighSpeedTransportSelector` e ripiega sul BLE quando `isAvailable()` e' `false`.
protocol HighSpeedTransport: Sendable {
    var name: String { get }
    func isAvailable() async -> Bool
    /// Invia il file al nodo indicato. Lancia `HighSpeedTransportError.notAvailable` se il trasporto non e' pronto.
    func sendFile(at url: URL, to nodeID: Data, progress: (@Sendable (Double) -> Void)?) async throws
}

enum HighSpeedTransportError: Error, Equatable {
    case notAvailable(String)
    case fileNotFound
}

/// Wi-Fi Aware (iOS 26+): richiede il pairing di sistema tra dispositivi e la dichiarazione del
/// servizio `_aeromesh._tcp` nella chiave Info.plist `WiFiAwareServices`. Finche' il servizio non e'
/// dichiarato, `isAvailable()` e' `false` e il router resta sul BLE.
struct WiFiAwareTransport: HighSpeedTransport {

    static let serviceName = "_aeromesh._tcp"

    let name = "Wi-Fi Aware"

    func isAvailable() async -> Bool {
        #if canImport(WiFiAware)
        if #available(iOS 26.0, *) {
            let publishable = WAPublishableService.allServices[Self.serviceName] != nil
            let subscribable = WASubscribableService.allServices[Self.serviceName] != nil
            return publishable && subscribable
        }
        #endif
        return false
    }

    func sendFile(at url: URL, to nodeID: Data, progress: (@Sendable (Double) -> Void)?) async throws {
        guard FileManager.default.fileExists(atPath: url.path) else { throw HighSpeedTransportError.fileNotFound }
        throw HighSpeedTransportError.notAvailable(
            "Wi-Fi Aware richiede iOS 26, il pairing di sistema dei dispositivi e il servizio \(Self.serviceName) dichiarato in Info.plist"
        )
    }
}

/// Socket TCP su hotspot personale/locale: utile in crociera o in sala d'attesa quando un
/// passeggero condivide un hotspot. Non ancora operativo.
struct LocalHotspotTCPTransport: HighSpeedTransport {

    let name = "Hotspot TCP"
    let port: UInt16 = 47_117

    func isAvailable() async -> Bool { false }

    func sendFile(at url: URL, to nodeID: Data, progress: (@Sendable (Double) -> Void)?) async throws {
        guard FileManager.default.fileExists(atPath: url.path) else { throw HighSpeedTransportError.fileNotFound }
        throw HighSpeedTransportError.notAvailable("Nessun hotspot locale condiviso rilevato")
    }
}

/// Sceglie il primo trasporto veloce disponibile; `nil` => si resta sul BLE (chunk WebP/Opus).
struct HighSpeedTransportSelector: Sendable {

    let candidates: [any HighSpeedTransport]

    init(candidates: [any HighSpeedTransport] = [WiFiAwareTransport(), LocalHotspotTCPTransport()]) {
        self.candidates = candidates
    }

    func best() async -> (any HighSpeedTransport)? {
        for candidate in candidates where await candidate.isAvailable() {
            return candidate
        }
        return nil
    }
}
