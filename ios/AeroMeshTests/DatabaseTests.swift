import Testing
import Foundation
@testable import AeroChat

@Suite("GRDB – schema, FTS5, osservazioni, code")
struct DatabaseTests {

    private func makePeer(_ nickname: String, trusted: Bool = false) -> PeerRecord {
        let identity = IdentityKeys.generate()
        return PeerRecord(nodeID: identity.nodeID,
                          x25519Pub: identity.x25519PublicKeyRaw,
                          ed25519Pub: identity.ed25519PublicKeyRaw,
                          nickname: nickname,
                          isTrusted: trusted)
    }

    @Test("Le migrazioni creano tutte le tabelle richieste e il DB gira in WAL")
    func migrationsCreateSchema() async throws {
        let db = try AppDatabase.makeTemporary()
        let tables = try await db.tableNames()
        for expected in ["identity", "peers", "conversations", "group_members", "messages", "messages_fts", "seen_packets", "relay_queue", "message_reads"] {
            #expect(tables.contains(expected), "manca la tabella \(expected)")
        }
        #expect(try await db.isWALEnabled())
    }

    @Test("Identity: singola riga, salvataggio e stato Pro")
    func identitySingleton() async throws {
        let db = try AppDatabase.makeTemporary()
        let keys = IdentityKeys.generate()
        #expect(try await db.identities.fetch() == nil)

        try await db.identities.save(IdentityRecord(identity: keys, nickname: "Stefano", avatarColorHex: "00E5FF"))
        let stored = try #require(try await db.identities.fetch())
        #expect(stored.nodeID == keys.nodeID)
        #expect(stored.isPro() == false)

        let until = Date().addingTimeInterval(72 * 3600).unixMilliseconds
        try await db.identities.updateProStatus(tier: .pass72h, validUntilMs: until, receiptJWS: "jws")
        let upgraded = try #require(try await db.identities.fetch())
        #expect(upgraded.isPro())
        #expect(upgraded.isPro(now: Date().addingTimeInterval(73 * 3600)) == false)
    }

    @Test("Peers: upsert non declassa un peer fidato e aggiorna la telemetria radio")
    func peerUpsertPreservesTrust() async throws {
        let db = try AppDatabase.makeTemporary()
        let peer = makePeer("Anna", trusted: true)
        try await db.peers.upsert(peer)

        var gossiped = peer
        gossiped.isTrusted = false
        gossiped.nickname = "Anna R."
        try await db.peers.upsert(gossiped)

        let stored = try #require(try await db.peers.fetch(nodeID: peer.nodeID))
        #expect(stored.isTrusted == true)
        #expect(stored.nickname == "Anna R.")

        try await db.peers.markSeen(nodeID: peer.nodeID, hopCount: 2, rssi: -71)
        let seen = try #require(try await db.peers.fetch(nodeID: peer.nodeID))
        #expect(seen.lastHopCount == 2)
        #expect(seen.lastRSSI == -71)
        #expect(try await db.peers.trustedPeers().count == 1)

        try await db.peers.setBlocked(true, nodeID: peer.nodeID)
        #expect(try await db.peers.blockedNodeIDs() == [peer.nodeID])
        #expect(try await db.peers.trustedPeers().isEmpty)
    }

