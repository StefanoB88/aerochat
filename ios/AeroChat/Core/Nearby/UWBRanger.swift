import Foundation
import NearbyInteraction
import Observation

/// Distanza precisa tra iPhone con chip UWB (iPhone 11 e successivi) tramite Nearby Interaction.
/// Una `NISession` per vicino: il nostro `NIDiscoveryToken` viaggia nel RADAR_PING verso quel vicino,
/// il suo arriva nel RADAR_PING di ritorno; quando entrambi i radar sono aperti la sessione misura
/// distanza (±10 cm) e, sui chip che la forniscono, direzione. Solo con il radar a schermo.
@MainActor
@Observable
final class UWBRanger: NSObject {

    /// Ultima misura per `node_id`; sparisce quando la sessione si ferma o la misura invecchia.
    private(set) var measurements: [Data: UWBMeasurement] = [:]
    /// L'utente ha negato "Interazioni nelle vicinanze": niente piu' token finche' non riapre l'app.
    private(set) var isDenied = false

    @ObservationIgnored private var sessions: [Data: NISession] = [:]
    /// Token del peer gia' usato per la sessione in corso (evita di riavviarla a ogni RADAR_PING).
    @ObservationIgnored private var peerTokens: [Data: Data] = [:]

    /// Una misura piu' vecchia di cosi' (peer fuori portata UWB, telefono in tasca) non viene mostrata.
    static let measurementLifetime: TimeInterval = 5
    static let maxTokenBytes = 1_024

    /// `true` sugli iPhone con chip U1/U2.
    static var isSupported: Bool {
        NISession.deviceCapabilities.supportsPreciseDistanceMeasurement
    }

