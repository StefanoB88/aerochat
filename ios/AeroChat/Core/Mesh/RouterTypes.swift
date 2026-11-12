import Foundation

/// Vicino radio raggiungibile direttamente (1 hop).
struct Neighbor: Sendable, Hashable, Identifiable {
    let nodeID: Data
    var rssi: Int?
    var linkCount: Int

    var id: Data { nodeID }
}

/// Indicatore semantico "Stato Radio" dell'header chat.
enum Reachability: Sendable, Equatable {
    /// 🟢 Connessione diretta (`hop_count == 1`): testo, vocali e foto attivi.
    case direct(rssi: Int?)
    /// 🟡 Connesso tramite ponte: testo e mini-vocali, foto disabilitate.
    case viaBridge(hops: Int)
    /// ⚪ Fuori portata: invio in coda.
    case outOfRange

    var allowsPhotos: Bool {
        if case .direct = self { return true }
        return false
    }

    var allowsRealtimeDelivery: Bool {
        self != .outOfRange
    }
}

/// Profilo pubblico locale annunciato ai contatti con `PROFILE_UPDATE`.
struct LocalProfile: Sendable, Equatable {
    var nickname: String
    var avatarColorHex: String
    var avatarImage: Data?
    var updatedAtMs: Int64
}

/// Eventi del router verso la UI.
enum RouterEvent: Sendable, Equatable {
    case radioStateChanged(RadioState)
    case neighborsChanged([Neighbor])
    case messageReceived(conversationID: String, msgID: Data)
    case relayQueueChanged(count: Int)
    case groupMembersChanged(conversationID: String)
    /// "Sta scrivendo" ricevuto (TYPING): mai salvato, solo stato in memoria.
    case typingChanged(conversationID: String, senderNodeID: Data, nickname: String, isTyping: Bool)
    /// Un QR con token e' stato scansionato da un nuovo contatto (PAIR_HELLO): la sua chat e' pronta.
    /// `isNewPeer`: il `node_id` non era mai stato visto prima (conta per il premio inviti).
    case pairedByHello(conversationID: String, peerNodeID: Data, isNewPeer: Bool)
    /// PRESENCE ricevuto da un vicino diretto (solo cache in memoria, mai nel DB).
    case presenceUpdated(NearbyPerson)
    /// "Saluta" / "Sfida" ricevuto (CONTACT_REQUEST REQUEST): entra in "Richieste".
    case contactRequestReceived(IncomingContactRequest)
    /// Risposta (ACCEPT / DECLINE) a una nostra richiesta. Con ACCEPT la chat 1-a-1 e' gia' pronta.
    case contactRequestAnswered(requestID: Data, peerNodeID: Data, nickname: String, outcome: ContactRequestOutcome, gameKind: Int32)
    /// RADAR_PING da un vicino diretto: il suo radar e' aperto (`isActive`) ed eventualmente il suo token UWB per noi.
    case radarPingReceived(nodeID: Data, discoveryToken: Data?, isActive: Bool)
    /// Bacheca della cabina cambiata (annunci nostri e ricevuti, solo in memoria).
    case boardChanged([BoardPostEntry])
}

/// Cosa annunciamo con il PRESENCE (impostazione "Visibile alle persone vicine", Cabina del volo, sfide).
struct PresenceSettings: Sendable, Equatable {
    /// Spento: niente PRESENCE in uscita (si continua a vedere gli altri). Il router parte spento:
    /// lo accende l'app secondo l'impostazione dell'utente (default attiva).
    var isVisible = false
    var openToGames = true
    /// Codice canonico della Cabina del volo attiva ("CARRIER|FLIGHT|yyyy-MM-dd").
    var flightCode: String?
    /// Posto da annunciare (gia' validato): solo con "Mostra il mio posto ai vicini" attivo e un volo attivo.
    var seat: String?
}

/// Pacchetto decifrato e verificato che il router non gestisce da solo (media, giochi, Pro Pass):
/// viene consegnato ai sottosistemi delle fasi successive tramite `inboundPackets()`.
struct InboundPacket: Sendable {
    let envelope: MeshEnvelope
    let payload: InnerPayload
    let conversation: ConversationRecord
    let sender: PeerRecord
    /// 1 = ricevuto direttamente dal mittente, N = attraversati N-1 ponti.
    let hopCount: Int
}

