package com.example.aerochat.core.mesh

import com.example.aerochat.core.games.GameService
import com.example.aerochat.core.games.GameSessionState
import com.example.aerochat.core.games.isCardGame
import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.aerochat.MainActivity
import com.example.aerochat.R
import com.example.aerochat.core.emergency.EmergencyAlert
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Chat attualmente sullo schermo: solo per quella (con telefono sbloccato e app attiva) il banner e' superfluo. */
object NotificationPresence {

    /** Impostata dalla UI quando una chat e' aperta in primo piano (null altrimenti). */
    @Volatile
    var visibleConversationID: String? = null

    /**
     * `true` se l'utente sta gia' guardando la conversazione. A schermo bloccato (anche con l'app in
     * primo piano prima del blocco) la notifica va sempre mostrata. Da chiamare sul main thread.
     */
    fun isShowing(context: Context, conversationID: String): Boolean {
        val isActive = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return isShowing(
            isAppResumed = isActive,
            isKeyguardLocked = keyguard?.isKeyguardLocked == true,
            isScreenOn = power?.isInteractive != false,
            visibleConversationID = visibleConversationID,
            conversationID = conversationID,
        )
    }

    /**
     * Decisione pura (testabile): il banner e' superfluo solo se l'app e' in primo piano (RESUMED), lo
     * schermo e' acceso e sbloccato e la chat aperta e' proprio quella del messaggio. In tutti gli altri
     * casi (background, schermo spento/bloccato, altra chat o altra scheda) la notifica va mostrata.
     */
    fun isShowing(
        isAppResumed: Boolean,
        isKeyguardLocked: Boolean,
        isScreenOn: Boolean,
        visibleConversationID: String?,
        conversationID: String,
    ): Boolean = isAppResumed && isScreenOn && !isKeyguardLocked && visibleConversationID == conversationID
}

/**
 * Notifiche locali istantanee per i messaggi ricevuti via BLE: a schermo spento/bloccato, in
 * background e, in primo piano, per le chat diverse da quella aperta.
 *
 * Il permesso runtime `POST_NOTIFICATIONS` (API 33+) viene chiesto dalla UI; qui si verifica soltanto
 * prima di pubblicare. Il tocco sulla notifica apre [MainActivity] con l'extra [extraConversationID].
 */
class LocalNotificationCenter(context: Context) : MeshNotifier {

    private val context: Context = context.applicationContext

    /**
     * Totale dei messaggi non letti (impostato da `AppEnvironment` all'apertura del database): diventa il
     * numero della notifica, che i launcher mostrano come badge sull'icona.
     */
    @Volatile
    var unreadCountProvider: (suspend () -> Int)? = null

    /**
     * Prepara i canali e ritorna `true` se l'app puo' pubblicare notifiche. Su Android il dialogo
     * del permesso richiede un'Activity: lo mostra la UI (`ActivityResultContracts.RequestPermission`).
     */
    suspend fun requestAuthorization(): Boolean {
        createChannels()
        return canPostNotifications()
    }

    /** Crea i canali (idempotente). Il dialogo del permesso runtime e' a carico della UI. */
    suspend fun requestAuthorizationIfNeeded() {
        createChannels()
    }

    override suspend fun notify(message: MessageRecord, conversation: ConversationRecord) {
        // Il router chiama notify prima di mandare l'ACK: un errore qui non deve mai bloccare la consegna.
        try {
            post(message, conversation)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AeroMesh.Notify", "notifica non pubblicata: $e")
        }
    }

    private suspend fun post(message: MessageRecord, conversation: ConversationRecord) {
        // Chat aperta e telefono sbloccato: la UI mostra gia' il messaggio (con feedback aptico).
        val conversationID = conversation.id
        val isShowing = withContext(Dispatchers.Main) { NotificationPresence.isShowing(context, conversationID) }
        if (isShowing) return
        createChannels()
        if (!canPostNotifications()) return

        val title = if (conversation.type == ConversationType.direct) {
            message.senderNickname ?: conversation.title
        } else {
            conversation.title
        }
        val body = preview(message)
        val msgHex = message.msgID.toHex()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(extraConversationID, conversationID)
            putExtra(extraMsgID, msgHex)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationID.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, messagesChannelID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setGroup(conversationID) // threadIdentifier
            .setContentIntent(pendingIntent)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
        // Badge sull'icona dell'app: il launcher usa il numero della notifica (somma dei non letti).
        val unread = runCatching { unreadCountProvider?.invoke() }.getOrNull()
        if (unread != null && unread > 0) builder.setNumber(unread)
        if (conversation.type == ConversationType.group) {
            message.senderNickname?.let { builder.setSubText(it) } // subtitle
        }

        try {
            NotificationManagerCompat.from(context).notify(msgHex, notificationID, builder.build())
        } catch (_: SecurityException) {
            // Permesso revocato tra il controllo e la pubblicazione.
        }
    }

