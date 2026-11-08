import Foundation
import Testing
@testable import AeroChat

@Suite("Conferme di lettura e profilo")
struct ProfileReadReceiptTests {

    @Test("READ_RECEIPT: aprire la chat porta il messaggio del mittente a read (doppia spunta colorata)")
    func readReceiptMarksSenderMessageRead() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let conversation = try await alice.directConversation(with: bob)
        let sent = try await alice.router.sendText("Hai visto il tramonto?", in: conversation)
        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .deliveredAck })

        let bobConversation = try await bob.directConversation(with: alice)
        await bob.router.markConversationRead(bobConversation)

        #expect(try await bob.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .read)
        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .read })
        #expect(try await bob.database.conversations.fetch(id: bobConversation.id)?.unreadCount == 0)
    }

    @Test("READ_RECEIPT da un terzo non tocca i messaggi della chat 1-a-1 con un altro")
    func readReceiptFromOtherPeerIsIgnored() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        let mallory = try await TestNode.make("Mallory")
        try await TestNode.pair(alice, bob)
        try await TestNode.pair(alice, mallory)

        let conversation = try await alice.directConversation(with: bob)
        let sent = try await alice.router.sendText("Solo per Bob", in: conversation)
        let updated = try await alice.database.messages.markReadByRecipient(msgIDs: [sent.msgID], readerNodeID: mallory.nodeID)
        #expect(updated.isEmpty)
        #expect(try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus != .read)
    }

    @Test("READ_RECEIPT nei gruppi: la spunta blu arriva solo quando hanno letto tutti i membri")
    func groupReadReceiptRequiresAllMembers() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        let carol = try await TestNode.make("Carol")
        try await TestNode.pair(alice, bob)
        try await TestNode.pair(alice, carol)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        await FakeMeshNetwork.connect(alice.transport, carol.transport)

        let groupID = UUID()
        let key = CryptoEngine.randomGroupSecretKey()
        func peers(of node: TestNode, _ others: [TestNode]) async throws -> [PeerRecord] {
            var result: [PeerRecord] = []
            for other in others {
                result.append(try #require(try await node.database.peers.fetch(nodeID: other.nodeID)))
            }
            return result
        }
        let aliceGroup = try await alice.database.conversations.createGroup(id: groupID, title: "Volo", secretKey: key,
                                                                            members: try await peers(of: alice, [bob, carol]))
        let bobGroup = try await bob.database.conversations.createGroup(id: groupID, title: "Volo", secretKey: key,
                                                                        members: try await peers(of: bob, [alice]))
        let carolGroup = try await carol.database.conversations.createGroup(id: groupID, title: "Volo", secretKey: key,
                                                                            members: try await peers(of: carol, [alice]))

        let sent = try await alice.router.sendText("Tutti a bordo?", in: aliceGroup)
        #expect(await eventually { try await carol.database.messages.fetch(msgID: sent.msgID) != nil })
        #expect(await eventually { try await bob.database.messages.fetch(msgID: sent.msgID) != nil })
        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .deliveredAck })

        // Bob legge: Alice lo registra ma la spunta resta grigia (manca Carol).
        await bob.router.markConversationRead(bobGroup)
        #expect(await eventually { try await alice.database.messages.readerNodeIDs(msgID: sent.msgID) == [bob.nodeID] })
        #expect(try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .deliveredAck)

        // Carol legge: ora hanno letto tutti, spunta blu.
        await carol.router.markConversationRead(carolGroup)
        #expect(await eventually { try await alice.database.messages.fetch(msgID: sent.msgID)?.deliveryStatus == .read })
        #expect(Set(try await alice.database.messages.readerNodeIDs(msgID: sent.msgID)) == [bob.nodeID, carol.nodeID])
    }

    @Test("PROFILE_UPDATE: nickname, colore e foto arrivano al contatto e aggiornano il titolo della chat")
    func profileUpdatePropagates() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        try await TestNode.pair(alice, bob)
        await FakeMeshNetwork.connect(alice.transport, bob.transport)

        let avatar = Data("RIFF\u{0}\u{0}\u{0}\u{0}WEBPVP8 fake".utf8)
        let profile = LocalProfile(nickname: "Alice in volo", avatarColorHex: "EC4899", avatarImage: avatar,
                                   updatedAtMs: Date.nowUnixMilliseconds)
        await alice.router.setProfile(profile, announce: true)

        #expect(await eventually { try await bob.database.peers.fetch(nodeID: alice.nodeID)?.avatarImage == avatar })
        let peer = try #require(try await bob.database.peers.fetch(nodeID: alice.nodeID))
        #expect(peer.nickname == "Alice in volo")
        #expect(peer.avatarColorHex == "EC4899")
        #expect(try await bob.directConversation(with: alice).title == "Alice in volo")

        // Una versione piu' vecchia (es. ritrasmessa in ritardo) non sovrascrive quella nota.
        let stale = try await bob.database.peers.applyProfile(nodeID: alice.nodeID, nickname: "Vecchio", avatarColorHex: nil,
                                                              avatarImage: nil, updatedAtMs: profile.updatedAtMs - 1)
        #expect(!stale)
        // Il nickname riferito da terzi (gossip) non scavalca quello annunciato dal profilo.
        _ = try await bob.database.peers.upsert(PeerRecord(nodeID: alice.nodeID, x25519Pub: alice.identity.x25519PublicKeyRaw,
                                                           ed25519Pub: alice.identity.ed25519PublicKeyRaw, nickname: "Gossip"))
        #expect(try await bob.database.peers.fetch(nodeID: alice.nodeID)?.nickname == "Alice in volo")
    }

    @Test("Foto profilo: ritaglio quadrato 128x128 WebP entro il limite")
    func avatarPipeline() throws {
        let width = 400, height = 300
        let pixels = [UInt8](repeating: 180, count: width * height * 4)
        let image = try #require(RGBAPixels.makeImage(from: pixels, width: width, height: height))
        let webp = try WebPEncoder.encode(image, quality: 90)
        let avatar = try ImageProcessor.prepareAvatar(imageData: webp)
        #expect(WebPEncoder.isWebP(avatar))
        #expect(avatar.count <= MeshProtocol.maxAvatarBytes)
        let size = try #require(WebPEncoder.dimensions(of: avatar))
        #expect(size.width == MeshProtocol.avatarPixelSize && size.height == MeshProtocol.avatarPixelSize)
    }
}
