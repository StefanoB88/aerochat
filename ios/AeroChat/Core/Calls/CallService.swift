import Foundation
import AVFoundation
import AudioToolbox
import Observation
import UIKit
import UserNotifications

/// Chiamate vocali in tempo reale tra vicini diretti: segnalazione `CALL_SIGNAL`, audio `CALL_AUDIO`,
/// squillo con timeout, monitoraggio del link e registro chiamate nella chat.
@MainActor
@Observable
final class CallService {

    enum Direction: Sendable, Equatable {
        case outgoing
        case incoming
    }

    enum EndReason: Sendable, Equatable {
        case hungUp
        case rejected
        case busy
        case noAnswer
        case missed
        case connectionLost
        case unreachable
        case microphoneDenied
        case failed
    }

    enum Phase: Sendable, Equatable {
        /// In uscita: squilla dal compagno. In entrata: sta squillando qui.
        case ringing
        case active
        case ended(EndReason)
    }

    struct Call: Identifiable, Equatable {
        let id: String
        let peer: PeerRecord
        let conversation: ConversationRecord
        let direction: Direction
        var phase: Phase = .ringing
        var connectedAt: Date?
        var isMuted = false
        var isSpeakerOn = false
        /// Pacchetti audio in ritardo: il link radio fatica (avvicinarsi).
        var isSignalWeak = false
    }

    private(set) var current: Call?

    private let router: MeshRouterActor
    private let database: AppDatabase
    private let identity: IdentityKeys
    private var nickname: String
    private let audio = CallAudioEngine()

    private var listenTasks: [Task<Void, Never>] = []
    private var callTasks: [Task<Void, Never>] = []
    private var audioContinuation: AsyncStream<[Data]>.Continuation?
    private var lastAudioAt = Date.distantPast

    init(router: MeshRouterActor, database: AppDatabase, identity: IdentityKeys, nickname: String) {
        self.router = router
        self.database = database
        self.identity = identity
        self.nickname = nickname
    }

    func setNickname(_ nickname: String) {
        self.nickname = nickname
    }

    func start() async {
        guard listenTasks.isEmpty else { return }
        let packets = await router.inboundPackets()
        listenTasks.append(Task { [weak self] in
            for await packet in packets where packet.envelope.packetType == .callSignal {
                guard let self else { break }
                await self.handleSignal(packet)
            }
        })
        let audioPackets = await router.callAudioPackets()
        listenTasks.append(Task { [weak self] in
            for await packet in audioPackets {
                guard let self else { break }
                self.handleAudio(packet)
            }
        })
    }

    func stop() {
        if current != nil { hangUp() }
        listenTasks.forEach { $0.cancel() }
        listenTasks.removeAll()
    }

    // MARK: - Azioni dell'utente

    /// Chiama il compagno della chat 1-a-1 (il gate su Pass/segnale e' gia' stato applicato dalla UI).
    func placeCall(in conversation: ConversationRecord) async {
        guard current == nil, conversation.type == .direct, let peerID = conversation.peerNodeID,
              let peer = try? await database.peers.fetch(nodeID: peerID) else { return }
        let call = Call(id: UUID().uuidString.lowercased(), peer: peer, conversation: conversation, direction: .outgoing)
        current = call
        guard await PermissionPrompt.ensureMicrophone() else {
            finish(call.id, reason: .microphoneDenied, notifyPeer: false)
            return
        }
        guard await send(.offer, callID: call.id, to: peer) else {
            finish(call.id, reason: .unreachable, notifyPeer: false)
            return
        }
        armRingTimeout(for: call.id)
    }

    func accept() {
        guard let call = current, call.direction == .incoming, call.phase == .ringing else { return }
        Task {
            guard await PermissionPrompt.ensureMicrophone() else {
                _ = await send(.reject, callID: call.id, to: call.peer)
                finish(call.id, reason: .microphoneDenied, notifyPeer: false)
                return
            }
            guard current?.id == call.id, current?.phase == .ringing else { return }
            guard await send(.accept, callID: call.id, to: call.peer) else {
                finish(call.id, reason: .unreachable, notifyPeer: false)
                return
            }
            beginMedia(for: call.id)
        }
    }

    func decline() {
        guard let call = current, call.direction == .incoming, call.phase == .ringing else { return }
        Task { _ = await send(.reject, callID: call.id, to: call.peer) }
        finish(call.id, reason: .rejected, notifyPeer: false)
    }

