package com.stefanobrandi.aerochat.core.transport

import com.stefanobrandi.aerochat.core.protocol.FrameCodec
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Astrazione minima di un link che sa spedire frame: [L2CAPConnection] in produzione, finti nei test. */
interface FrameLink {
    val id: LinkID

    /** Accoda un payload (senza prefisso di lunghezza). Lancia per payload non validi, link chiuso o backpressure. */
    fun send(payload: ByteArray)
    fun close()
}

/**
 * Pompa dati bidirezionale su una coppia `InputStream`/`OutputStream` (quella di un `BluetoothSocket`
 * L2CAP CoC, oppure stream collegati nei test) con framing `[UInt32 BE][payload]` ([FrameCodec]).
 *
 * Gli stream di Android sono bloccanti: un thread lettore estrae i frame completi e chiama [onFrame]
 * (in ordine), un thread scrittore svuota la coda FIFO in uscita. `send`/`close` sono chiamabili da
 * qualsiasi thread. [onClose] viene chiamato esattamente una volta (anche per la `close()` locale).
 *
 * @param closeable risorsa da chiudere insieme agli stream (il `BluetoothSocket`): la chiusura sblocca
 *   le `read`/`write` in corso.
 */
class L2CAPConnection(
    override val id: LinkID,
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val closeable: Closeable? = null,
    private val onFrame: (ByteArray) -> Unit,
    private val onClose: (Throwable?) -> Unit,
) : FrameLink {

    private val outgoing = LinkedBlockingQueue<ByteArray>()
    private val opened = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** Byte accodati e non ancora scritti. */
    private val pendingBytes = AtomicInteger(0)
    private val bytesSentCounter = AtomicLong(0)
    private val bytesReceivedCounter = AtomicLong(0)

    val bytesSent: Long get() = bytesSentCounter.get()
    val bytesReceived: Long get() = bytesReceivedCounter.get()
    val isClosed: Boolean get() = closed.get()

    // MARK: - API

    /** Avvia i thread di lettura e scrittura. Idempotente. */
    fun open() {
        if (closed.get() || !opened.compareAndSet(false, true)) return
        Thread({ readLoop() }, "AeroMesh.L2CAP.read-$id").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "AeroMesh.L2CAP.write-$id").apply { isDaemon = true }.start()
    }

    /**
     * Accoda un payload (viene incapsulato nel frame con prefisso di lunghezza).
     * Lancia `FrameCodec.Failure` per payload vuoti o oltre il limite, [TransportError.LinkClosed]
     * se il link e' chiuso, [TransportError.WriteFailed] ("backpressure") se la coda e' piena; l'I/O e' asincrono.
     */
    override fun send(payload: ByteArray) {
        val framed = FrameCodec.frame(payload)
        if (closing.get() || closed.get()) throw TransportError.LinkClosed
        while (true) {
            val current = pendingBytes.get()
            if (current + framed.size > maxPendingBytes) throw TransportError.WriteFailed("backpressure")
            if (pendingBytes.compareAndSet(current, current + framed.size)) break
        }
        outgoing.put(framed)
        // Chiusura concorrente: la coda e' gia' stata svuotata, il frame viene semplicemente scartato.
        if (closed.get()) outgoing.clear()
    }

    /**
     * Chiusura ordinata: i frame gia' accodati vengono scritti, poi gli stream si chiudono.
     * Se lo scrittore e' bloccato (peer fuori portata) la chiusura viene forzata dopo [closeGraceMs].
     */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        if (!opened.get()) {
            finish(null)
            return
        }
        outgoing.put(CLOSE_MARKER)
        watchdog.schedule({ finish(null) }, closeGraceMs, TimeUnit.MILLISECONDS)
    }

    // MARK: - I/O (thread dedicati)

    private fun readLoop() {
        val parser = FrameCodec.Parser()
        val buffer = ByteArray(readBufferSize)
        try {
            while (!closed.get()) {
                val count = inputStream.read(buffer)
                if (count < 0) {
                    finish(null)
                    return
                }
                if (count == 0) {
                    // Per contratto `read` su un buffer non vuoto blocca o ritorna >= 1 (-1 a fine stream), ma
                    // alcuni stack BT ritornano 0: una pausa breve evita di occupare un core in un ciclo a vuoto.
                    try {
                        Thread.sleep(ZERO_READ_BACKOFF_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        finish(null)
                        return
                    }
                    continue
                }
                bytesReceivedCounter.addAndGet(count.toLong())
                val frames = try {
                    parser.append(buffer.copyOf(count))
                } catch (error: FrameCodec.Failure) {
                    // Frame malformato: il peer non parla il nostro protocollo, chiudiamo il link.
                    finish(error)
                    return
                }
                for (frame in frames) {
                    if (closed.get()) return
                    onFrame(frame)
                }
            }
        } catch (error: IOException) {
            finish(error)
        } catch (error: RuntimeException) {
            finish(error)
        }
    }

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val frame = outgoing.take()
                if (frame === CLOSE_MARKER || closed.get()) {
                    finish(null)
                    return
                }
                outputStream.write(frame)
                outputStream.flush()
                bytesSentCounter.addAndGet(frame.size.toLong())
                pendingBytes.addAndGet(-frame.size)
            }
        } catch (_: InterruptedException) {
            finish(null)
        } catch (error: IOException) {
            finish(error)
        } catch (error: RuntimeException) {
            finish(error)
        }
    }

    private fun finish(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        closing.set(true)
        outgoing.clear()
        // Sblocca lo scrittore in attesa sulla coda.
        outgoing.offer(CLOSE_MARKER)
        pendingBytes.set(0)
        closeQuietly(outputStream)
        closeQuietly(inputStream)
        closeable?.let { closeQuietly(it) }
        onClose(error)
    }

    private fun closeQuietly(resource: Closeable) {
        try {
            resource.close()
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        /**
         * Oltre questa coda il link e' di fatto bloccato (peer fuori portata): rifiutiamo nuovi frame
         * invece di accumulare memoria; il router li mette in store-and-forward.
         */
        const val maxPendingBytes = 4 * 1_048_576

        /** Attesa massima (ms) per lo svuotamento della coda durante una `close()` ordinata. */
        const val closeGraceMs = 1_500L

        private const val readBufferSize = 16_384

        /** Pausa (ms) dopo una `read` che ritorna 0 byte (stack BT non conformi). */
        private const val ZERO_READ_BACKOFF_MS = 10L

        /** Sentinella (confronto per identita') che chiede allo scrittore di chiudere. */
        private val CLOSE_MARKER = ByteArray(0)

        private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AeroMesh.L2CAP.watchdog").apply { isDaemon = true }
        }
    }
}