    /**
     * Avviso di emergenza: canale ad alta priorita' dedicato (equivalente della notifica *time sensitive*),
     * pubblicato anche con l'app aperta sulla Cabina.
     */
    override suspend fun notifyEmergency(alert: EmergencyAlert) {
        try {
            createChannels()
            if (!canPostNotifications()) return
            val msgHex = alert.msgID.toHex()
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(extraConversationID, ConversationRecord.cabinLoungeID)
                putExtra(extraMsgID, msgHex)
                putExtra(extraEmergency, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, msgHex.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val body = alert.text.ifEmpty { context.getString(R.string.core11_emergency_body_default) }
            val builder = NotificationCompat.Builder(context, emergencyChannelID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("${alert.emoji} ${alert.statusTitle(context)}")
                .setSubText(alert.senderNickname)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setGroup("emergency") // threadIdentifier
                .setContentIntent(pendingIntent)
            try {
                NotificationManagerCompat.from(context).notify("emergency-$msgHex", notificationID, builder.build())
            } catch (_: SecurityException) {
                // Permesso revocato tra il controllo e la pubblicazione.
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AeroMesh.Notify", "avviso di emergenza non pubblicato: $e")
        }
    }

    override suspend fun cancelMessage(msgID: ByteArray, conversationID: String) {
        try {
            NotificationManagerCompat.from(context).cancel(msgID.toHex(), notificationID)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AeroMesh.Notify", "notifica non rimossa: $e")
        }
    }

    /** Rimuove le notifiche dei messaggi della conversazione (raggruppate per `conversation_id`). */
    override suspend fun cancelConversation(conversationID: String) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            for (notification in manager.activeNotifications) {
                if (notification.id != notificationID) continue
                if (notification.notification.group != conversationID) continue
                manager.cancel(notification.tag, notification.id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AeroMesh.Notify", "notifiche non rimosse: $e")
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun createChannels() {
        val channel = NotificationChannel(messagesChannelID, messagesChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
            description = messagesChannelDescription
            enableVibration(true)
            // Il badge sull'icona dell'app nasce dalle notifiche di questo canale.
            setShowBadge(true)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
        val emergency = NotificationChannel(
            emergencyChannelID, context.getString(R.string.core11_emergency_channel_name), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            enableLights(true)
            // Ignorato dal sistema senza l'accesso "Non disturbare"; se concesso, l'avviso passa comunque.
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        NotificationManagerCompat.from(context).createNotificationChannel(emergency)
    }

    companion object {
        /** Canale dei messaggi ricevuti. */
        const val messagesChannelID = "messages"
        const val messagesChannelName = "Messaggi"
        const val messagesChannelDescription = "Messaggi ricevuti dalla mesh"

        /** Extra dell'Intent di apertura: id della conversazione da mostrare (`conversation_id` su iOS). */
        const val extraConversationID = "conversationID"
        /** Extra dell'Intent di apertura: `msg_id` esadecimale. */
        const val extraMsgID = "msgID"
        /** Extra dell'Intent di apertura: `true` se la notifica e' un avviso di emergenza. */
        const val extraEmergency = "emergency"
        /** Canale degli avvisi di emergenza (priorita' massima). */
        const val emergencyChannelID = "emergency"

        /** Id numerico comune: le notifiche si distinguono per tag (`msg_id` hex), come l'identifier iOS. */
        private const val notificationID = 1

        fun preview(message: MessageRecord): String = when (message.contentType) {
            MessageContentType.text -> message.text ?: ""
            MessageContentType.image -> "📷 Foto"
            MessageContentType.audio -> "🎤 Nota vocale"
            MessageContentType.game -> {
                // Carte: "🃏 Scopa", "🃏 Scopone", "🃏 Briscola"; gli altri giochi restano generici.
                val state = message.gameState?.let { runCatching { GameSessionState.decode(it) }.getOrNull() }
                if (state != null && state.isCardGame) GameService.title(state.gameKind) else "🎮 Mossa di gioco"
            }
            MessageContentType.system -> message.text ?: "Aggiornamento"
        }
    }
}
