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
        // Dopo l'arresto i `perform` diventano no-op espliciti invece di accodarsi a una runloop morta.
        lock.withLock { loop = nil }
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

    /// Ferma la runloop dopo aver eseguito i blocchi gia' accodati (es. le `close()` dei link),
    /// cosi' nessuno stream resta aperto. Il thread non e' riavviabile: serve una nuova istanza.
    func shutdown() {
        guard let runLoop else {
            cancel()
            return
        }
        runLoop.perform { [weak self] in
            self?.cancel()
            CFRunLoopStop(CFRunLoopGetCurrent())
        }
        CFRunLoopWakeUp(runLoop.getCFRunLoop())
    }
}
