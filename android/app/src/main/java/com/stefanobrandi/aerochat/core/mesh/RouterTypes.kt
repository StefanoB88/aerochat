package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.emergency.EmergencyAlert
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.core.transport.RadioState
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.ConversationType
import com.stefanobrandi.aerochat.data.records.MessageRecord
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.proto.CallAudio
import com.stefanobrandi.aerochat.proto.InnerPayload
import com.stefanobrandi.aerochat.proto.MeshEnvelope
import com.stefanobrandi.aerochat.proto.PacketType

/** Vicino radio raggiungibile direttamente (1 hop). */
data class Neighbor(
    val nodeID: ByteArray,
    val rssi: Int?,
    val linkCount: Int,
) {
    /** `Identifiable.id` di Swift: il `node_id` (hex, per le chiavi delle liste Compose). */
    val id: String get() = nodeID.toHex()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Neighbor) return false
        return nodeID.contentEquals(other.nodeID) && rssi == other.rssi && linkCount == other.linkCount
    }

    override fun hashCode(): Int {
        var result = nodeID.contentHashCode()
        result = 31 * result + (rssi ?: 0)
        result = 31 * result + linkCount
        return result
    }

    override fun toString(): String = "Neighbor(nodeID=${nodeID.toHex()}, rssi=$rssi, linkCount=$linkCount)"
}

/** Indicatore semantico "Stato Radio" dell'header chat. */
sealed interface Reachability {
    /** Connessione diretta (`hop_count == 1`): testo, vocali e foto attivi. */
    data class Direct(val rssi: Int?) : Reachability

    /** Connesso tramite ponte: testo e mini-vocali, foto disabilitate. */
    data class ViaBridge(val hops: Int) : Reachability

    /** Fuori portata: invio in coda. */
    data object OutOfRange : Reachability

    val allowsPhotos: Boolean get() = this is Direct

    val allowsRealtimeDelivery: Boolean get() = this != OutOfRange
}

/** Profilo pubblico locale annunciato ai contatti con `PROFILE_UPDATE`. `updatedAtMs` epoch ms. */
data class LocalProfile(
    val nickname: String,
    val avatarColorHex: String,
    val avatarImage: ByteArray?,
    val updatedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalProfile) return false
        return nickname == other.nickname && avatarColorHex == other.avatarColorHex &&
            (avatarImage?.contentEquals(other.avatarImage) ?: (other.avatarImage == null)) &&
            updatedAtMs == other.updatedAtMs
    }

    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + avatarColorHex.hashCode()
        result = 31 * result + (avatarImage?.contentHashCode() ?: 0)
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }
}

/** Eventi del router verso la UI. */
sealed interface RouterEvent {
    data class RadioStateChanged(val state: RadioState) : RouterEvent
    data class NeighborsChanged(val neighbors: List<Neighbor>) : RouterEvent

    data class MessageReceived(val conversationID: String, val msgID: ByteArray) : RouterEvent {
        override fun equals(other: Any?): Boolean =
            other is MessageReceived && conversationID == other.conversationID && msgID.contentEquals(other.msgID)
        override fun hashCode(): Int = 31 * conversationID.hashCode() + msgID.contentHashCode()
        override fun toString(): String = "MessageReceived(conversationID=$conversationID, msgID=${msgID.toHex()})"
    }

    data class RelayQueueChanged(val count: Int) : RouterEvent

    /** Qualcuno ha scansionato il nostro QR (`PAIR_HELLO` verificato): la sua chat 1-a-1 e' pronta. */
    data class PeerPaired(val conversationID: String, val nickname: String) : RouterEvent
    data class GroupMembersChanged(val conversationID: String) : RouterEvent

    /** "Saluta" / "Sfida" ricevuto da una persona vicina (`CONTACT_REQUEST` REQUEST): entra in "Richieste". */
    data class ContactRequestReceived(val request: IncomingContactRequest) : RouterEvent

