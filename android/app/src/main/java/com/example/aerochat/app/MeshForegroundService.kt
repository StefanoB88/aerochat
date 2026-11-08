package com.example.aerochat.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.aerochat.AeroChatApplication
import com.example.aerochat.MainActivity
import com.example.aerochat.R
import com.example.aerochat.core.transport.BLEPermissions
import kotlinx.coroutines.launch

/**
 * Servizio in primo piano che tiene viva la radio BLE a schermo spento (equivalente dei background mode
 * iOS `bluetooth-central` / `bluetooth-peripheral` / `audio`). Non fa lavoro proprio: la mesh vive in
 * [AppEnvironment]; il servizio mantiene il processo in primo piano con una notifica persistente a
 * bassa importanza "AeroChat è attivo sulla mesh".
 *
 * Tipi (regole Android 14+):
 * - `connectedDevice` sempre (richiede i permessi BLE runtime concessi);
 * - `microphone` aggiunto solo durante una chiamata, con `RECORD_AUDIO` concesso e app in primo piano
 *   (un servizio "while-in-use" non puo' acquisire il microfono partendo dal background).
 */
class MeshForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wantsMicrophone = intent?.getBooleanExtra(extraCallActive, false) == true
        if (!BLEPermissions.hasAllPermissions(this)) {
            // Senza permessi BLE il tipo connectedDevice non e' ammesso (Android 14+): niente servizio.
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        var types = typeConnectedDevice()
        if (wantsMicrophone && canUseMicrophoneType(this)) types = types or typeMicrophone()
        try {
            ServiceCompat.startForeground(this, notificationID, buildNotification(), types)
            isRunning = true
            isMicrophoneActive = types and typeMicrophone() != 0
            ensureMeshRunning()
        } catch (e: Exception) {
            // Es. ForegroundServiceStartNotAllowedException / SecurityException: la mesh resta attiva
            // finche' il processo vive, ma senza garanzia a schermo spento.
            Log.w(tag, "startForeground fallito: $e")
            val retried = wantsMicrophone && runCatching {
                // Riprova senza microfono, per non perdere la radio.
                ServiceCompat.startForeground(this, notificationID, buildNotification(), typeConnectedDevice())
                isRunning = true
                isMicrophoneActive = false
            }.isSuccess
            if (retried) ensureMeshRunning() else stopSelf()
        }
        // Se il sistema uccide il processo (memoria, OEM), lo riavvia con il servizio: la mesh riparte da
        // sola senza che l'utente riapra l'app (equivalente della State Restoration CoreBluetooth).
        return START_STICKY
    }

    /**
     * Riavvio "sticky" (intent `null`) o processo ricreato senza Activity: nessuno chiamerebbe
     * `bootstrap()` (lo fa `MainActivity`), quindi radio e router resterebbero spenti. Idempotente.
     */
    private fun ensureMeshRunning() {
        val app = application as? AeroChatApplication ?: return
        app.applicationScope.launch { app.environment.bootstrap() }
    }

    override fun onDestroy() {
        isRunning = false
        isMicrophoneActive = false
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, channelID)
        .setSmallIcon(R.drawable.ic_stat_mesh)
        .setContentTitle(getString(R.string.shell_fgs_notification_title))
        .setContentText(getString(R.string.shell_fgs_notification_text))
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val tag = "MeshForegroundService"
        const val channelID = "mesh_service"
        private const val notificationID = 0x4D45
        private const val extraCallActive = "callActive"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var isMicrophoneActive: Boolean = false

        /** Avvia (o aggiorna) il servizio. No-op senza permessi BLE; errori di avvio in background ignorati. */
        fun start(context: Context, callActive: Boolean) {
            if (!BLEPermissions.hasAllPermissions(context)) return
            val intent = Intent(context, MeshForegroundService::class.java).putExtra(extraCallActive, callActive)
            try {
                if (isRunning) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(tag, "avvio servizio non consentito: $e")
            }
        }

        /** Aggiunge/toglie il tipo `microphone` se il servizio e' gia' attivo. */
        fun update(context: Context, callActive: Boolean) {
            if (!isRunning) return
            if (callActive == isMicrophoneActive) return
            if (callActive && !canUseMicrophoneType(context)) return
            start(context, callActive)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
            isRunning = false
            isMicrophoneActive = false
        }

        private fun canUseMicrophoneType(context: Context): Boolean {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            return granted && (foreground || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        }

        private fun typeConnectedDevice(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        private fun typeMicrophone(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                channelID,
                context.getString(R.string.shell_fgs_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }
}