    /// Token da mandare a `nodeID` (crea la sessione se serve). Nil se UWB non e' disponibile.
    func localToken(for nodeID: Data) -> Data? {
        guard Self.isSupported, !isDenied else { return nil }
        let session = sessions[nodeID] ?? makeSession(for: nodeID)
        guard let token = session.discoveryToken else { return nil }
        return try? NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true)
    }

    /// Token ricevuto da `nodeID`: avvia (o riavvia, se e' cambiato) la misura verso quel vicino.
    func receive(peerToken data: Data, from nodeID: Data) {
        guard Self.isSupported, !isDenied, data.count <= Self.maxTokenBytes, peerTokens[nodeID] != data,
              let token = try? NSKeyedUnarchiver.unarchivedObject(ofClass: NIDiscoveryToken.self, from: data) else { return }
        let session = sessions[nodeID] ?? makeSession(for: nodeID)
        peerTokens[nodeID] = data
        session.run(NINearbyPeerConfiguration(peerToken: token))
    }

    /// `true` se la sessione verso `nodeID` sta gia' usando un token del peer.
    func isRanging(with nodeID: Data) -> Bool {
        peerTokens[nodeID] != nil
    }

    /// Il vicino ha chiuso il radar o non e' piu' a portata.
    func stop(_ nodeID: Data) {
        sessions.removeValue(forKey: nodeID)?.invalidate()
        peerTokens.removeValue(forKey: nodeID)
        measurements.removeValue(forKey: nodeID)
    }

    /// Chiude le sessioni dei nodi non piu' vicini diretti e toglie le misure scadute.
    func prune(keeping nodeIDs: Set<Data>, now: Date = Date()) {
        for nodeID in sessions.keys where !nodeIDs.contains(nodeID) {
            stop(nodeID)
        }
        let fresh = measurements.filter { now.timeIntervalSince($0.value.updatedAt) < Self.measurementLifetime }
        if fresh.count != measurements.count { measurements = fresh }
    }

    /// Radar riaperto: si riprova anche se in passato l'utente ha negato il permesso (se e' ancora
    /// negato, la sessione lo segnala di nuovo e il radar spiega come riattivarlo).
    func resetDenial() {
        isDenied = false
    }

    func stopAll() {
        sessions.values.forEach { $0.invalidate() }
        sessions.removeAll()
        peerTokens.removeAll()
        if !measurements.isEmpty { measurements.removeAll() }
    }

    /// Misura ancora valida verso `nodeID`.
    func measurement(for nodeID: Data, now: Date = Date()) -> UWBMeasurement? {
        guard let measurement = measurements[nodeID],
              now.timeIntervalSince(measurement.updatedAt) < Self.measurementLifetime else { return nil }
        return measurement
    }

    private func makeSession(for nodeID: Data) -> NISession {
        let session = NISession()
        session.delegate = self
        session.delegateQueue = .main
        sessions[nodeID] = session
        return session
    }

    /// Le callback arrivano fuori dall'isolamento: la sessione si ritrova dal suo identificatore.
    private func entry(for id: ObjectIdentifier) -> (nodeID: Data, session: NISession)? {
        sessions.first { ObjectIdentifier($0.value) == id }.map { ($0.key, $0.value) }
    }

    fileprivate func update(_ id: ObjectIdentifier, meters: Double, azimuth: Double?) {
        guard let nodeID = entry(for: id)?.nodeID else { return }
        // La direzione arriva a intermittenza: se manca per un aggiornamento teniamo l'ultima.
        let previous = measurements[nodeID]
        measurements[nodeID] = UWBMeasurement(meters: meters, azimuth: azimuth ?? previous?.azimuth, updatedAt: Date())
    }

    fileprivate func peerRemoved(from id: ObjectIdentifier, timedOut: Bool) {
        guard let (nodeID, session) = entry(for: id) else { return }
        measurements.removeValue(forKey: nodeID)
        if timedOut, let configuration = session.configuration {
            // Fuori portata per un attimo: la stessa configurazione riprende appena il peer torna visibile.
            session.run(configuration)
        } else {
            // Il peer ha chiuso la sessione: al prossimo token la si riavvia da capo.
            peerTokens.removeValue(forKey: nodeID)
        }
    }

    fileprivate func invalidated(_ id: ObjectIdentifier, userDidNotAllow: Bool) {
        guard let nodeID = entry(for: id)?.nodeID else { return }
        sessions.removeValue(forKey: nodeID)
        peerTokens.removeValue(forKey: nodeID)
        measurements.removeValue(forKey: nodeID)
        if userDidNotAllow {
            isDenied = true
            stopAll()
        }
    }

    fileprivate func resume(_ id: ObjectIdentifier) {
        guard let session = entry(for: id)?.session, let configuration = session.configuration else { return }
        session.run(configuration)
    }
}

extension UWBRanger: NISessionDelegate {

    nonisolated func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        guard let object = nearbyObjects.first, let distance = object.distance else { return }
        // Direzione nel sistema dell'iPhone: x a destra, z uscente dallo schermo (davanti = -z).
        let azimuth = object.direction.map { Double(atan2($0.x, -$0.z)) }
        let id = ObjectIdentifier(session)
        MainActor.assumeIsolated {
            update(id, meters: Double(distance), azimuth: azimuth)
        }
    }

    nonisolated func session(_ session: NISession, didRemove nearbyObjects: [NINearbyObject], reason: NINearbyObject.RemovalReason) {
        let timedOut = reason == .timeout
        let id = ObjectIdentifier(session)
        MainActor.assumeIsolated {
            peerRemoved(from: id, timedOut: timedOut)
        }
    }

    nonisolated func sessionSuspensionEnded(_ session: NISession) {
        let id = ObjectIdentifier(session)
        MainActor.assumeIsolated {
            resume(id)
        }
    }

    nonisolated func session(_ session: NISession, didInvalidateWith error: Error) {
        let id = ObjectIdentifier(session)
        let userDidNotAllow = (error as? NIError)?.code == .userDidNotAllow
        MainActor.assumeIsolated {
            invalidated(id, userDidNotAllow: userDidNotAllow)
        }
    }
}
