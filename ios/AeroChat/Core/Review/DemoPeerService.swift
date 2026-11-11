import Foundation
import CryptoKit

/// Passeggero virtuale ("Prova subito", e revisione dell'App Store con un solo iPhone): un contatto
/// fittizio che risponde ai messaggi della sua chat 1-a-1, gioca a carte e a Tris (`DemoGameBot`) e scrive
/// qualche riga in Cabina Pubblica. Vive solo nel database locale: nessun pacchetto radio (il router scarta
/// tutto cio' che e' diretto a lui), nessuna chiave reale.
@MainActor
final class DemoPeerService {

    private let database: AppDatabase
    private var observation: Task<Void, Never>?
    private var answered: Set<Data> = []
    private var replyIndex = 0

    init(database: AppDatabase) {
        self.database = database
    }

    /// Identita' fissa (vedi `DemoPeerIdentity`): riattivare la prova ritrova la stessa chat.
    static var nodeID: Data { Data(DemoPeerIdentity.nodeID) }
    private static var x25519Pub: Data { DemoPeerIdentity.x25519Pub }
    private static var ed25519Pub: Data { DemoPeerIdentity.ed25519Pub }

    /// Nome ben visibile in lista e nell'intestazione: e' chiaro che non e' una persona vera.
    static var nickname: String { String(localized: "Passeggero virtuale") }

    static func isDemoPeer(_ nodeID: Data?) -> Bool { DemoPeerIdentity.isDemoPeer(nodeID) }

    private var replies: [String] {
        [
            String(localized: "Ciao! Sono il passeggero virtuale 👋 Ti mostro come funziona AeroChat, tutto da questo iPhone."),
            String(localized: "In volo i messaggi passano da telefono a telefono via Bluetooth: niente Wi-Fi, niente rete, niente account."),
            String(localized: "Se l'amico è lontano, il messaggio aspetta e arriva appena siete vicini, anche passando da altri passeggeri."),
            String(localized: "Facciamo una partita? Tocca + qui sotto e scegli Scopa, Briscola o Tris 🃏"),
            String(localized: "Ora prova con una persona vera: tocca «Invita chi è vicino a te» qui sotto ✈️"),
        ]
    }

    private var loungeLines: [String] {
        [
            String(localized: "Qualcuno sa se il carrello passa prima o dopo il decollo? 🍫"),
            String(localized: "Fila 12 qui: si vede un tramonto pazzesco dal finestrino."),
        ]
    }

    /// Chat del passeggero virtuale (creata da `enable`).
    func conversation() async -> ConversationRecord? {
        try? await database.conversations.fetch(id: DemoPeerIdentity.conversationID)
    }

    func enable() async {
        let peer = PeerRecord(nodeID: Self.nodeID, x25519Pub: Self.x25519Pub, ed25519Pub: Self.ed25519Pub,
                              nickname: Self.nickname, isTrusted: true)
        guard let stored = try? await database.peers.upsert(peer, authoritativeNickname: true),
              let conversation = try? await database.conversations.ensureDirectConversation(with: stored) else { return }
        if (try? await database.messages.fetch(conversationID: conversation.id, limit: 1))?.isEmpty ?? true {
            await insertIncoming(replies[0], in: conversation)
            replyIndex = 1
        }
        if let lounge = try? await database.conversations.currentCabinLounge() {
            for line in loungeLines {
                await insertIncoming(line, in: lounge)
            }
        }
        observation?.cancel()
        observation = Task { [weak self] in
            guard let self else { return }
            for await list in database.messages.observe(conversationID: conversation.id) {
                let pending = list.filter { $0.isOutgoing && $0.contentType == .text && $0.deliveryStatus != .read && !self.answered.contains($0.msgID) }
                for message in pending {
                    self.answered.insert(message.msgID)
                    await self.reply(to: message, in: conversation)
                }
            }
        }
    }

    func disable() async {
        observation?.cancel()
        observation = nil
        answered.removeAll()
        try? await database.conversations.delete(conversationID: DemoPeerIdentity.conversationID)
        if let lounge = try? await database.conversations.fetch(id: ConversationRecord.cabinLoungeID),
           let messages = try? await database.messages.fetch(conversationID: lounge.id, limit: 500) {
            for message in messages where message.senderNodeID == Self.nodeID {
                try? await database.messages.delete(msgID: message.msgID)
            }
        }
    }

    private func reply(to message: MessageRecord, in conversation: ConversationRecord) async {
        // Consegnato subito, letto dopo un attimo, risposta dopo un paio di secondi: come un compagno vero.
        try? await database.messages.advanceDeliveryStatus(to: .deliveredAck, msgID: message.msgID)
        try? await Task.sleep(for: .seconds(1))
        try? await database.messages.advanceDeliveryStatus(to: .read, msgID: message.msgID)
        try? await Task.sleep(for: .seconds(1.5))
        guard !Task.isCancelled else { return }
        // Dopo il copione si alternano gli ultimi due suggerimenti (partita e invito).
        let index = replyIndex < replies.count ? replyIndex : replies.count - 2 + (replyIndex - replies.count) % 2
        let text = replies[index]
        replyIndex += 1
        await insertIncoming(text, in: conversation)
    }

    private func insertIncoming(_ text: String, in conversation: ConversationRecord) async {
        let message = MessageRecord(msgID: CryptoEngine.randomMessageID(),
                                    conversationID: conversation.id,
                                    senderNodeID: Self.nodeID,
                                    senderNickname: Self.nickname,
                                    contentType: .text,
                                    text: text,
                                    isOutgoing: false,
                                    deliveryStatus: .deliveredAck,
                                    hopCountReceived: 1)
        _ = try? await database.messages.insert(message)
    }
}