    /**
     * Richiesta accettata (`CONTACT_REQUEST` ACCEPT inviato o ricevuto): il peer e' un contatto fidato e la
     * chat 1-a-1 e' pronta. [requestedByMe] = abbiamo chiesto noi (riceviamo l'ACCEPT). [gameKind] != 0: sfida.
     */
    data class ContactAccepted(
        val conversationID: String,
        val nickname: String,
        val gameKind: Int,
        val requestedByMe: Boolean,
    ) : RouterEvent

    /** La nostra richiesta e' stata rifiutata (`CONTACT_REQUEST` DECLINE). */
    data class ContactDeclined(val nickname: String) : RouterEvent

    /** Testo inviato da noi (contatori del ricordo del viaggio). */
    data class MessageSent(val conversationID: String) : RouterEvent

    /**
     * Un messaggio ricevuto e' stato modificato o cancellato dal suo autore (`MESSAGE_REVISION`).
     * [deletedMediaPath]: file media (path relativo) da rimuovere dal disco dopo una cancellazione.
     */
    data class MessageRevised(
        val conversationID: String,
        val msgID: ByteArray,
        val isDeleted: Boolean,
        val deletedMediaPath: String?,
    ) : RouterEvent {
        override fun equals(other: Any?): Boolean =
            other is MessageRevised && conversationID == other.conversationID && msgID.contentEquals(other.msgID) &&
                isDeleted == other.isDeleted && deletedMediaPath == other.deletedMediaPath
        override fun hashCode(): Int = 31 * (31 * conversationID.hashCode() + msgID.contentHashCode()) + isDeleted.hashCode()
        override fun toString(): String = "MessageRevised(conversationID=$conversationID, msgID=${msgID.toHex()}, isDeleted=$isDeleted)"
    }
}

/**
 * Pacchetto decifrato e verificato che il router non gestisce da solo (media, giochi, Pro Pass):
 * viene consegnato ai sottosistemi delle fasi successive tramite `inboundPackets()`.
 */
class InboundPacket(
    val envelope: MeshEnvelope,
    val payload: InnerPayload,
    val conversation: ConversationRecord,
    val sender: PeerRecord,
    /** 1 = ricevuto direttamente dal mittente, N = attraversati N-1 ponti. */
    val hopCount: Int,
)

/** Pacchetto audio di una chiamata, gia' verificato e decifrato (fast path del router). */
class CallAudioPacket(
    val senderNodeID: ByteArray,
    val audio: CallAudio,
)

/** Statistiche del router (diagnostica e test). Immutabile: il router pubblica una copia nuova a ogni modifica. */
data class RouterStats(
    val received: Int = 0,
    val delivered: Int = 0,
    val relayed: Int = 0,
    val duplicates: Int = 0,
    val malformed: Int = 0,
    val undecryptable: Int = 0,
    val queued: Int = 0,
    /** Pacchetti scartati dalla finestra anti-replay (troppo vecchi o nel futuro). */
    val replayRejected: Int = 0,
    /** Relay annullati perche' un vicino ha ritrasmesso prima di noi (backoff del flooding). */
    val relaySuppressed: Int = 0,
)

sealed class RouterError(message: String) : Exception(message) {
    data object NotRunning : RouterError("router not running") {
        private fun readResolve(): Any = NotRunning
    }

    class UnknownPeer(val nodeID: ByteArray) : RouterError("unknown peer ${nodeID.toHex()}") {
        override fun equals(other: Any?): Boolean = other is UnknownPeer && nodeID.contentEquals(other.nodeID)
        override fun hashCode(): Int = nodeID.contentHashCode()
    }

    data object MissingGroupKey : RouterError("missing group key") {
        private fun readResolve(): Any = MissingGroupKey
    }

    data class InvalidGossip(val reason: String) : RouterError("invalid gossip: $reason")

    data object InvalidConversation : RouterError("invalid conversation") {
        private fun readResolve(): Any = InvalidConversation
    }

    data object SenderMismatch : RouterError("sender mismatch") {
        private fun readResolve(): Any = SenderMismatch
    }

