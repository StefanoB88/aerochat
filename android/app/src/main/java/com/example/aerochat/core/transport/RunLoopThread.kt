package com.example.aerochat.core.transport

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Coda seriale dedicata del trasporto BLE (equivalente della `DispatchQueue` CoreBluetooth
 * "AeroMesh.BLE" di iOS; il nome resta quello del file Swift).
 *
 * Su iOS questa classe era un thread con `RunLoop` per gli stream di `CBL2CAPChannel`. Su Android
 * gli stream di `BluetoothSocket` sono bloccanti e hanno i propri thread in [L2CAPConnection]; qui
 * serve invece un unico thread su cui confinare tutto lo stato dei controller GATT, perche' i callback
 * di `BluetoothGattCallback`, `BluetoothGattServerCallback`, `ScanCallback` e `AdvertiseCallback`
 * arrivano su thread binder/main diversi. Ogni callback viene "postato" qui in ordine FIFO.
 *
 * Non usa `Looper`, quindi funziona anche nei test JVM.
 */
class RunLoopThread(name: String = "AeroMesh.BLE") {

    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }.apply {
        // Dopo `shutdown` i timer pendenti (backoff, timeout) non devono piu' scattare.
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
        removeOnCancelPolicy = true
    }

    @Volatile
    private var thread: Thread? = null

    /** `true` se il chiamante e' gia' sul thread della coda. */
    val isCurrent: Boolean get() = Thread.currentThread() === thread

    /** Esegue [block] sul thread della coda (asincrono, FIFO). No-op dopo [shutdown]. */
    fun perform(block: () -> Unit) {
        try {
            executor.execute { runGuarded(block) }
        } catch (_: RejectedExecutionException) {
            // Coda gia' fermata: come un `perform` su una runloop morta.
        }
    }

    /** Esegue [block] dopo [delayMs] millisecondi. Ritorna il futuro per annullarlo (o `null` se fermata). */
    fun performAfter(delayMs: Long, block: () -> Unit): ScheduledFuture<*>? =
        try {
            executor.schedule({ runGuarded(block) }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }

    /** Timer periodico (primo scatto dopo [periodMs]). */
    fun performRepeating(periodMs: Long, block: () -> Unit): ScheduledFuture<*>? =
        try {
            executor.scheduleWithFixedDelay({ runGuarded(block) }, periodMs, periodMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }

    /**
     * Ferma la coda dopo aver eseguito i blocchi gia' accodati (es. gli `stop()` dei controller),
     * scartando i timer. Non e' riavviabile: serve una nuova istanza.
     */
    fun shutdown() {
        executor.shutdown()
    }

    val isShutdown: Boolean get() = executor.isShutdown

    private fun runGuarded(block: () -> Unit) {
        thread = Thread.currentThread()
        try {
            block()
        } catch (error: SecurityException) {
            // Permesso revocato a runtime: i controller lo rilevano al prossimo controllo di stato.
            android.util.Log.w("AeroMesh.BLE", "SecurityException: ${error.message}")
        } catch (error: Exception) {
            // Un callback difettoso non deve uccidere il thread (e con lui l'intero trasporto).
            android.util.Log.e("AeroMesh.BLE", "Errore nel blocco della coda BLE", error)
        }
    }
}
