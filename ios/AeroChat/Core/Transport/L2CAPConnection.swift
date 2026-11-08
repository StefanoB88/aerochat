import Foundation

/// Pompa dati bidirezionale su una coppia `InputStream`/`OutputStream` (quella di un
/// `CBL2CAPChannel`, oppure stream legati nei test) con framing `[UInt32 BE][payload]`.
///
/// Tutto lo stato mutabile e' confinato al thread della `RunLoopThread`: `send`/`close` sono
/// chiamabili da qualsiasi contesto e saltano su quel thread.
final class L2CAPConnection: NSObject, StreamDelegate, @unchecked Sendable {

    typealias FrameHandler = @Sendable (Data) -> Void
    typealias CloseHandler = @Sendable (Error?) -> Void

    let id: LinkID

    private let input: InputStream
    private let output: OutputStream
    private let thread: RunLoopThread
    private let onFrame: FrameHandler
    private let onClose: CloseHandler

    // Stato confinato al thread della runloop.
    private var parser = FrameCodec.Parser()
    /// Frame in uscita in ordine FIFO: si scrive dal primo a partire da `headOffset`, senza ricopiare
    /// il buffer a ogni scrittura parziale (prima era O(n^2) con code lunghe).
    private var outgoing: [Data] = []
    private var headOffset = 0
    private var readBuffer = [UInt8](repeating: 0, count: 16_384)
    private var isClosed = false
    private var bytesSent = 0
    private var bytesReceived = 0

    /// Byte accodati e non ancora scritti (letti anche da `send`, quindi protetti dal lock).
    private let pendingLock = NSLock()
    private var pendingBytes = 0
    /// Oltre questa coda il link e' di fatto bloccato (peer fuori portata): rifiutiamo nuovi frame
    /// invece di accumulare memoria; il router li mette in store-and-forward.
    static let maxPendingBytes = 4 * 1_048_576

    init(id: LinkID,
         inputStream: InputStream,
         outputStream: OutputStream,
         thread: RunLoopThread,
         onFrame: @escaping FrameHandler,
         onClose: @escaping CloseHandler) {
        self.id = id
        self.input = inputStream
        self.output = outputStream
        self.thread = thread
        self.onFrame = onFrame
        self.onClose = onClose
        super.init()
    }

    // MARK: - API

    /// Schedula gli stream sulla runloop dedicata e li apre.
    func open() {
        thread.perform { [self] in
            guard !isClosed else { return }
            input.delegate = self
            output.delegate = self
            input.schedule(in: .current, forMode: .default)
            output.schedule(in: .current, forMode: .default)
            input.open()
            output.open()
        }
    }

    /// Accoda un payload (viene incapsulato nel frame con prefisso di lunghezza).
    /// Lancia solo per payload vuoti o oltre il limite; l'I/O e' asincrono.
    func send(_ payload: Data) throws {
        let framed = try FrameCodec.frame(payload)
        try pendingLock.withLock {
            guard pendingBytes + framed.count <= Self.maxPendingBytes else {
                throw TransportError.writeFailed("backpressure")
            }
            pendingBytes += framed.count
        }
        thread.perform { [self] in
            guard !isClosed else { return }
            outgoing.append(framed)
            drainOutgoing()
        }
    }

    func close() {
        thread.perform { [self] in
            finish(error: nil)
        }
    }

    // MARK: - StreamDelegate

    func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case .hasBytesAvailable:
            readAvailable()
        case .hasSpaceAvailable:
            drainOutgoing()
        case .errorOccurred:
            finish(error: aStream.streamError ?? TransportError.writeFailed("stream error"))
        case .endEncountered:
            finish(error: nil)
        default:
            break
        }
    }

    // MARK: - I/O (solo sul thread della runloop)

    private func drainOutgoing() {
        while !isClosed, let head = outgoing.first, output.hasSpaceAvailable {
            let offset = headOffset
            let written = head.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) -> Int in
                guard let base = buffer.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return output.write(base + offset, maxLength: buffer.count - offset)
            }
            if written > 0 {
                bytesSent += written
                pendingLock.withLock { pendingBytes -= written }
                headOffset += written
                if headOffset >= head.count {
                    outgoing.removeFirst()
                    headOffset = 0
                }
            } else if written < 0 {
                finish(error: output.streamError ?? TransportError.writeFailed("write returned -1"))
                return
            } else {
                return
            }
        }
    }

    private func readAvailable() {
        while !isClosed, input.hasBytesAvailable {
            let count = input.read(&readBuffer, maxLength: readBuffer.count)
            if count > 0 {
                bytesReceived += count
                do {
                    let frames = try parser.append(Data(readBuffer[0..<count]))
                    for frame in frames {
                        onFrame(frame)
                    }
                } catch {
                    // Frame malformato: il peer non parla il nostro protocollo, chiudiamo il link.
                    finish(error: error)
                    return
                }
            } else if count < 0 {
                finish(error: input.streamError ?? TransportError.writeFailed("read returned -1"))
                return
            } else {
                return
            }
        }
    }

    private func finish(error: Error?) {
        guard !isClosed else { return }
        isClosed = true
        input.delegate = nil
        output.delegate = nil
        input.close()
        output.close()
        input.remove(from: .current, forMode: .default)
        output.remove(from: .current, forMode: .default)
        outgoing.removeAll()
        headOffset = 0
        pendingLock.withLock { pendingBytes = 0 }
        onClose(error)
    }
}