    func hangUp() {
        // Anche prima della risposta: il compagno smette di squillare (per lui e' una chiamata persa).
        guard let call = current else { return }
        finish(call.id, reason: .hungUp, notifyPeer: true)
    }

    func toggleMute() {
        guard var call = current, call.phase == .active else { return }
        call.isMuted.toggle()
        current = call
        audio.setMuted(call.isMuted)
    }

    func toggleSpeaker() {
        guard var call = current, call.phase == .active else { return }
        call.isSpeakerOn.toggle()
        current = call
        audio.setSpeaker(call.isSpeakerOn)
    }

    // MARK: - Segnalazione

    private func handleSignal(_ packet: InboundPacket) async {
        guard let signal = try? MeshWire.parseCallSignal(packet.payload.extraData), !signal.callID.isEmpty,
              signal.callID.count <= 64 else { return }
        let sender = packet.sender
        switch signal.action {
        case .offer:
            if let call = current {
                // Gia' al telefono (o lo stesso OFFER ritrasmesso): occupato per chiunque altro.
                if call.id != signal.callID { _ = await send(.busy, callID: signal.callID, to: sender) }
                return
            }
            guard packet.conversation.type == .direct else { return }
            current = Call(id: signal.callID, peer: sender, conversation: packet.conversation, direction: .incoming)
            startRinging(signal.callID, from: sender, conversation: packet.conversation)
        case .accept:
            guard let call = current, call.id == signal.callID, call.peer.nodeID == sender.nodeID,
                  call.direction == .outgoing, call.phase == .ringing else { return }
            beginMedia(for: call.id)
        case .reject:
            guard matches(signal, sender) else { return }
            finish(signal.callID, reason: .rejected, notifyPeer: false)
        case .busy:
            guard matches(signal, sender) else { return }
            finish(signal.callID, reason: .busy, notifyPeer: false)
        case .hangup:
            guard let call = current, matches(signal, sender) else { return }
            // Il chiamante ha rinunciato mentre squillava da noi: per noi e' una chiamata persa.
            let reason: EndReason = call.direction == .incoming && call.phase == .ringing ? .missed : .hungUp
            finish(signal.callID, reason: reason, notifyPeer: false)
        default:
            return
        }
    }

    private func matches(_ signal: CallSignal, _ sender: PeerRecord) -> Bool {
        current?.id == signal.callID && current?.peer.nodeID == sender.nodeID
    }

    @discardableResult
    private func send(_ action: CallAction, callID: String, to peer: PeerRecord) async -> Bool {
        var signal = CallSignal()
        signal.callID = callID
        signal.action = action
        guard let data = try? MeshWire.serialize(signal) else { return false }
        let payload = InnerPayload.extra(data, identity: identity, nickname: nickname)
        return await router.sendCallPacket(type: .callSignal, payload: payload, to: peer)
    }

    private func armRingTimeout(for callID: String) {
        callTasks.append(Task { [weak self] in
            try? await Task.sleep(for: .seconds(MeshProtocol.callRingTimeout))
            guard !Task.isCancelled, let self, let call = self.current, call.id == callID, call.phase == .ringing else { return }
            self.finish(callID, reason: call.direction == .outgoing ? .noAnswer : .missed, notifyPeer: true)
        })
    }