    @Test("Conversazioni dirette, messaggi, unread e ricerca FTS5 con prefissi")
    func directConversationMessagesAndFTS() async throws {
        let db = try AppDatabase.makeTemporary()
        let me = IdentityKeys.generate()
        let peer = makePeer("Luca")
        try await db.peers.upsert(peer)
        let conversation = try await db.conversations.ensureDirectConversation(with: peer)
        #expect(conversation.recipientID == peer.nodeID)
        #expect(try await db.conversations.ensureDirectConversation(with: peer).id == conversation.id)
        #expect(try await db.conversations.fetch(recipientID: peer.nodeID)?.id == conversation.id)

        try await db.messages.insert(MessageRecord.outgoingText("Prendiamo un taxi insieme all'arrivo?", conversationID: conversation.id, sender: me))
        try await db.messages.insert(MessageRecord(msgID: CryptoEngine.randomMessageID(), conversationID: conversation.id,
                                                   senderNodeID: peer.nodeID, contentType: .text, text: "Volentieri, io sono al 22A",
                                                   isOutgoing: false, deliveryStatus: .deliveredAck, hopCountReceived: 1,
                                                   createdAtMs: Date.nowUnixMilliseconds + 1))
        try await db.messages.insert(MessageRecord(msgID: CryptoEngine.randomMessageID(), conversationID: conversation.id,
                                                   senderNodeID: peer.nodeID, contentType: .text, text: "Il gate è cambiato: B7",
                                                   isOutgoing: false, deliveryStatus: .deliveredAck,
                                                   createdAtMs: Date.nowUnixMilliseconds + 2))

        let messages = try await db.messages.fetch(conversationID: conversation.id)
        #expect(messages.count == 3)
        #expect(messages.first?.isOutgoing == true)

        let updated = try #require(try await db.conversations.fetch(id: conversation.id))
        #expect(updated.unreadCount == 2)
        #expect(updated.lastMessageAtMs != nil)

        #expect(try await db.messages.search("taxi").count == 1)
        #expect(try await db.messages.search("tax").count == 1)
        #expect(try await db.messages.search("gate b7").first?.text == "Il gate è cambiato: B7")
        #expect(try await db.messages.search("inesistente").isEmpty)

        try await db.conversations.markRead(conversationID: conversation.id)
        #expect(try await db.conversations.fetch(id: conversation.id)?.unreadCount == 0)
        #expect(try await db.messages.fetch(conversationID: conversation.id).filter { !$0.isOutgoing }.allSatisfy { $0.deliveryStatus == .read })
    }

    @Test("Stato di consegna: avanza solo in avanti")
    func deliveryStatusOnlyAdvances() async throws {
        let db = try AppDatabase.makeTemporary()
        let me = IdentityKeys.generate()
        let peer = makePeer("Sara")
        try await db.peers.upsert(peer)
        let conversation = try await db.conversations.ensureDirectConversation(with: peer)
        let message = MessageRecord.outgoingText("ping", conversationID: conversation.id, sender: me)
        try await db.messages.insert(message)
        #expect(try await db.messages.queuedOutgoing().count == 1)

        try await db.messages.advanceDeliveryStatus(to: .deliveredAck, msgID: message.msgID)
        try await db.messages.advanceDeliveryStatus(to: .sentMesh, msgID: message.msgID)
        #expect(try await db.messages.fetch(msgID: message.msgID)?.deliveryStatus == .deliveredAck)
        #expect(try await db.messages.queuedOutgoing().isEmpty)
    }

    @Test("ValueObservation come AsyncStream: valore iniziale e aggiornamento dopo un insert")
    func observeMessagesStream() async throws {
        let db = try AppDatabase.makeTemporary()
        let me = IdentityKeys.generate()
        let peer = makePeer("Paolo")
        try await db.peers.upsert(peer)
        let conversation = try await db.conversations.ensureDirectConversation(with: peer)
        try await db.messages.insert(MessageRecord.outgoingText("uno", conversationID: conversation.id, sender: me))

        let stream = db.messages.observe(conversationID: conversation.id)
        var iterator = stream.makeAsyncIterator()
        let initial = try #require(await iterator.next())
        #expect(initial.count == 1)

        try await db.messages.insert(MessageRecord.outgoingText("due", conversationID: conversation.id, sender: me))
        let updated = try #require(await iterator.next())
        #expect(updated.count == 2)
        #expect(updated.last?.text == "due")
    }

