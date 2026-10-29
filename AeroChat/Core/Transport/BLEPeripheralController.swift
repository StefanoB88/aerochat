import Foundation
import CoreBluetooth

/// Lato *peripheral* del dual-role: pubblica il canale L2CAP (senza cifratura di sistema, niente
/// pop-up di pairing), espone PSM e `node_id` via GATT e fa advertising del solo Service UUID.
///
/// Tutto lo stato e' confinato alla `queue` CoreBluetooth; gli eventi escono tramite `handler`.
final class BLEPeripheralController: NSObject, CBPeripheralManagerDelegate, @unchecked Sendable {

    enum Event: Sendable {
        case stateChanged(RadioState)
        case psmPublished(UInt16)
        case advertisingChanged(Bool)
        case centralWroteNodeID(centralID: UUID, nodeID: Data)
        case channelOpened(L2CAPChannelBox, centralID: UUID)
        case channelFailed(centralID: UUID?, reason: String)
    }

    private let queue: DispatchQueue
    private let localNodeID: Data
    private let restoreIdentifier: String
    private let handler: @Sendable (Event) -> Void

    private var manager: CBPeripheralManager?
    private var psm: CBL2CAPPSM = 0
    private var serviceAdded = false
    private var nodeInfoCharacteristic: CBMutableCharacteristic?
    private var isAdvertising = false
    private var wantsAdvertising = true

    init(localNodeID: Data,
         queue: DispatchQueue,
         restoreIdentifier: String,
         handler: @escaping @Sendable (Event) -> Void) {
        self.localNodeID = localNodeID
        self.queue = queue
        self.restoreIdentifier = restoreIdentifier
        self.handler = handler
        super.init()
    }

    // MARK: - API (thread-safe)

    func start() {
        queue.async { [self] in
            guard manager == nil else { return }
            manager = CBPeripheralManager(
                delegate: self,
                queue: queue,
                options: [
                    CBPeripheralManagerOptionRestoreIdentifierKey: restoreIdentifier,
                    CBPeripheralManagerOptionShowPowerAlertKey: false,
                ]
            )
        }
    }

    func stop() {
        queue.async { [self] in
            wantsAdvertising = false
            guard let manager else { return }
            if manager.isAdvertising { manager.stopAdvertising() }
            if psm != 0 { manager.unpublishL2CAPChannel(psm) }
            manager.removeAllServices()
            serviceAdded = false
            psm = 0
            isAdvertising = false
            self.manager = nil
            handler(.advertisingChanged(false))
        }
    }

    func setAdvertising(_ enabled: Bool) {
        queue.async { [self] in
            wantsAdvertising = enabled
            guard let manager, manager.state == .poweredOn else { return }
            if enabled {
                startAdvertisingIfReady()
            } else if manager.isAdvertising {
                manager.stopAdvertising()
                isAdvertising = false
                handler(.advertisingChanged(false))
            }
        }
    }

    // MARK: - Setup

    private func setUpIfNeeded() {
        guard let manager, manager.state == .poweredOn else { return }
        if psm == 0 {
            // 1. Canale L2CAP senza encryption di sistema: la sicurezza e' E2EE a livello app.
            manager.publishL2CAPChannel(withEncryption: false)
        } else if !serviceAdded {
            addService()
        } else {
            startAdvertisingIfReady()
        }
    }

    private func addService() {
        guard let manager, psm != 0 else { return }
        let service = CBMutableService(type: MeshProtocol.serviceCBUUID, primary: true)

        // PSM: valore statico, 2 byte UInt16 Little-Endian (sola lettura).
        let psmCharacteristic = CBMutableCharacteristic(
            type: MeshProtocol.psmCharacteristicCBUUID,
            properties: [.read],
            value: UInt16(psm).littleEndianBytes,
            permissions: [.readable]
        )
        // Node Info: valore dinamico (Read / Write Without Response / Notify) con gli 8 byte del node_id.
        let nodeInfo = CBMutableCharacteristic(
            type: MeshProtocol.nodeInfoCharacteristicCBUUID,
            properties: [.read, .writeWithoutResponse, .notify],
            value: nil,
            permissions: [.readable, .writeable]
        )
        nodeInfoCharacteristic = nodeInfo
        service.characteristics = [psmCharacteristic, nodeInfo]
        manager.add(service)
    }

