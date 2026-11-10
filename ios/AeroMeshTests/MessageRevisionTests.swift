import Testing
import Foundation
@testable import AeroChat

@Suite("MESSAGE_REVISION – modifica e cancellazione per tutti entro 15 minuti")
struct MessageRevisionTests {

    // MARK: - Protocollo

    @Test("MessageRevision: round-trip Protobuf e PacketType 14")
    func revisionProtoRoundTrip() throws {
        var revision = MessageRevision()
        revision.targetMsgID = Data(1...16)
        revision.action = .edit
        revision.newText = "Gate cambiato: B14"
        revision.revisedAtMs = 1_762_000_000_123
        let bytes = try MeshWire.serialize(revision)
        let decoded = try MeshWire.parseMessageRevision(bytes)
        #expect(decoded == revision)
        #expect(decoded.action.rawValue == 1)

        var delete = MessageRevision()
        delete.targetMsgID = Data(repeating: 7, count: 16)
        delete.action = .delete
        delete.revisedAtMs = 5
        #expect(try MeshWire.parseMessageRevision(try MeshWire.serialize(delete)).action == .delete)
        #expect(RevisionAction(rawValue: 2) == .delete)

        #expect(PacketType.messageRevision.rawValue == 14)
        #expect(PacketType(rawValue: 14) == .messageRevision)
        #expect(PacketType.allCases.contains(.messageRevision))
        #expect(PacketType.messageRevision.isStoreAndForwardEligible)

        var envelope = MeshEnvelope()
        envelope.packetType = .messageRevision
        #expect(try MeshWire.parseEnvelope(try MeshWire.serialize(envelope)).packetType == .messageRevision)
    }

    // MARK: - Chat 1-a-1

