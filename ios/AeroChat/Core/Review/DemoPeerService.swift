import Foundation
import CryptoKit

/// Compagno di volo simulato per la revisione dell'App Store e per provare l'app con un solo iPhone:
/// un contatto fittizio che risponde ai messaggi della sua chat 1-a-1 e scrive qualche riga in Cabina
/// Pubblica. Vive solo nel database locale: nessun pacchetto radio, nessuna chiave reale.
@MainActor
final class DemoPeerService {

    private let database: AppDatabase
    private var observation: Task<Void, Never>?
    private var answered: Set<Data> = []
    private var replyIndex = 0

    init(database: AppDatabase) {
        self.database = database
    }

    /// Identita' fissa (derivata da etichette costanti) cosi' riattivare la demo ritrova la stessa chat.
    static let nodeID = Data(SHA256.hash(data: Data("AeroMesh-Demo-Peer-Node".utf8))).prefix(MeshProtocol.nodeIDLength)
    private static let x25519Pub = Data(SHA256.hash(data: Data("AeroMesh-Demo-Peer-X25519".utf8)))
    private static let ed25519Pub = Data(SHA256.hash(data: Data("AeroMesh-Demo-Peer-Ed25519".utf8)))

    static var nickname: String { String(localized: "Equipaggio Demo") }

    static func isDemoPeer(_ nodeID: Data) -> Bool { nodeID == Self.nodeID }

    private var replies: [String] {
        [
            String(localized: "Ciao! Sono il compagno di volo simulato: rispondo dallo stesso iPhone, senza Bluetooth. 👋"),
            String(localized: "Con un secondo telefono i messaggi passerebbero via mesh BLE, cifrati end-to-end."),
            String(localized: "Prova a tenere premuto un mio messaggio: puoi segnalarlo o bloccarmi."),
            String(localized: "Nella tab Cabina trovi la bacheca pubblica di 12 ore con le regole da accettare."),
            String(localized: "Le foto che ricevi da chi non hai scansionato di persona restano sfocate finché non le tocchi."),
            String(localized: "Buon volo! ✈️ Quando vuoi disattivami da Impostazioni › Informazioni."),
        ]
    }

    private var loungeLines: [String] {
        [
            String(localized: "Qualcuno sa se il carrello passa prima o dopo il decollo? 🍫"),
            String(localized: "Fila 12 qui: si vede un tramonto pazzesco dal finestrino."),
        ]
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
        let conversationID = "direct:\(Self.nodeID.hexString)"
        try? await database.conversations.delete(conversationID: conversationID)
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
        let text = replies[replyIndex % replies.count]
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
