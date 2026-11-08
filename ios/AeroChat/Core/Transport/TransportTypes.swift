import Foundation
import CoreBluetooth

// MARK: - UUID BLE

extension MeshProtocol {
    // `CBUUID` non e' Sendable: proprieta' calcolate (costruzione economica) invece di static let.
    static var serviceCBUUID: CBUUID { CBUUID(string: serviceUUIDString) }
    static var psmCharacteristicCBUUID: CBUUID { CBUUID(string: psmCharacteristicUUIDString) }
    static var nodeInfoCharacteristicCBUUID: CBUUID { CBUUID(string: nodeInfoCharacteristicUUIDString) }

    /// Byte stimati del pacchetto Legacy Advertising: 3 (Flags AD) + 2 (header AD) + 16 (UUID 128 bit).
    static let estimatedLegacyAdvertisingBytes = 3 + 2 + 16

    /// Numero massimo di link L2CAP simultanei (batteria e banda 2.4 GHz).
    static let maxConcurrentLinks = 8
}

// MARK: - Stato radio

/// Stato della radio Bluetooth, mappa 1:1 `CBManagerState` (alimenta il banner "Modalita' Aereo").
enum RadioState: String, Sendable, Equatable {
    case unknown
    case resetting
    case unsupported
    case unauthorized
    case poweredOff
    case poweredOn

    init(_ state: CBManagerState) {
        switch state {
        case .poweredOn: self = .poweredOn
        case .poweredOff: self = .poweredOff
        case .unauthorized: self = .unauthorized
        case .unsupported: self = .unsupported
        case .resetting: self = .resetting
        case .unknown: self = .unknown
        @unknown default: self = .unknown
        }
    }

    var isReady: Bool { self == .poweredOn }
}

// MARK: - Link

/// Ruolo locale sul link: `central` = abbiamo aperto noi il canale, `peripheral` = ci hanno contattato.
enum LinkRole: String, Sendable, Hashable {
    case central
    case peripheral
}

/// Identificatore di un link fisico: UUID CoreBluetooth del dispositivo remoto + ruolo locale.
struct LinkID: Hashable, Sendable, CustomStringConvertible {
    let deviceID: UUID
    let role: LinkRole

    var description: String { "\(role.rawValue):\(deviceID.uuidString.prefix(8))" }
}

/// Link L2CAP attivo verso un vicino radio.
struct PeerLink: Sendable, Hashable, Identifiable {
    let id: LinkID
    /// `node_id` del vicino (noto dopo la lettura/scrittura della characteristic Node Info).
    var nodeID: Data?
    var rssi: Int?
    let connectedAt: Date

    var isIdentified: Bool { nodeID != nil }
}

// MARK: - Eventi

enum TransportEvent: Sendable, Equatable {
    case radioStateChanged(RadioState)
    case advertisingChanged(isAdvertising: Bool)
    case peerDiscovered(deviceID: UUID, rssi: Int)
    case linkOpened(PeerLink)
    case linkIdentified(LinkID, nodeID: Data)
    case linkClosed(LinkID, nodeID: Data?)
    case frameReceived(Data, from: LinkID, nodeID: Data?)
}

enum TransportError: Error, Equatable {
    case radioNotReady(RadioState)
    case linkNotFound
    case linkClosed
    case nodeUnreachable
    case writeFailed(String)
}

// MARK: - Provider

/// Astrazione del trasporto radio usata dal `MeshRouterActor`: BLE oggi, Wi-Fi Aware / TCP domani.
protocol TransportProvider: Sendable {
    var localNodeID: Data { get }
    func start() async
    func stop() async
    func events() async -> AsyncStream<TransportEvent>
    func radioState() async -> RadioState
    func activeLinks() async -> [PeerLink]
    /// Invia un frame (gia' serializzato, senza prefisso di lunghezza) su un link specifico.
    func send(_ frame: Data, to link: LinkID) async throws
    /// Invia a un nodo identificato; `false` se nessun link porta a quel `node_id`.
    func send(_ frame: Data, toNode nodeID: Data) async throws -> Bool
    /// Invia a tutti i vicini (una sola volta per `node_id`), escludendo i link indicati. Ritorna i link raggiunti.
    func broadcast(_ frame: Data, excluding: Set<LinkID>) async -> Int
    /// Compagni fidati da tenere in connessione perenne (UUID CoreBluetooth dei loro peripheral).
    func setTrustedPeripheralIdentifiers(_ identifiers: Set<UUID>) async
}

/// Wrapper `Sendable` per passare un `CBL2CAPChannel` dalla coda CoreBluetooth all'actor.
/// Il canale e' usato esclusivamente dal thread degli stream dopo l'adozione.
struct L2CAPChannelBox: @unchecked Sendable {
    let channel: CBL2CAPChannel
    var peerIdentifier: UUID { channel.peer.identifier }
}