    @Test("1-a-1: EDIT aggiorna testo e edited_at, un EDIT piu' vecchio viene ignorato, l'ACK svuota la coda")
    func directEdit() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let conversation = try await alice.directConversation(with: bob)
        let sent = try await alice.router.sendText("Gate B12", in: conversation)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })

        let original = try #require(try await alice.database.messages.fetch(msgID: sent.msgID))
        try await alice.router.reviseMessage(original, action: .edit, newText: "  Gate B14  ")
        let mine = try #require(try await alice.database.messages.fetch(msgID: sent.msgID))
        #expect(mine.text == "Gate B14")
        #expect(mine.editedAtMs != nil)

        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID)?.text == "Gate B14" })
        let onBob = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(onBob.editedAtMs == mine.editedAtMs)
        #expect(bob.notifier.notified.count == 1, "nessuna notifica per la revisione")

        // EDIT con revised_at piu' vecchio: perde contro quello gia' applicato.
        var stale = MessageRevision()
        stale.targetMsgID = sent.msgID
        stale.action = .edit
        stale.newText = "Gate A1"
        stale.revisedAtMs = (mine.editedAtMs ?? 0) - 1_000
        try await alice.router.sendRevisionPacket(stale, in: conversation)
        try await Task.sleep(for: .milliseconds(300))
        #expect(try await bob.database.messages.fetch(msgID: sent.msgID)?.text == "Gate B14")

        // Le revisioni confermate escono dalla coda store-and-forward.
        #expect(await eventually { try await alice.database.relayQueue.pending().isEmpty })
    }

    @Test("1-a-1: DELETE cancella per tutti, azzera il non letto ed e' definitivo")
    func directDelete() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let conversation = try await alice.directConversation(with: bob)
        let sent = try await alice.router.sendText("Messaggio sbagliato", in: conversation)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })
        let bobConversation = try await bob.directConversation(with: alice)
        #expect(try await bob.database.conversations.fetch(id: bobConversation.id)?.unreadCount == 1)
        #expect(try await bob.database.conversations.totalUnread() == 1)

        let original = try #require(try await alice.database.messages.fetch(msgID: sent.msgID))
        try await alice.router.reviseMessage(original, action: .delete)
        let mine = try #require(try await alice.database.messages.fetch(msgID: sent.msgID))
        #expect(mine.isDeleted)
        #expect(mine.text == nil)

        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID)?.isDeleted == true })
        let onBob = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(onBob.text == nil)
        #expect(try await bob.database.conversations.fetch(id: bobConversation.id)?.unreadCount == 0)
        #expect(try await bob.database.conversations.totalUnread() == 0)

        // Un EDIT dopo il DELETE viene ignorato; anche il mittente non puo' piu' modificarlo.
        var late = MessageRevision()
        late.targetMsgID = sent.msgID
        late.action = .edit
        late.newText = "Resuscitato"
        late.revisedAtMs = Date.nowUnixMilliseconds
        try await alice.router.sendRevisionPacket(late, in: conversation)
        try await Task.sleep(for: .milliseconds(300))
        let after = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(after.isDeleted)
        #expect(after.text == nil)
        await #expect(throws: RouterError.revisionNotAllowed) {
            try await alice.router.reviseMessage(mine, action: .edit, newText: "ancora")
        }
    }

    // MARK: - Gruppo

    @Test("Gruppo: EDIT e DELETE arrivano a tutti i membri, anche via ponte; un altro membro non puo' revisionare")
    func groupRevisionsAndWrongAuthor() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, bob)
        try await TestNode.pair(bob, carol)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        await FakeMeshNetwork.connect(bob.transport, carol.transport)

        let group = try await bob.router.createGroup(title: "Volo AZ204")
        let invite = try #require(await bob.router.groupInvitePayload(for: group))
        let aliceGroup = try await alice.router.pair(with: invite)
        let carolGroup = try await carol.router.pair(with: invite)
        #expect(await eventually {
            try await carol.database.conversations.groupMembers(of: carolGroup.id).map(\.nodeID).contains(alice.nodeID)
        })

        let sent = try await alice.router.sendText("Ci vediamo al nastro 3", in: aliceGroup)
        #expect(await eventually { try await carol.database.messages.fetch(msgID: sent.msgID) != nil })
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })

        // Carol prova a modificare il messaggio di Alice: scartato da tutti.
        var forged = MessageRevision()
        forged.targetMsgID = sent.msgID
        forged.action = .edit
        forged.newText = "Testo falso"
        forged.revisedAtMs = Date.nowUnixMilliseconds
        try await carol.router.sendRevisionPacket(forged, in: carolGroup)
        var forgedDelete = forged
        forgedDelete.action = .delete
        try await carol.router.sendRevisionPacket(forgedDelete, in: carolGroup)
        try await Task.sleep(for: .milliseconds(400))
        let untouched = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(untouched.text == "Ci vediamo al nastro 3")
        #expect(untouched.editedAtMs == nil)
        #expect(!untouched.isDeleted)
        #expect(await carol.router.isDirectNeighbor(bob.nodeID))

        // Alice modifica e poi cancella: Bob (diretto) e Carol (via Bob) si allineano.
        let original = try #require(try await alice.database.messages.fetch(msgID: sent.msgID))
        try await alice.router.reviseMessage(original, action: .edit, newText: "Ci vediamo al nastro 5")
        #expect(await eventually { try await carol.database.messages.fetch(msgID: sent.msgID)?.text == "Ci vediamo al nastro 5" })
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID)?.text == "Ci vediamo al nastro 5" })

        let edited = try #require(try await alice.database.messages.fetch(msgID: sent.msgID))
        try await alice.router.reviseMessage(edited, action: .delete)
        #expect(await eventually { try await carol.database.messages.fetch(msgID: sent.msgID)?.isDeleted == true })
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID)?.isDeleted == true })
    }

    // MARK: - Finestra dei 15 minuti

    @Test("Fuori finestra: il mittente non puo' revisionare dopo 15 minuti e il ricevente scarta revisioni tardive")
    func outsideWindowIsRejected() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        let conversation = try await alice.directConversation(with: bob)

        // Lato mittente: messaggio di 20 minuti fa.
        let old = MessageRecord(msgID: CryptoEngine.randomMessageID(), conversationID: conversation.id,
                                senderNodeID: alice.nodeID, contentType: .text, text: "vecchio",
                                isOutgoing: true, deliveryStatus: .deliveredAck,
                                createdAtMs: Date.nowUnixMilliseconds - 20 * 60 * 1_000)
        try await alice.database.messages.insert(old)
        #expect(!old.canBeEdited())
        #expect(!old.canBeDeletedForEveryone())
        await #expect(throws: RouterError.revisionWindowExpired) {
            try await alice.router.reviseMessage(old, action: .delete)
        }
        #expect(try await alice.database.messages.fetch(msgID: old.msgID)?.isDeleted == false)

        // Lato ricevente: revisione con revised_at 20 minuti dopo l'originale (oltre i 15 + 2 di tolleranza).
        let sent = try await alice.router.sendText("Gate B12", in: conversation)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })
        let onBob = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        var late = MessageRevision()
        late.targetMsgID = sent.msgID
        late.action = .delete
        late.revisedAtMs = onBob.createdAtMs + 20 * 60 * 1_000
        try await alice.router.sendRevisionPacket(late, in: conversation)
        var lateEdit = late
        lateEdit.action = .edit
        lateEdit.newText = "troppo tardi"
        try await alice.router.sendRevisionPacket(lateEdit, in: conversation)
        try await Task.sleep(for: .milliseconds(400))
        let after = try #require(try await bob.database.messages.fetch(msgID: sent.msgID))
        #expect(!after.isDeleted)
        #expect(after.text == "Gate B12")
        // Scartata ma confermata: il mittente smette di ritrasmetterla.
        #expect(await eventually { try await alice.database.relayQueue.pending().isEmpty })
    }

    // MARK: - Ordine di arrivo

    @Test("Revisione arrivata prima dell'originale: tenuta in memoria e applicata all'arrivo del messaggio")
    func revisionBeforeOriginalIsAppliedLater() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        try await Task.sleep(for: .milliseconds(200))
        let conversation = try await alice.directConversation(with: bob)

        await bob.transport.holdIncoming()
        let sent = try await alice.router.sendText("Bozza", in: conversation)
        let original = try #require(try await alice.database.messages.fetch(msgID: sent.msgID))
        try await alice.router.reviseMessage(original, action: .edit, newText: "Versione finale")
        #expect(await eventually { await bob.transport.heldFrameCount() >= 2 })

        // La revisione (spedita per ultima) arriva per prima.
        await bob.transport.releaseHeldFrames(reversed: true)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID)?.text == "Versione finale" })
        #expect(try await bob.database.messages.fetch(msgID: sent.msgID)?.editedAtMs != nil)

        // DELETE prima dell'originale: il messaggio arriva gia' cancellato e non viene notificato.
        let notifiedBefore = bob.notifier.notified.count
        await bob.transport.holdIncoming()
        let second = try await alice.router.sendText("Da cancellare", in: conversation)
        let secondRecord = try #require(try await alice.database.messages.fetch(msgID: second.msgID))
        try await alice.router.reviseMessage(secondRecord, action: .delete)
        #expect(await eventually { await bob.transport.heldFrameCount() >= 2 })
        await bob.transport.releaseHeldFrames(reversed: true)
        #expect(await eventually { try await bob.database.messages.fetch(msgID: second.msgID)?.isDeleted == true })
        let bobConversation = try await bob.directConversation(with: alice)
        #expect(try await bob.database.conversations.fetch(id: bobConversation.id)?.unreadCount == 1, "conta solo il primo messaggio")
        #expect(bob.notifier.notified.count == notifiedBefore)
    }

    // MARK: - Database

    @Test("DB: DELETE restituisce il path del media e azzera blurhash/forma d'onda; updateMedia non lo resuscita")
    func deleteReturnsMediaPath() async throws {
        let db = try AppDatabase.makeTemporary()
        let me = IdentityKeys.generate()
        let peer = PeerRecord(nodeID: Data(repeating: 9, count: 8), x25519Pub: Data(repeating: 1, count: 32),
                              ed25519Pub: Data(repeating: 2, count: 32), nickname: "Bob")
        _ = try await db.peers.upsert(peer)
        let conversation = try await db.conversations.ensureDirectConversation(with: peer)
        let photo = MessageRecord(msgID: CryptoEngine.randomMessageID(), conversationID: conversation.id,
                                  senderNodeID: me.nodeID, contentType: .image, mediaLocalPath: "images/a.webp",
                                  blurhash: "LKO2?U%2Tw=w]~RBVZRi};RPxuwH", isOutgoing: true, deliveryStatus: .sentMesh)
        try await db.messages.insert(photo)
        let result = try await db.messages.applyDeleteForEveryone(msgID: photo.msgID)
        #expect(result.deleted)
        #expect(result.mediaPath == "images/a.webp")
        let stored = try #require(try await db.messages.fetch(msgID: photo.msgID))
        #expect(stored.isDeleted && stored.mediaLocalPath == nil && stored.blurhash == nil)
        #expect(try await db.messages.updateMedia(localPath: "images/b.webp", msgID: photo.msgID) == false)
        #expect(try await db.messages.applyDeleteForEveryone(msgID: photo.msgID).deleted == false)
    }
}