    private func startAdvertisingIfReady() {
        guard let manager, wantsAdvertising, serviceAdded, !manager.isAdvertising else { return }
        // Solo Flags + Service UUID 128 bit: il pacchetto legacy resta sotto i 31 byte.
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [MeshProtocol.serviceCBUUID]])
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        handler(.stateChanged(RadioState(peripheral.state)))
        switch peripheral.state {
        case .poweredOn:
            setUpIfNeeded()
        default:
            serviceAdded = false
            psm = 0
            if isAdvertising {
                isAdvertising = false
                handler(.advertisingChanged(false))
            }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        // State Restoration: iOS ha rilanciato l'app in background per un evento BLE.
        if let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService],
           let ours = services.first(where: { $0.uuid == MeshProtocol.serviceCBUUID }) {
            serviceAdded = true
            nodeInfoCharacteristic = ours.characteristics?
                .compactMap { $0 as? CBMutableCharacteristic }
                .first { $0.uuid == MeshProtocol.nodeInfoCharacteristicCBUUID }
            if let psmCharacteristic = ours.characteristics?.first(where: { $0.uuid == MeshProtocol.psmCharacteristicCBUUID }),
               let value = psmCharacteristic.value,
               let restored = UInt16(littleEndianBytes: value) {
                psm = CBL2CAPPSM(restored)
            }
        }
        if dict[CBPeripheralManagerRestoredStateAdvertisementDataKey] != nil {
            isAdvertising = true
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        guard error == nil, PSM != 0 else {
            handler(.channelFailed(centralID: nil, reason: "publishL2CAPChannel: \(error?.localizedDescription ?? "PSM 0")"))
            return
        }
        psm = PSM
        handler(.psmPublished(UInt16(PSM)))
        if serviceAdded {
            // Servizio ripristinato con un vecchio PSM: lo ripubblichiamo con quello nuovo.
            peripheral.removeAllServices()
            serviceAdded = false
        }
        addService()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else {
            handler(.channelFailed(centralID: nil, reason: "addService: \(error?.localizedDescription ?? "")"))
            return
        }
        serviceAdded = true
        startAdvertisingIfReady()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        isAdvertising = error == nil
        handler(.advertisingChanged(isAdvertising))
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        switch request.characteristic.uuid {
        case MeshProtocol.nodeInfoCharacteristicCBUUID:
            guard request.offset <= localNodeID.count else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = localNodeID.subdata(in: request.offset..<localNodeID.count)
            peripheral.respond(to: request, withResult: .success)
        case MeshProtocol.psmCharacteristicCBUUID:
            request.value = UInt16(psm).littleEndianBytes
            peripheral.respond(to: request, withResult: .success)
        default:
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        // Handshake fallback: il central ci scrive il proprio node_id prima di aprire l'L2CAP.
        for request in requests where request.characteristic.uuid == MeshProtocol.nodeInfoCharacteristicCBUUID {
            guard let value = request.value, value.count == MeshProtocol.nodeIDLength else { continue }
            handler(.centralWroteNodeID(centralID: request.central.identifier, nodeID: value))
        }
        // Le scritture sono "without response": nessun respond() necessario.
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == MeshProtocol.nodeInfoCharacteristicCBUUID, let nodeInfoCharacteristic else { return }
        _ = peripheral.updateValue(localNodeID, for: nodeInfoCharacteristic, onSubscribedCentrals: [central])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let channel, error == nil else {
            handler(.channelFailed(centralID: channel?.peer.identifier, reason: error?.localizedDescription ?? "nil channel"))
            return
        }
        handler(.channelOpened(L2CAPChannelBox(channel: channel), centralID: channel.peer.identifier))
    }
}
