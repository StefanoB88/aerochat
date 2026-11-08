import Foundation
import AVFoundation

/// Audio full-duplex della chiamata: microfono -> 16 kHz -> Opus a frame da 20 ms (a gruppi di
/// `MeshProtocol.callFramesPerPacket`) e, in ricezione, Opus -> PCM su un `AVAudioPlayerNode`
/// con un piccolo jitter buffer. La cancellazione dell'eco e' quella di sistema (voice processing).
///
/// Tutto lo stato del codec vive sulla `codecQueue` seriale; il tap del microfono e le completion
/// del player girano su thread audio e vi saltano con `async`.
final class CallAudioEngine: @unchecked Sendable {

    typealias PacketHandler = @Sendable ([Data]) -> Void

    enum Failure: Error, Equatable {
        case formatUnavailable
        case microphoneUnavailable
    }

    /// Pacchetti in coda nel player prima di iniziare (o riprendere) la riproduzione: ~120 ms.
    static let prebufferPackets = 2
    /// Oltre questa coda (~480 ms) i pacchetti in arrivo vengono scartati per non accumulare ritardo.
    static let maxQueuedPackets = 8

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let codecQueue = DispatchQueue(label: "AeroMesh.CallCodec", qos: .userInteractive)

    // Stato confinato alla codecQueue.
    private var encoder: OpusStreamEncoder?
    private var decoder: OpusStreamDecoder?
    private var pendingSamples: [Float] = []
    private var pendingFrames: [Data] = []
    private var lastReceivedSequence: UInt32 = 0
    private var queuedPackets = 0
    private var isPrebuffering = true
    private var onPacket: PacketHandler?
    private var isMuted = false
    /// Incrementata a ogni riavvio dell'engine: le completion dei buffer svuotati da `player.stop()`
    /// arrivano in ritardo e non devono scalare il contatore della nuova coda.
    private var playbackGeneration = 0

    /// Solo main thread (start/stop).
    private var isRunning = false
    /// Osservatori di cambio configurazione (cuffie, AirPods, HFP) e interruzioni. Solo main thread.
    private var observers: [any NSObjectProtocol] = []

    init() {}

    // MARK: - Ciclo di vita (main thread)