    data object LinkNotAllowedInLounge : RouterError("link not allowed in lounge") {
        private fun readResolve(): Any = LinkNotAllowedInLounge
    }

    /** `PAIR_HELLO` che non si apre con nessuno dei nostri token validi (sconosciuto o scaduto). */
    data object UnknownPairingToken : RouterError("unknown pairing token") {
        private fun readResolve(): Any = UnknownPairingToken
    }

    /** Persona vicina sconosciuta o richiesta scaduta (`CONTACT_REQUEST`). */
    data object UnknownContactRequest : RouterError("unknown contact request") {
        private fun readResolve(): Any = UnknownContactRequest
    }

    /** Modifica/cancellazione non consentita: non e' nostro, fuori dai 15 minuti, gia' cancellato o tipo errato. */
    data object RevisionNotAllowed : RouterError("revision not allowed") {
        private fun readResolve(): Any = RevisionNotAllowed
    }
}

/** Consegna notifiche locali a schermo spento. Implementato con `NotificationManagerCompat`. */
interface MeshNotifier {
    suspend fun notify(message: MessageRecord, conversation: ConversationRecord)

    /** Avviso di emergenza: notifica prioritaria, mostrata anche con la Cabina aperta (default: nessuna). */
    suspend fun notifyEmergency(alert: EmergencyAlert) {}

    /** Il messaggio e' stato cancellato per tutti dal mittente: via la sua notifica (e dal badge). */
    suspend fun cancelMessage(msgID: ByteArray, conversationID: String) {}

    /** La conversazione e' stata letta o eliminata: via tutte le sue notifiche (e dal badge). */
    suspend fun cancelConversation(conversationID: String) {}

    /** "Marco ti saluta" / "Marco ti sfida a Scopa" (`CONTACT_REQUEST` ricevuta). Default: nessuna. */
    suspend fun notifyContactRequest(request: IncomingContactRequest) {}
}

class NoopNotifier : MeshNotifier {
    override suspend fun notify(message: MessageRecord, conversation: ConversationRecord) {}
}

/** "Sta scrivendo" solo in chat 1-a-1 e gruppi: mai nella Cabina Pubblica ne' nella Cabina del volo. */
val ConversationRecord.supportsTypingIndicator: Boolean
    get() = type == ConversationType.direct || (type == ConversationType.group && !isFlightLounge)

/** Pacchetti testuali/di controllo che possono attendere in `relay_queue` (Delay-Tolerant Networking). */
val PacketType.isStoreAndForwardEligible: Boolean
    get() = when (this) {
        // L'emergenza deve arrivare anche a chi passa di li' piu' tardi.
        PacketType.CHAT_TEXT, PacketType.DELIVERY_ACK, PacketType.READ_RECEIPT, PacketType.GROUP_KEY_GOSSIP,
        PacketType.PRO_PASS_GRANT, PacketType.GAME_MOVE, PacketType.EMERGENCY, PacketType.MESSAGE_REVISION,
        // Chi ha scansionato il QR si presenta anche se chi l'ha mostrato e' momentaneamente lontano.
        PacketType.PAIR_HELLO -> true
        // Il profilo viene ripetuto a ogni nuovo link: non serve tenerlo in coda.
        // Le chiamate e il quiz hanno senso solo in tempo reale: niente coda.
        PacketType.PUBLIC_LOUNGE, PacketType.MEDIA_HEADER, PacketType.MEDIA_CHUNK, PacketType.PROFILE_UPDATE,
        // "Sta scrivendo" ha senso solo in quel momento.
        PacketType.CALL_SIGNAL, PacketType.CALL_AUDIO, PacketType.QUIZ, PacketType.TYPING,
        // Persone vicine: la presenza vale solo adesso; la richiesta ha la sua risposta (niente coda ne' ACK).
        PacketType.PRESENCE, PacketType.CONTACT_REQUEST, PacketType.UNRECOGNIZED -> false
    }

val PacketType.isMedia: Boolean
    get() = this == PacketType.MEDIA_HEADER || this == PacketType.MEDIA_CHUNK
