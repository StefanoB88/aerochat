import Testing
import Foundation
import CoreBluetooth
@testable import MyApp

@Suite("Trasporto BLE – framing su stream, registro link, politiche")
struct TransportTests {

    /// Coppia di connessioni collegate tra loro tramite stream legati (simula un canale L2CAP).
    private func makeLinkedPair(thread: RunLoopThread,
                                onFrameA: @escaping @Sendable (Data) -> Void,
                                onFrameB: @escaping @Sendable (Data) -> Void,
                                onCloseB: @escaping @Sendable (Error?) -> Void = { _ in }) -> (L2CAPConnection, L2CAPConnection) {
        var inputAToB: InputStream?
        var outputAToB: OutputStream?
        var inputBToA: InputStream?
        var outputBToA: OutputStream?
        Stream.getBoundStreams(withBufferSize: 4_096, inputStream: &inputAToB, outputStream: &outputAToB)
        Stream.getBoundStreams(withBufferSize: 4_096, inputStream: &inputBToA, outputStream: &outputBToA)
        guard let inputAToB, let outputAToB, let inputBToA, let outputBToA else {
            fatalError("Stream.getBoundStreams ha restituito nil")
        }
        let a = L2CAPConnection(id: LinkID(deviceID: UUID(), role: .central),
                                inputStream: inputBToA, outputStream: outputAToB,
                                thread: thread, onFrame: onFrameA, onClose: { _ in })
        let b = L2CAPConnection(id: LinkID(deviceID: UUID(), role: .peripheral),
                                inputStream: inputAToB, outputStream: outputBToA,
                                thread: thread, onFrame: onFrameB, onClose: onCloseB)
        return (a, b)
    }

    @Test("L2CAPConnection: frame grandi e piccoli attraversano stream legati con backpressure")
    func connectionPumpsFramesAcrossBoundStreams() async throws {
        let thread = RunLoopThread()
        thread.startAndWaitUntilReady()
        defer { thread.shutdown() }

        let payloads: [Data] = [
            Data("ciao".utf8),
            Data(repeating: 0xAB, count: 70_000),   // > buffer da 4 KB: piu' giri di hasSpaceAvailable
            Data((0..<300).map { UInt8($0 % 251) }),
        ]

        let received = AsyncStream<Data>.makeStream()
        let (a, b) = makeLinkedPair(thread: thread,
                                    onFrameA: { _ in },
                                    onFrameB: { received.continuation.yield($0) })
        a.open()
        b.open()
        for payload in payloads {
            try a.send(payload)
        }

        var collected: [Data] = []
        for await frame in received.stream {
            collected.append(frame)
            if collected.count == payloads.count { break }
        }
        #expect(collected == payloads)
        a.close()
        b.close()
    }

    @Test("L2CAPConnection: la chiusura remota chiude il link e notifica onClose")
    func connectionReportsRemoteClose() async throws {
        let thread = RunLoopThread()
        thread.startAndWaitUntilReady()
        defer { thread.shutdown() }

        let closed = AsyncStream<Bool>.makeStream()
        let (a, b) = makeLinkedPair(thread: thread,
                                    onFrameA: { _ in },
                                    onFrameB: { _ in },
                                    onCloseB: { _ in closed.continuation.yield(true) })
        a.open()
        b.open()
        try a.send(Data([1, 2, 3]))
        a.close()

        var iterator = closed.stream.makeAsyncIterator()
        let didClose = await iterator.next()
        #expect(didClose == true)
    }

    @Test("Payload vuoto rifiutato prima di toccare lo stream")
    func connectionRejectsEmptyPayload() throws {
        let thread = RunLoopThread()
        thread.startAndWaitUntilReady()
        defer { thread.shutdown() }
        let (a, _) = makeLinkedPair(thread: thread, onFrameA: { _ in }, onFrameB: { _ in })
        #expect(throws: FrameCodec.Failure.emptyFrame) { try a.send(Data()) }
    }

    // MARK: - Registro link dell'actor (con link finti, senza radio)

    final class FakeLink: FrameLink, @unchecked Sendable {
        let id: LinkID
        private let lock = NSLock()
        private var _sent: [Data] = []
        private var _closed = false
        var failSend = false

        init(id: LinkID) { self.id = id }

        var sent: [Data] { lock.withLock { _sent } }
        var isClosed: Bool { lock.withLock { _closed } }

        func send(_ payload: Data) throws {
            if failSend { throw TransportError.writeFailed("fake") }
            lock.withLock { _sent.append(payload) }
        }

        func close() { lock.withLock { _closed = true } }
    }