    func start(onPacket: @escaping PacketHandler) throws {
        guard !isRunning else { return }
        guard let pcm = OpusCodec.pcmFormat,
              let encoder = OpusStreamEncoder(),
              let decoder = OpusStreamDecoder() else { throw Failure.formatUnavailable }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetoothHFP, .duckOthers])
        try? session.setPreferredIOBufferDuration(0.02)
        try session.setActive(true)

        let input = engine.inputNode
        // Cancellazione dell'eco e riduzione del rumore di sistema (fondamentale in vivavoce).
        try? input.setVoiceProcessingEnabled(true)
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let capture = AVAudioConverter(from: inputFormat, to: pcm) else {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            throw Failure.microphoneUnavailable
        }

        codecQueue.sync {
            self.encoder = encoder
            self.decoder = decoder
            self.onPacket = onPacket
            self.pendingSamples.removeAll()
            self.pendingFrames.removeAll()
            self.lastReceivedSequence = 0
            self.queuedPackets = 0
            self.isPrebuffering = true
            self.isMuted = false
        }

        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: pcm)
        input.removeTap(onBus: 0)
        Self.installTap(on: input, format: inputFormat, converter: capture, target: pcm, engine: self)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            // Il player resterebbe attaccato e la chiamata successiva lo riattaccherebbe a un engine
            // che lo contiene gia'.
            engine.detach(player)
            try? input.setVoiceProcessingEnabled(false)
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            throw error
        }
        isRunning = true
        observeAudioRouteChanges()
    }

    /// Cambio di route (AirPods collegati/scollegati, passaggio a HFP) o interruzione: iOS ferma
    /// l'engine e il formato del microfono puo' cambiare. Senza riavvio la chiamata restava muta
    /// fino al timeout, e `player.play()` su un engine fermo solleva un'eccezione ObjC (crash).
    private func observeAudioRouteChanges() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            self?.restartAfterAudioChange()
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard raw == AVAudioSession.InterruptionType.ended.rawValue else { return }
            self?.restartAfterAudioChange()
        })
    }

    /// Solo main thread: rimonta il tap con il nuovo formato del microfono e riavvia l'engine.
    private func restartAfterAudioChange() {
        guard isRunning, !engine.isRunning, let pcm = OpusCodec.pcmFormat else { return }
        codecQueue.sync {
            self.playbackGeneration += 1
            self.queuedPackets = 0
            self.isPrebuffering = true
        }
        // Svuota i buffer rimasti in coda (audio ormai vecchio).
        player.stop()
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let capture = AVAudioConverter(from: inputFormat, to: pcm) else { return }
        Self.installTap(on: input, format: inputFormat, converter: capture, target: pcm, engine: self)
        try? AVAudioSession.sharedInstance().setActive(true)
        engine.prepare()
        try? engine.start()
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        // Prima si spegne il codec (nessun nuovo play/pause dalla codecQueue), poi l'audio.
        codecQueue.sync {
            self.onPacket = nil
            self.encoder = nil
            self.decoder = nil
            self.pendingSamples.removeAll()
            self.pendingFrames.removeAll()
        }
        engine.inputNode.removeTap(onBus: 0)
        player.stop()
        engine.stop()
        try? engine.inputNode.setVoiceProcessingEnabled(false)
        engine.detach(player)
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.none)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func setMuted(_ muted: Bool) {
        codecQueue.async { self.isMuted = muted }
    }

    func setSpeaker(_ enabled: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(enabled ? .speaker : .none)
    }

    // MARK: - Cattura (thread audio -> codecQueue)

    /// Il blocco del tap gira sul thread audio: creato fuori da qualsiasi attore (vedi `VoiceNoteRecorder`).
    private static func installTap(on input: AVAudioInputNode, format: AVAudioFormat, converter: AVAudioConverter,
                                   target: AVAudioFormat, engine: CallAudioEngine) {
        input.installTap(onBus: 0, bufferSize: 1_024, format: format) { [weak engine] buffer, _ in
            guard let engine, let samples = Self.resample(buffer, converter: converter, target: target) else { return }
            engine.codecQueue.async { engine.encode(samples) }
        }
    }

    private static func resample(_ buffer: AVAudioPCMBuffer, converter: AVAudioConverter, target: AVAudioFormat) -> [Float]? {
        let ratio = target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 32
        guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return nil }
        var consumed = false
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            if consumed {
                status.pointee = .noDataNow
                return nil
            }
            consumed = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, output.frameLength > 0, let channel = output.floatChannelData?[0] else { return nil }
        return Array(UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
    }

    /// Solo sulla codecQueue.
    private func encode(_ samples: [Float]) {
        guard let encoder, let onPacket else { return }
        // In muto si inviano frame di silenzio: il compagno continua a sapere che il link e' vivo.
        pendingSamples.append(contentsOf: isMuted ? [Float](repeating: 0, count: samples.count) : samples)
        let frameSize = Int(OpusCodec.frameSamples)
        while pendingSamples.count >= frameSize {
            let frame = Array(pendingSamples.prefix(frameSize))
            pendingSamples.removeFirst(frameSize)
            if let packet = encoder.encode(frame) {
                pendingFrames.append(packet)
            }
            if pendingFrames.count >= MeshProtocol.callFramesPerPacket {
                onPacket(pendingFrames)
                pendingFrames.removeAll()
            }
        }
    }

    // MARK: - Riproduzione

    /// Audio ricevuto dal compagno (qualsiasi thread).
    func receive(sequence: UInt32, frames: [Data]) {
        codecQueue.async { self.play(sequence: sequence, frames: frames) }
    }

    /// Solo sulla codecQueue.
    private func play(sequence: UInt32, frames: [Data]) {
        guard let decoder, sequence > lastReceivedSequence else { return }
        lastReceivedSequence = sequence
        // Coda troppo lunga (raffica dopo un buco radio): meglio perdere 60 ms che restare indietro.
        guard queuedPackets < Self.maxQueuedPackets else { return }
        let samples = frames.prefix(MeshProtocol.callFramesPerPacket * 2).flatMap { decoder.decode($0) }
        guard !samples.isEmpty, let format = OpusCodec.pcmFormat,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)) else { return }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { source in
            if let base = source.baseAddress {
                buffer.floatChannelData?[0].update(from: base, count: samples.count)
            }
        }
        queuedPackets += 1
        let generation = playbackGeneration
        player.scheduleBuffer(buffer) { [weak self] in
            guard let self else { return }
            self.codecQueue.async { self.bufferFinished(generation: generation) }
        }
        // `play()` con l'engine fermo (cambio di route in corso) solleva un'eccezione ObjC: si riprende
        // al prossimo pacchetto dopo il riavvio.
        if isPrebuffering, queuedPackets >= Self.prebufferPackets, engine.isRunning {
            isPrebuffering = false
            player.play()
        }
    }

    /// Solo sulla codecQueue.
    private func bufferFinished(generation: Int) {
        guard generation == playbackGeneration else { return }
        queuedPackets = max(0, queuedPackets - 1)
        if queuedPackets == 0, decoder != nil {
            // Coda vuota: pausa e nuovo prebuffer, cosi' il jitter della radio non diventa audio a scatti.
            isPrebuffering = true
            player.pause()
        }
    }
}

