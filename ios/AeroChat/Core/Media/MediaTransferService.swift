import Foundation

/// Invio e ricezione di foto WebP e note vocali Opus sulla mesh:
/// `MEDIA_HEADER` (metadati + BlurHash/forma d'onda per l'anteprima istantanea) seguito da `MEDIA_CHUNK`.
actor MediaTransferService {

    enum Failure: Error, Equatable {
        case recipientOutOfRange
        case mediaTooLarge(Int)
        case unsupportedMime(String)
    }

    enum Event: Sendable, Equatable {
        /// Avanzamento in chunk (in ricezione o in invio) del media legato al messaggio `msgID`.
        case progress(msgID: Data, received: Int, total: Int)
        case completed(msgID: Data)
        /// Trasferimento fallito o scaduto: `msgID` permette alla UI di togliere l'anello di avanzamento.
        case failed(mediaID: String, msgID: Data?)
    }

    /// Chunk arrivati prima dell'header, con il mittente (solo lui puo' completare quel media).
    private struct OrphanChunks {
        let senderNodeID: Data
        let receivedAt: Date
        var chunks: [Int: Data] = [:]
        var bytes = 0
    }

    private struct Assembly {
        let mediaID: String
        let kind: MediaStore.Kind
        let headerMsgID: Data
        let conversation: ConversationRecord
        let sender: PeerRecord
        let totalChunks: Int
        let totalBytes: Int
        var chunks: [Int: Data] = [:]
        let startedAt: Date
    }

    static let chunkSize = 8_192
    static let maxMediaBytes = 1_048_576
    static let assemblyTimeout: TimeInterval = 300
    /// Trasferimenti in ricezione contemporanei (limite di memoria contro header a raffica).
    static let maxConcurrentAssemblies = 16
    static let maxChunks = maxMediaBytes / chunkSize
    static let maxOrphanBytes = 2 * 1_048_576
    static let orphanTimeout: TimeInterval = 60

    let router: MeshRouterActor
    let database: AppDatabase
    let store: MediaStore
    private let identity: IdentityKeys
    private var nickname: String

    private var assemblies: [String: Assembly] = [:]
    /// Chunk arrivati prima del loro header (riordino sulla mesh).
    private var orphanChunks: [String: OrphanChunks] = [:]
    private var inboundTask: Task<Void, Never>?
    private var subscribers: [UUID: AsyncStream<Event>.Continuation] = [:]

    init(router: MeshRouterActor, database: AppDatabase, store: MediaStore, identity: IdentityKeys, nickname: String) {
        self.router = router
        self.database = database
        self.store = store
        self.identity = identity
        self.nickname = nickname
    }

    func start() async {
        guard inboundTask == nil else { return }
        let packets = await router.inboundPackets()
        inboundTask = Task { [weak self] in
            for await packet in packets {
                guard let self else { break }
                await self.handle(packet)
            }
        }
    }

    func stop() {
        inboundTask?.cancel()
        inboundTask = nil
    }

    func setNickname(_ nickname: String) {
        self.nickname = nickname
    }

    func events() -> AsyncStream<Event> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(64)) { continuation in
            subscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeSubscriber(id) }
            }
        }
    }

    // MARK: - Invio

    @discardableResult
    func sendImage(_ image: ImageProcessor.Result, in conversation: ConversationRecord) async throws -> MessageRecord {
        try await send(kind: .image, data: image.webp, blurhash: image.blurhash, waveform: nil, durationMs: nil, in: conversation)
    }

    @discardableResult
    func sendVoiceNote(_ recording: VoiceNoteRecorder.Recording, in conversation: ConversationRecord) async throws -> MessageRecord {
        try await send(kind: .audio, data: recording.oggOpus, blurhash: nil, waveform: recording.waveform,
                       durationMs: recording.durationMs, in: conversation)
    }

    private func send(kind: MediaStore.Kind, data: Data, blurhash: String?, waveform: Data?, durationMs: Int?,
                      in conversation: ConversationRecord) async throws -> MessageRecord {
        guard data.count <= Self.maxMediaBytes else { throw Failure.mediaTooLarge(data.count) }
        // I media non entrano nella coda store-and-forward: serve un vicino a portata adesso.
        guard await router.reachability(of: conversation) != .outOfRange else { throw Failure.recipientOutOfRange }

        let mediaID = UUID().uuidString.lowercased()
        let headerMsgID = CryptoEngine.randomMessageID()
        let localPath = try store.write(data, mediaID: mediaID, kind: kind)
        let chunks = stride(from: 0, to: data.count, by: Self.chunkSize).map { start in
            data.subdata(in: start..<min(start + Self.chunkSize, data.count))
        }

        let message = MessageRecord(msgID: headerMsgID,
                                    conversationID: conversation.id,
                                    senderNodeID: identity.nodeID,
                                    senderNickname: nickname,
                                    contentType: kind == .image ? .image : .audio,
                                    mediaLocalPath: localPath,
                                    blurhash: blurhash,
                                    audioWaveform: waveform,
                                    durationMs: durationMs,
                                    isOutgoing: true,
                                    deliveryStatus: .queued)
        try await database.messages.insert(message)
        // La bolla mostra subito l'anello di invio, prima ancora che parta l'header.
        emit(.progress(msgID: headerMsgID, received: 0, total: chunks.count))

        var header = InnerPayload.signed(by: identity, nickname: nickname)
        header.mediaID = mediaID
        header.mimeType = kind.mimeType
        header.totalSizeBytes = UInt32(data.count)
        header.totalChunks = UInt32(chunks.count)
        if let blurhash { header.blurhash = blurhash }
        if let waveform { header.audioWaveform = waveform }
        if let durationMs { header.durationMs = UInt32(durationMs) }
        do {
            return try await sendChunks(chunks, header: header, message: message, mediaID: mediaID, kind: kind, in: conversation)
        } catch {
            emit(.failed(mediaID: mediaID, msgID: headerMsgID))
            throw error
        }
    }

    private func sendChunks(_ chunks: [Data], header: InnerPayload, message: MessageRecord, mediaID: String,
                            kind: MediaStore.Kind, in conversation: ConversationRecord) async throws -> MessageRecord {
        let headerMsgID = message.msgID
        let headerResult = try await router.sendPacket(type: .mediaHeader, payload: header, in: conversation,
                                                       msgID: headerMsgID, storeIfUnreachable: false)

        for (index, chunk) in chunks.enumerated() {
            var payload = InnerPayload.signed(by: identity, nickname: nickname)
            payload.mediaID = mediaID
            payload.mimeType = kind.mimeType
            payload.totalChunks = UInt32(chunks.count)
            payload.chunkIndex = UInt32(index)
            payload.chunkData = chunk
            _ = try await router.sendPacket(type: .mediaChunk, payload: payload, in: conversation, storeIfUnreachable: false)
            emit(.progress(msgID: headerMsgID, received: index + 1, total: chunks.count))
        }

        var sent = message
        sent.deliveryStatus = headerResult.reached ? .sentMesh : .queued
        try await database.messages.updateDeliveryStatus(sent.deliveryStatus, msgID: headerMsgID)
        emit(.completed(msgID: headerMsgID))
        return sent
    }

    // MARK: - Ricezione

    private func handle(_ packet: InboundPacket) async {
        switch packet.envelope.packetType {
        case .mediaHeader:
            await handleHeader(packet)
        case .mediaChunk:
            await handleChunk(packet)
        default:
            break
        }
        purgeStaleAssemblies()
    }

    private func handleHeader(_ packet: InboundPacket) async {
        let payload = packet.payload
        let totalBytes = Int(payload.totalSizeBytes)
        guard let kind = MediaStore.Kind(mimeType: payload.mimeType),
              !payload.mediaID.isEmpty,
              totalBytes > 0, totalBytes <= Self.maxMediaBytes,
              // Numero di chunk coerente con la dimensione: altrimenti il media non si completerebbe mai.
              Int(payload.totalChunks) == (totalBytes + Self.chunkSize - 1) / Self.chunkSize,
              // Header ripetuto (flooding): non buttiamo i chunk gia' ricevuti.
              assemblies[payload.mediaID] == nil,
              assemblies.count < Self.maxConcurrentAssemblies else { return }

        let placeholder = MessageRecord(msgID: packet.envelope.msgID,
                                        conversationID: packet.conversation.id,
                                        senderNodeID: packet.sender.nodeID,
                                        senderNickname: payload.senderNickname.isEmpty ? packet.sender.nickname : payload.senderNickname,
                                        contentType: kind == .image ? .image : .audio,
                                        blurhash: payload.blurhash.isEmpty ? nil : payload.blurhash,
                                        audioWaveform: payload.audioWaveform.isEmpty ? nil : payload.audioWaveform,
                                        durationMs: payload.durationMs == 0 ? nil : Int(payload.durationMs),
                                        isOutgoing: false,
                                        deliveryStatus: .deliveredAck,
                                        hopCountReceived: packet.hopCount,
                                        createdAtMs: packet.envelope.createdAtMs)
        _ = try? await database.messages.insert(placeholder)

        var assembly = Assembly(mediaID: payload.mediaID,
                                kind: kind,
                                headerMsgID: packet.envelope.msgID,
                                conversation: packet.conversation,
                                sender: packet.sender,
                                totalChunks: Int(payload.totalChunks),
                                totalBytes: Int(payload.totalSizeBytes),
                                startedAt: Date())
        if let orphans = orphanChunks.removeValue(forKey: payload.mediaID), orphans.senderNodeID == packet.sender.nodeID {
            assembly.chunks = orphans.chunks.filter { $0.key < assembly.totalChunks }
        }
        assemblies[payload.mediaID] = assembly
        await completeIfReady(mediaID: payload.mediaID)
    }

    private func handleChunk(_ packet: InboundPacket) async {
        let payload = packet.payload
        guard !payload.mediaID.isEmpty, !payload.chunkData.isEmpty, payload.chunkData.count <= Self.chunkSize else { return }
        let index = Int(payload.chunkIndex)
        guard index < Self.maxChunks else { return }
        if var assembly = assemblies[payload.mediaID] {
            guard assembly.sender.nodeID == packet.sender.nodeID, index < assembly.totalChunks else { return }
            assembly.chunks[index] = payload.chunkData
            assemblies[payload.mediaID] = assembly
            emit(.progress(msgID: assembly.headerMsgID, received: assembly.chunks.count, total: assembly.totalChunks))
            await completeIfReady(mediaID: payload.mediaID)
        } else {
            var orphans = orphanChunks[payload.mediaID]
                ?? OrphanChunks(senderNodeID: packet.sender.nodeID, receivedAt: Date())
            guard orphans.senderNodeID == packet.sender.nodeID, orphans.chunks[index] == nil,
                  orphanBytes + payload.chunkData.count <= Self.maxOrphanBytes else { return }
            orphans.chunks[index] = payload.chunkData
            orphans.bytes += payload.chunkData.count
            orphanChunks[payload.mediaID] = orphans
        }
    }

    private func completeIfReady(mediaID: String) async {
        guard let assembly = assemblies[mediaID], assembly.chunks.count == assembly.totalChunks else { return }
        assemblies.removeValue(forKey: mediaID)

        var data = Data(capacity: assembly.totalBytes)
        for index in 0..<assembly.totalChunks {
            guard let chunk = assembly.chunks[index] else {
                emit(.failed(mediaID: mediaID, msgID: assembly.headerMsgID))
                return
            }
            data.append(chunk)
        }
        guard data.count == assembly.totalBytes else {
            emit(.failed(mediaID: mediaID, msgID: assembly.headerMsgID))
            return
        }
        if assembly.kind == .image, !WebPEncoder.isWebP(data) {
            emit(.failed(mediaID: mediaID, msgID: assembly.headerMsgID))
            return
        }
        do {
            let path = try store.write(data, mediaID: mediaID, kind: assembly.kind)
            // Cancellato per tutti mentre arrivavano i chunk: il file non serve piu'.
            guard try await database.messages.updateMedia(localPath: path, msgID: assembly.headerMsgID) else {
                store.remove(relativePath: path)
                emit(.failed(mediaID: mediaID, msgID: assembly.headerMsgID))
                return
            }
            // ACK sul msg_id dell'header: il mittente passa a delivered_ack.
            let ack = InnerPayload.deliveryAck(for: assembly.headerMsgID, identity: identity, nickname: nickname)
            _ = try? await router.sendDirectPacket(type: .deliveryAck, payload: ack, to: assembly.sender, ttl: MeshProtocol.ackTTL)
            emit(.completed(msgID: assembly.headerMsgID))
        } catch {
            emit(.failed(mediaID: mediaID, msgID: assembly.headerMsgID))
        }
    }

    private func purgeStaleAssemblies(now: Date = Date()) {
        let stale = assemblies.filter { now.timeIntervalSince($0.value.startedAt) > Self.assemblyTimeout }
        for (mediaID, assembly) in stale {
            assemblies.removeValue(forKey: mediaID)
            emit(.failed(mediaID: mediaID, msgID: assembly.headerMsgID))
        }
        // Chunk orfani (header perso o media gia' completato): scadono dopo un minuto.
        orphanChunks = orphanChunks.filter { now.timeIntervalSince($0.value.receivedAt) < Self.orphanTimeout }
    }

    private var orphanBytes: Int {
        orphanChunks.values.reduce(0) { $0 + $1.bytes }
    }

    private func emit(_ event: Event) {
        for continuation in subscribers.values {
            continuation.yield(event)
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }
}