    @Test("Relay queue: limite di conteggio con eviction FIFO")
    func relayQueueEnforcesCountLimit() async throws {
        let db = try AppDatabase.makeTemporary()
        let queue = RelayQueueStore(database: db, maxCount: 5, maxTotalBytes: MeshProtocol.relayQueueMaxBytes)
        var ids: [Data] = []
        let base = Date()
        for index in 0..<7 {
            let id = CryptoEngine.randomMessageID()
            ids.append(id)
            try await queue.enqueue(RelayQueueRecord(msgID: id, recipientID: Data(repeating: 1, count: 8),
                                                     rawEnvelopeBytes: Data(repeating: 0, count: 50),
                                                     enqueuedAt: base.addingTimeInterval(Double(index))),
                                    now: base.addingTimeInterval(Double(index)))
        }
        let stats = try await queue.stats()
        #expect(stats.count == 5)
        #expect(stats.totalBytes == 250)
        let pending = try await queue.pending(now: base.addingTimeInterval(10))
        #expect(pending.map(\.msgID) == Array(ids[2...]))
    }

    @Test("Relay queue: limite in byte, idempotenza e scadenza")
    func relayQueueEnforcesByteLimitAndExpiry() async throws {
        let db = try AppDatabase.makeTemporary()
        let queue = RelayQueueStore(database: db, maxCount: 200, maxTotalBytes: 1_000)
        let base = Date()
        let first = RelayQueueRecord(msgID: CryptoEngine.randomMessageID(), recipientID: Data(repeating: 2, count: 8),
                                     rawEnvelopeBytes: Data(repeating: 0, count: 400), enqueuedAt: base)
        try await queue.enqueue(first, now: base)
        try await queue.enqueue(first, now: base) // duplicato ignorato
        try await queue.enqueue(RelayQueueRecord(msgID: CryptoEngine.randomMessageID(), recipientID: Data(repeating: 2, count: 8),
                                                 rawEnvelopeBytes: Data(repeating: 0, count: 400), enqueuedAt: base.addingTimeInterval(1)),
                                now: base.addingTimeInterval(1))
        try await queue.enqueue(RelayQueueRecord(msgID: CryptoEngine.randomMessageID(), recipientID: Data(repeating: 2, count: 8),
                                                 rawEnvelopeBytes: Data(repeating: 0, count: 400), enqueuedAt: base.addingTimeInterval(2)),
                                now: base.addingTimeInterval(2))
        var stats = try await queue.stats()
        #expect(stats.count == 2)
        #expect(stats.totalBytes == 800)
        #expect(try await queue.pending(now: base).contains { $0.msgID == first.msgID } == false)

        await #expect(throws: RelayQueueStore.Failure.packetTooLarge(5_000)) {
            try await queue.enqueue(RelayQueueRecord(msgID: CryptoEngine.randomMessageID(), recipientID: Data(repeating: 2, count: 8),
                                                     rawEnvelopeBytes: Data(repeating: 0, count: 5_000)))
        }