/// Encoder Opus con stato (un frame da 20 ms alla volta).
final class OpusStreamEncoder {

    private let converter: AVAudioConverter
    private let pcm: AVAudioFormat
    private let opus: AVAudioFormat

    init?() {
        guard let pcm = OpusCodec.pcmFormat, let opus = OpusCodec.opusFormat,
              let converter = AVAudioConverter(from: pcm, to: opus) else { return nil }
        converter.bitRate = OpusCodec.bitRate
        converter.bitRateStrategy = AVAudioBitRateStrategy_Variable
        self.converter = converter
        self.pcm = pcm
        self.opus = opus
    }

    func encode(_ frame: [Float]) -> Data? {
        guard frame.count == Int(OpusCodec.frameSamples),
              let input = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: OpusCodec.frameSamples) else { return nil }
        input.frameLength = OpusCodec.frameSamples
        frame.withUnsafeBufferPointer { source in
            if let base = source.baseAddress {
                input.floatChannelData?[0].update(from: base, count: frame.count)
            }
        }
        let output = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: converter.maximumOutputPacketSize)
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return input
        }
        guard status != .error, output.packetCount > 0 else { return nil }
        let size = Int(output.packetDescriptions?[0].mDataByteSize ?? output.byteLength)
        guard size > 0 else { return nil }
        return Data(bytes: output.data, count: size)
    }
}

/// Decoder Opus con stato (un pacchetto alla volta, nell'ordine di arrivo).
final class OpusStreamDecoder {

    private let converter: AVAudioConverter
    private let pcm: AVAudioFormat
    private let opus: AVAudioFormat

    init?() {
        guard let pcm = OpusCodec.pcmFormat, let opus = OpusCodec.opusFormat,
              let converter = AVAudioConverter(from: opus, to: pcm) else { return nil }
        self.converter = converter
        self.pcm = pcm
        self.opus = opus
    }

    func decode(_ packet: Data) -> [Float] {
        guard !packet.isEmpty, packet.count <= 1_500,
              let output = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: OpusCodec.frameSamples * 6) else { return [] }
        let compressed = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: packet.count)
        packet.withUnsafeBytes { bytes in
            if let base = bytes.baseAddress {
                compressed.data.copyMemory(from: base, byteCount: packet.count)
            }
        }
        compressed.byteLength = UInt32(packet.count)
        compressed.packetCount = 1
        compressed.packetDescriptions?.pointee = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0,
                                                                              mDataByteSize: UInt32(packet.count))
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return compressed
        }
        guard status != .error, output.frameLength > 0, let channel = output.floatChannelData?[0] else { return [] }
        return Array(UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
    }
}