/// Pacchetto audio di una chiamata, gia' verificato e decifrato (fast path del router).
struct CallAudioPacket: Sendable {
    let senderNodeID: Data
    let audio: CallAudio
}

/// Statistiche del router (diagnostica e test).
struct RouterStats: Sendable, Equatable {
    var received = 0
    var delivered = 0
    var relayed = 0
    var duplicates = 0
    var malformed = 0
    var undecryptable = 0
    var queued = 0
    /// Pacchetti scartati dalla finestra anti-replay (troppo vecchi o nel futuro).
    var replayRejected = 0
    /// Relay annullati perche' un vicino ha ritrasmesso prima di noi (backoff del flooding).
    var relaySuppressed = 0
}

enum RouterError: Error, Equatable {
    case notRunning
    case unknownPeer(Data)
    case missingGroupKey
    case invalidGossip(String)
    case invalidConversation
    case senderMismatch
    case linkNotAllowedInLounge
    /// Linguaggio non ammesso in Cabina Pubblica (modifica di un messaggio).
    case languageNotAllowedInLounge
    /// Modifica / cancellazione non consentita (non nostro, gia' cancellato, tipo non modificabile).
    case revisionNotAllowed
    /// Sono passati piu' di 15 minuti dall'invio.
    case revisionWindowExpired
    /// `PAIR_HELLO` che non si apre con nessuno dei nostri token validi (sconosciuto o scaduto).
    case pairHelloRejected
    /// CONTACT_REQUEST verso noi stessi o un utente bloccato.
    case contactRequestNotAllowed
    /// Richiesta ricevuta inesistente o scaduta (Accetta / Rifiuta).
    case contactRequestNotFound
    /// Annuncio della bacheca non valido (testo vuoto, oltre 140 caratteri, con link, tipo mancante).
    case invalidBoardPost
    /// Gia' 3 annunci aperti in bacheca.
    case boardLimitReached
}

/// Consegna notifiche locali a schermo spento. Implementato con `UNUserNotificationCenter`.
protocol MeshNotifier: Sendable {
    func notify(message: MessageRecord, conversation: ConversationRecord) async
    /// Come `notify(message:conversation:)`, con il numero da mostrare sul badge dell'icona.
    func notify(message: MessageRecord, conversation: ConversationRecord, badgeCount: Int?) async
    /// Messaggio cancellato per tutti dal mittente: la sua notifica va tolta dal Centro Notifiche.
    func removeNotification(msgID: Data) async
    /// "Marco ti saluta" / "Marco ti sfida a Scopa" (CONTACT_REQUEST ricevuto).
    func notifyContactRequest(_ request: IncomingContactRequest) async
}

extension MeshNotifier {
    func notify(message: MessageRecord, conversation: ConversationRecord, badgeCount: Int?) async {
        await notify(message: message, conversation: conversation)
    }

    func removeNotification(msgID: Data) async {}

    func notifyContactRequest(_ request: IncomingContactRequest) async {}
}

struct NoopNotifier: MeshNotifier {
    func notify(message: MessageRecord, conversation: ConversationRecord) async {}
}

extension PacketType {
    /// Pacchetti testuali/di controllo che possono attendere in `relay_queue` (Delay-Tolerant Networking).
    var isStoreAndForwardEligible: Bool {
        switch self {
        // L'emergenza deve arrivare anche a chi passa di li' piu' tardi.
        case .chatText, .deliveryAck, .readReceipt, .groupKeyGossip, .proPassGrant, .gameMove, .emergency, .messageRevision, .pairHello: return true
        // Il profilo viene ripetuto a ogni nuovo link: non serve tenerlo in coda.
        // Le chiamate, il quiz e il "sta scrivendo" hanno senso solo in tempo reale: niente coda.
        // PRESENCE, CONTACT_REQUEST e RADAR_PING valgono solo tra vicini, adesso.
        // BOARD_POST viene ripetuto dall'autore ogni 60 s: niente coda.
        case .publicLounge, .mediaHeader, .mediaChunk, .profileUpdate, .callSignal, .callAudio, .quiz, .typing,
             .presence, .contactRequest, .radarPing, .boardPost, .UNRECOGNIZED: return false
        }
    }

    var isMedia: Bool {
        self == .mediaHeader || self == .mediaChunk
    }
}
