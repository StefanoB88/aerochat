package com.stefanobrandi.aerochat.core.transport

import java.io.File

/**
 * Trasporto ad alta velocita' per file HD non compressi (> 1 MB), complementare al BLE.
 * Oggi nessuna implementazione e' operativa: l'app usa sempre il BLE; il router interroga
 * [HighSpeedTransportSelector] e ripiega sul BLE quando `isAvailable()` e' `false`.
 */
interface HighSpeedTransport {
    val name: String
    suspend fun isAvailable(): Boolean

    /** Invia il file al nodo indicato. Lancia [HighSpeedTransportError.NotAvailable] se il trasporto non e' pronto. */
    suspend fun sendFile(at: File, to: ByteArray, progress: ((Double) -> Unit)?)
}

sealed class HighSpeedTransportError(message: String) : Exception(message) {
    data class NotAvailable(val reason: String) : HighSpeedTransportError(reason)
    data object FileNotFound : HighSpeedTransportError("file not found") {
        private fun readResolve(): Any = FileNotFound
    }
}

/**
 * Wi-Fi Aware: su iOS richiede iOS 26, il pairing di sistema e il servizio `_aeromesh._tcp` in Info.plist.
 * Android ha Wi-Fi Aware (NAN) dall'API 26, ma senza un protocollo concordato con iOS non e' interoperabile:
 * `isAvailable()` resta `false` e il router resta sul BLE.
 */
class WiFiAwareTransport : HighSpeedTransport {

    override val name: String = "Wi-Fi Aware"

    override suspend fun isAvailable(): Boolean = false

    override suspend fun sendFile(at: File, to: ByteArray, progress: ((Double) -> Unit)?) {
        if (!at.exists()) throw HighSpeedTransportError.FileNotFound
        throw HighSpeedTransportError.NotAvailable(
            "Wi-Fi Aware richiede il pairing dei dispositivi e il servizio $serviceName, non ancora operativo",
        )
    }

    companion object {
        const val serviceName = "_aeromesh._tcp"
    }
}

/**
 * Socket TCP su hotspot personale/locale: utile in crociera o in sala d'attesa quando un
 * passeggero condivide un hotspot. Non ancora operativo.
 */
class LocalHotspotTCPTransport : HighSpeedTransport {

    override val name: String = "Hotspot TCP"
    val port: Int = 47_117

    override suspend fun isAvailable(): Boolean = false

    override suspend fun sendFile(at: File, to: ByteArray, progress: ((Double) -> Unit)?) {
        if (!at.exists()) throw HighSpeedTransportError.FileNotFound
        throw HighSpeedTransportError.NotAvailable("Nessun hotspot locale condiviso rilevato")
    }
}

/** Sceglie il primo trasporto veloce disponibile; `null` => si resta sul BLE (chunk WebP/Opus). */
class HighSpeedTransportSelector(
    val candidates: List<HighSpeedTransport> = listOf(WiFiAwareTransport(), LocalHotspotTCPTransport()),
) {
    suspend fun best(): HighSpeedTransport? = candidates.firstOrNull { it.isAvailable() }
}