    private func startRinging(_ callID: String, from peer: PeerRecord, conversation: ConversationRecord) {
        armRingTimeout(for: callID)
        if UIApplication.shared.applicationState != .active {
            postIncomingCallNotification(callID: callID, from: peer, conversation: conversation)
        }
        // Squillo: vibrazione ritmata finche' l'utente non risponde.
        callTasks.append(Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.current?.id == callID, self.current?.phase == .ringing else { break }
                Haptics.impact(.heavy)
                AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
                try? await Task.sleep(for: .seconds(2))
            }
        })
    }

    // MARK: - Audio

    private func beginMedia(for callID: String) {
        guard var call = current, call.id == callID else { return }
        let (stream, continuation) = AsyncStream.makeStream(of: [Data].self, bufferingPolicy: .bufferingNewest(8))
        audioContinuation = continuation
        do {
            try audio.start { frames in continuation.yield(frames) }
        } catch {
            finish(callID, reason: .failed, notifyPeer: true)
            return
        }
        call.phase = .active
        call.connectedAt = Date()
        current = call
        lastAudioAt = Date()
        cancelCallTasks()
        Haptics.success()

        // Invio sequenziale (un solo consumatore): i pacchetti partono nell'ordine di cattura.
        let peer = call.peer
        let identity = identity
        let router = router
        let nickname = nickname
        callTasks.append(Task.detached(priority: .userInitiated) {
            var sequence: UInt32 = 0
            for await frames in stream {
                sequence &+= 1
                var packet = CallAudio()
                packet.callID = callID
                packet.sequence = sequence
                packet.frames = frames
                guard let data = try? MeshWire.serialize(packet) else { continue }
                let payload = InnerPayload.extra(data, identity: identity, nickname: nickname)
                _ = await router.sendCallPacket(type: .callAudio, payload: payload, to: peer)
            }
        })
        // Monitoraggio del link: segnale debole dopo 2 s senza audio, chiusura dopo `callMediaTimeout`.
        callTasks.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, var call = self.current, call.id == callID, call.phase == .active else { break }
                let silence = Date().timeIntervalSince(self.lastAudioAt)
                if silence > MeshProtocol.callMediaTimeout {
                    self.finish(callID, reason: .connectionLost, notifyPeer: true)
                    break
                }
                let weak = silence > 2
                if weak != call.isSignalWeak {
                    call.isSignalWeak = weak
                    self.current = call
                }
            }
        })
    }

    private func handleAudio(_ packet: CallAudioPacket) {
        guard let call = current, call.phase == .active, call.id == packet.audio.callID,
              call.peer.nodeID == packet.senderNodeID, !packet.audio.frames.isEmpty else { return }
        lastAudioAt = Date()
        audio.receive(sequence: packet.audio.sequence, frames: packet.audio.frames)
    }

    // MARK: - Chiusura

    private func finish(_ callID: String, reason: EndReason, notifyPeer: Bool) {
        guard var call = current, call.id == callID else { return }
        if case .ended = call.phase { return }
        if notifyPeer {
            let peer = call.peer
            Task { await self.send(.hangup, callID: callID, to: peer) }
        }
        cancelCallTasks()
        audioContinuation?.finish()
        audioContinuation = nil
        audio.stop()
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [callID])

        let duration = call.connectedAt.map { Date().timeIntervalSince($0) }
        call.phase = .ended(reason)
        current = call
        Task { await logCall(call, reason: reason, duration: duration) }
        if reason != .hungUp { Haptics.warning() }

        // Il riepilogo resta a schermo un attimo, poi la schermata di chiamata si chiude.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            guard let self, self.current?.id == callID else { return }
            self.current = nil
        }
    }

    private func cancelCallTasks() {
        callTasks.forEach { $0.cancel() }
        callTasks.removeAll()
    }

    /// Registro nella chat (solo locale): durata, chiamata persa, rifiutata, senza risposta.
    private func logCall(_ call: Call, reason: EndReason, duration: TimeInterval?) async {
        let text: String
        if let duration {
            text = String(localized: "📞 Chiamata vocale · \(MessageBubble.durationLabel(Int(duration * 1_000)))")
        } else {
            switch (call.direction, reason) {
            case (.incoming, .missed), (.incoming, .connectionLost), (.incoming, .noAnswer):
                text = String(localized: "📞 Chiamata persa")
            case (.incoming, _):
                text = String(localized: "📞 Chiamata rifiutata")
            case (.outgoing, .rejected):
                text = String(localized: "📞 Chiamata rifiutata")
            case (.outgoing, .busy):
                text = String(localized: "📞 Occupato")
            case (.outgoing, .noAnswer):
                text = String(localized: "📞 Nessuna risposta")
            case (.outgoing, .hungUp):
                text = String(localized: "📞 Chiamata annullata")
            case (.outgoing, _):
                text = String(localized: "📞 Chiamata non riuscita")
            }
        }
        let message = MessageRecord(msgID: CryptoEngine.randomMessageID(),
                                    conversationID: call.conversation.id,
                                    senderNodeID: call.direction == .outgoing ? identity.nodeID : call.peer.nodeID,
                                    senderNickname: call.direction == .outgoing ? nickname : call.peer.nickname,
                                    contentType: .system,
                                    text: text,
                                    isOutgoing: call.direction == .outgoing,
                                    // Voce solo locale: niente conferme di lettura verso il compagno.
                                    deliveryStatus: .read)
        _ = try? await database.messages.insert(message)
    }

    private func postIncomingCallNotification(callID: String, from peer: PeerRecord, conversation: ConversationRecord) {
        let content = UNMutableNotificationContent()
        content.title = peer.nickname
        content.body = String(localized: "📞 Chiamata in arrivo su AeroChat")
        content.sound = .default
        content.userInfo = ["conversation_id": conversation.id, "call_id": callID]
        let request = UNNotificationRequest(identifier: callID, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