    @Test("Broadcast: una sola copia per node_id, esclusioni rispettate, link non identificati inclusi")
    func broadcastDeduplicatesByNodeID() async throws {
        let me = Data(repeating: 0x10, count: 8)
        let transport = BLETransportActor(localNodeID: me)
        let peerA = Data(repeating: 0xA0, count: 8)

        let deviceA1 = UUID(), deviceA2 = UUID(), deviceUnknown = UUID()
        // Il peer A e' raggiungibile su due link (abbiamo iniziato noi e lui ha iniziato con noi).
        let linkA1 = FakeLink(id: LinkID(deviceID: deviceA1, role: .peripheral))
        let linkA2 = FakeLink(id: LinkID(deviceID: deviceA2, role: .central))
        let linkUnknown = FakeLink(id: LinkID(deviceID: deviceUnknown, role: .central))

        // Nodo con id maggiore del nostro: entrambi i link restano (siamo noi a dover iniziare).
        await transport.register(link: linkA1, id: linkA1.id)
        await transport.register(link: linkA2, id: linkA2.id)
        await transport.register(link: linkUnknown, id: linkUnknown.id)
        await transport.identifyForTesting(deviceID: deviceA1, nodeID: peerA, role: .peripheral)
        await transport.identifyForTesting(deviceID: deviceA2, nodeID: peerA, role: .central)

        #expect(await transport.activeLinks().count == 3)
        let reached = await transport.broadcast(Data([0x01]), excluding: [])
        #expect(reached == 2, "una copia per peerA + una per il link non identificato")
        #expect(linkA1.sent.count + linkA2.sent.count == 1)
        #expect(linkUnknown.sent.count == 1)

        let reachedExcluding = await transport.broadcast(Data([0x02]), excluding: [linkUnknown.id])
        #expect(reachedExcluding == 1)
        #expect(linkUnknown.sent.count == 1)

        // Invio mirato: preferisce il link che abbiamo aperto noi (ruolo central).
        #expect(try await transport.send(Data([0x03]), toNode: peerA) == true)
        #expect(linkA2.sent.last == Data([0x03]))
        #expect(try await transport.send(Data([0x04]), toNode: Data(repeating: 0xFF, count: 8)) == false)
        await #expect(throws: TransportError.linkNotFound) {
            try await transport.send(Data([0x05]), to: LinkID(deviceID: UUID(), role: .central))
        }
    }

    @Test("Link duplicati: il nodo con node_id maggiore chiude il link che ha iniziato lui")
    func duplicateLinkResolutionPrefersSmallerInitiator() async throws {
        let me = Data(repeating: 0xF0, count: 8)      // noi siamo "maggiori"
        let peer = Data(repeating: 0x01, count: 8)
        let transport = BLETransportActor(localNodeID: me)
        #expect(transport.shouldInitiateConnection(to: peer) == false)

        let inbound = FakeLink(id: LinkID(deviceID: UUID(), role: .peripheral))
        let outbound = FakeLink(id: LinkID(deviceID: UUID(), role: .central))
        await transport.register(link: inbound, id: inbound.id)
        await transport.register(link: outbound, id: outbound.id)
        await transport.identifyForTesting(deviceID: inbound.id.deviceID, nodeID: peer, role: .peripheral)
        await transport.identifyForTesting(deviceID: outbound.id.deviceID, nodeID: peer, role: .central)

        let remaining = await transport.activeLinks()
        #expect(remaining.count == 1)
        #expect(remaining.first?.id == inbound.id)
        #expect(outbound.isClosed)
    }

    @Test("Eventi: linkOpened, linkIdentified, frameReceived e linkClosed arrivano in ordine agli iscritti")
    func eventStreamDeliversLifecycle() async throws {
        let transport = BLETransportActor(localNodeID: Data(repeating: 0x10, count: 8))
        let events = await transport.events()
        var iterator = events.makeAsyncIterator()

        let link = FakeLink(id: LinkID(deviceID: UUID(), role: .central))
        let peer = Data(repeating: 0x77, count: 8)
        await transport.register(link: link, id: link.id)
        await transport.identifyForTesting(deviceID: link.id.deviceID, nodeID: peer, role: .central)
        await transport.received(Data([9, 9]), from: link.id)
        await transport.closeLink(link.id)

        guard case .linkOpened(let opened)? = await iterator.next() else { Issue.record("atteso linkOpened"); return }
        #expect(opened.id == link.id)
        #expect(opened.nodeID == nil)
        #expect(await iterator.next() == .linkIdentified(link.id, nodeID: peer))
        #expect(await iterator.next() == .frameReceived(Data([9, 9]), from: link.id, nodeID: peer))
        #expect(await iterator.next() == .linkClosed(link.id, nodeID: peer))
        #expect(await transport.activeLinks().isEmpty)
    }

    @Test("Contratto GATT: PSM Little-Endian a 2 byte e advertising sotto i 31 byte")
    func gattContract() throws {
        #expect(UInt16(0x00C1).littleEndianBytes == Data([0xC1, 0x00]))
        #expect(UInt16(littleEndianBytes: Data([0xC1, 0x00])) == 0x00C1)
        #expect(MeshProtocol.estimatedLegacyAdvertisingBytes <= MeshProtocol.maxLegacyAdvertisingBytes)
        #expect(MeshProtocol.serviceCBUUID.uuidString == "F1164700-B1E0-4A18-9C0D-000000000001")
        #expect(MeshProtocol.psmCharacteristicCBUUID.data.count == 16)
    }

    @Test("Trasporti ad alta velocita': nessuno disponibile => si resta sul BLE")
    func highSpeedTransportsFallBackToBLE() async throws {
        let selector = HighSpeedTransportSelector()
        #expect(await selector.best() == nil)
        let missing = URL(fileURLWithPath: "/nonexistent/\(UUID().uuidString).jpg")
        await #expect(throws: HighSpeedTransportError.fileNotFound) {
            try await WiFiAwareTransport().sendFile(at: missing, to: Data(repeating: 1, count: 8), progress: nil)
        }
    }
}
