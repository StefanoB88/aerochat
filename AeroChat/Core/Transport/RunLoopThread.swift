import Foundation

/// Thread dedicato con `RunLoop` attivo: gli `InputStream`/`OutputStream` di `CBL2CAPChannel`
/// richiedono una runloop per consegnare gli eventi, e non vogliamo occupare il main thread
/// con il traffico radio.
final class RunLoopThread: Thread, @unchecked Sendable {

    private let ready = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var loop: RunLoop?

    override init() {
        super.init()
        name = "AeroMesh.L2CAP"
        qualityOfService = .userInitiated
    }

    override func main() {
        let current = RunLoop.current
        lock.withLock { loop = current }
        // Una Port fittizia tiene viva la runloop anche senza stream schedulati.
        current.add(Port(), forMode: .default)
        ready.signal()
        while !isCancelled {
            _ = current.run(mode: .default, before: .distantFuture)
        }
    }

    /// Avvia il thread e attende che la runloop sia pronta a ricevere blocchi.
    func startAndWaitUntilReady() {
        guard !isExecuting, !isFinished else { return }
        start()
        ready.wait()
    }

    /// La runloop del thread (nil prima dell'avvio).
    var runLoop: RunLoop? {
        lock.withLock { loop }
    }

    /// Esegue `block` sul thread della runloop (asincrono, FIFO).
    func perform(_ block: @escaping @Sendable () -> Void) {
        guard let runLoop else { return }
        runLoop.perform(block)
        CFRunLoopWakeUp(runLoop.getCFRunLoop())
    }

    /// Ferma la runloop (il thread termina al prossimo giro).
    func shutdown() {
        cancel()
        guard let runLoop else { return }
        CFRunLoopStop(runLoop.getCFRunLoop())
    }
}