        // Dopo 6 ore + 1 s tutto e' scaduto.
        let later = base.addingTimeInterval(MeshProtocol.relayQueueLifetime + 10)
        #expect(try await queue.pending(now: later).isEmpty)
        #expect(try await queue.purgeExpired(now: later) == 2)
        stats = try await queue.stats()
        #expect(stats.count == 0)
    }

    @Test("Seen packets: deduplicazione e pulizia scaduti")
    func seenPacketsDeduplicate() async throws {
        let db = try AppDatabase.makeTemporary()
        let id = CryptoEngine.randomMessageID()
        let now = Date()
        #expect(try await db.seenPackets.markSeenIfNew(msgID: id, now: now) == true)
        #expect(try await db.seenPackets.markSeenIfNew(msgID: id, now: now) == false)
        #expect(try await db.seenPackets.contains(msgID: id))
        #expect(try await db.seenPackets.activeMessageIDs(now: now) == [id])

        let later = now.addingTimeInterval(MeshProtocol.seenPacketLifetime + 1)
        #expect(try await db.seenPackets.purgeExpired(now: later) == 1)
        #expect(try await db.seenPackets.count() == 0)
    }

    @Test("Cabina Pubblica: si rigenera dopo 12 ore e i messaggi vecchi vengono eliminati")
    func cabinLoungeExpiresAfter12Hours() async throws {
        let db = try AppDatabase.makeTemporary()
        let start = Date()
        let lounge = try await db.conversations.currentCabinLounge(now: start)
        #expect(lounge.type == .cabinLounge)
        #expect(lounge.recipientID == MeshProtocol.loungeRecipientID)
        #expect(lounge.expiresAtMs == start.addingTimeInterval(MeshProtocol.loungeLifetime).unixMilliseconds)

        try await db.messages.insert(MessageRecord(msgID: CryptoEngine.randomMessageID(), conversationID: lounge.id,
                                                   senderNodeID: Data(repeating: 3, count: 8), senderNickname: "31F",
                                                   contentType: .text, text: "Caricatore?", isOutgoing: false,
                                                   deliveryStatus: .deliveredAck, createdAtMs: start.unixMilliseconds))
        #expect(try await db.messages.fetch(conversationID: lounge.id).count == 1)

        let sameLounge = try await db.conversations.currentCabinLounge(now: start.addingTimeInterval(3_600))
        #expect(sameLounge.expiresAtMs == lounge.expiresAtMs)

        let renewed = try await db.conversations.currentCabinLounge(now: start.addingTimeInterval(13 * 3_600))
        #expect(renewed.expiresAtMs != lounge.expiresAtMs)
        #expect(try await db.messages.fetch(conversationID: lounge.id).isEmpty)
    }

    @Test("Chat fissate in cima, poi per ultimo messaggio; eliminazione con messaggi in cascata")
    func pinnedConversationsComeFirst() async throws {
        let db = try AppDatabase.makeTemporary()
        let a = makePeer("A", trusted: true)
        let b = makePeer("B", trusted: true)
        try await db.peers.upsert(a)
        try await db.peers.upsert(b)
        let chatA = try await db.conversations.ensureDirectConversation(with: a)
        let chatB = try await db.conversations.ensureDirectConversation(with: b)
        var older = MessageRecord.outgoingText("vecchio", conversationID: chatA.id, sender: IdentityKeys.generate())
        older.createdAtMs -= 60_000
        try await db.messages.insert(older)
        try await db.messages.insert(MessageRecord.outgoingText("nuovo", conversationID: chatB.id, sender: IdentityKeys.generate()))
        #expect(try await db.conversations.fetchPermanent().map(\.id) == [chatB.id, chatA.id])

        try await db.conversations.setPinned(true, conversationID: chatA.id)
        let pinned = try await db.conversations.fetchPermanent()
        #expect(pinned.map(\.id) == [chatA.id, chatB.id])
        #expect(pinned.first?.isPinned == true)

        try await db.conversations.setPinned(false, conversationID: chatA.id)
        #expect(try await db.conversations.fetchPermanent().map(\.id) == [chatB.id, chatA.id])

        try await db.conversations.delete(conversationID: chatA.id)
        #expect(try await db.conversations.fetchPermanent().map(\.id) == [chatB.id])
        #expect(try await db.messages.fetch(msgID: older.msgID) == nil, "i messaggi seguono la chat eliminata")
    }

    @Test("Gruppi: chiave segreta, membri e lookup per recipient_id")
    func groupConversation() async throws {
        let db = try AppDatabase.makeTemporary()
        let a = makePeer("A", trusted: true)
        let b = makePeer("B", trusted: true)
        try await db.peers.upsert(a)
        try await db.peers.upsert(b)

        let groupID = UUID()
        let group = try await db.conversations.createGroup(id: groupID, title: "Volo FR1234", members: [a])
        try await db.conversations.addGroupMember(b, to: group.id)
        #expect(group.groupSecretKey?.count == 32)
        #expect(group.groupID == groupID)
        #expect(group.recipientID == CryptoEngine.groupRecipientID(groupID: groupID))
        #expect(try await db.conversations.fetch(recipientID: group.recipientID)?.id == group.id)
        #expect(try await db.conversations.groupMembers(of: group.id).count == 2)
        #expect(try await db.conversations.groupIDs(containing: b.nodeID) == [group.id])

        let until = Date().addingTimeInterval(MeshProtocol.proPassDuration).unixMilliseconds
        try await db.conversations.setProUnlocked(until: until, conversationID: group.id)
        #expect(try await db.conversations.fetch(id: group.id)?.isProUnlocked() == true)
        #expect(try await db.conversations.fetchPermanent().map(\.id) == [group.id])
    }

    @Test("Gruppi: la spunta blu arriva solo quando hanno letto tutti i membri (mittente escluso)")
    func groupReadRequiresAllMembers() async throws {
        let db = try AppDatabase.makeTemporary()
        let me = IdentityKeys.generate()
        let a = makePeer("A", trusted: true)
        let b = makePeer("B", trusted: true)
        let outsider = makePeer("Estraneo")
        for peer in [a, b, outsider] { try await db.peers.upsert(peer) }
        let group = try await db.conversations.createGroup(title: "Volo FR1234", members: [a, b])
        let message = MessageRecord.outgoingText("Imbarco al gate 12", conversationID: group.id, sender: me)
        try await db.messages.insert(message)
        try await db.messages.markSentMesh(msgIDs: [message.msgID])

        // Primo lettore: registrato, il messaggio diventa solo "consegnato".
        #expect(try await db.messages.markReadByRecipient(msgIDs: [message.msgID], readerNodeID: a.nodeID) == [message.msgID])
        #expect(try await db.messages.fetch(msgID: message.msgID)?.deliveryStatus == .deliveredAck)
        // Conferma duplicata e lettore non membro: nessun avanzamento.
        #expect(try await db.messages.markReadByRecipient(msgIDs: [message.msgID], readerNodeID: a.nodeID).isEmpty)
        #expect(try await db.messages.markReadByRecipient(msgIDs: [message.msgID], readerNodeID: outsider.nodeID).isEmpty)
        #expect(try await db.messages.fetch(msgID: message.msgID)?.deliveryStatus == .deliveredAck)
        #expect(Set(try await db.messages.readerNodeIDs(msgID: message.msgID)) == [a.nodeID, outsider.nodeID])

        // Ultimo membro: spunta blu.
        #expect(try await db.messages.markReadByRecipient(msgIDs: [message.msgID], readerNodeID: b.nodeID) == [message.msgID])
        #expect(try await db.messages.fetch(msgID: message.msgID)?.deliveryStatus == .read)

        // Un membro che entra dopo non riporta indietro un messaggio gia' letto da tutti.
        try await db.conversations.addGroupMember(makePeer("C"), to: group.id)
        #expect(try await db.messages.markReadByRecipient(msgIDs: [message.msgID], readerNodeID: a.nodeID).isEmpty)
        #expect(try await db.messages.fetch(msgID: message.msgID)?.deliveryStatus == .read)

        // I lettori seguono il messaggio eliminato.
        try await db.messages.delete(msgID: message.msgID)
        #expect(try await db.messages.readerNodeIDs(msgID: message.msgID).isEmpty)
    }

    @Test("Gruppi: con un nuovo membro noto la spunta blu attende anche lui")
    func groupReadWaitsForMembersKnownNow() async throws {
        let db = try AppDatabase.makeTemporary()
        let me = IdentityKeys.generate()
        let a = makePeer("A", trusted: true)
        let c = makePeer("C", trusted: true)
        try await db.peers.upsert(a)
        try await db.peers.upsert(c)
        let group = try await db.conversations.createGroup(title: "Volo AZ610", members: [a])
        let message = MessageRecord.outgoingText("Ciao a tutti", conversationID: group.id, sender: me)
        try await db.messages.insert(message)
        try await db.conversations.addGroupMember(c, to: group.id)

        _ = try await db.messages.markReadByRecipient(msgIDs: [message.msgID], readerNodeID: a.nodeID)
        #expect(try await db.messages.fetch(msgID: message.msgID)?.deliveryStatus == .deliveredAck)
        _ = try await db.messages.markReadByRecipient(msgIDs: [message.msgID], readerNodeID: c.nodeID)
        #expect(try await db.messages.fetch(msgID: message.msgID)?.deliveryStatus == .read)
    }
}
