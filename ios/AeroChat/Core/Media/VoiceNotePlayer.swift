import Foundation
import AVFoundation
import Observation

/// Riproduce note vocali Ogg Opus: demux -> decodifica Opus -> `AVAudioPlayerNode`.
@MainActor
@Observable
final class VoiceNotePlayer {

    enum Failure: Error, Equatable {
        case emptyAudio
        case formatUnavailable
    }

    private(set) var isPlaying = false
    private(set) var progress: Double = 0
    private(set) var duration: TimeInterval = 0
    private(set) var currentMediaPath: String?

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var buffer: AVAudioPCMBuffer?
    private var progressTask: Task<Void, Never>?
    /// Ogni nuova riproduzione incrementa la generazione: la completion di un buffer fermato con
    /// `player.stop()` arriva in ritardo e non deve chiudere la riproduzione successiva.
    private var generation = 0
    private var isPaused = false

    /// Osservatore delle interruzioni audio (chiamata in arrivo, Siri, sveglia).
    nonisolated(unsafe) private var interruptionObserver: (any NSObjectProtocol)?

    init() {
        engine.attach(player)
        // Un'interruzione ferma l'engine senza completion del buffer: senza questo la bolla restava
        // in "riproduzione" con il timer di avanzamento a 10 Hz attivo finche' la chat restava aperta.
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard raw == AVAudioSession.InterruptionType.began.rawValue else { return }
            MainActor.assumeIsolated { self?.pause() }
        }
    }

    deinit {
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
    }

    /// Decodifica (fuori dal main thread) e prepara il buffer PCM.
    func load(oggOpus data: Data, mediaPath: String? = nil) async throws {
        stop()
        let samples = try await Task.detached(priority: .userInitiated) { () throws -> [Float] in
            let stream = try OggOpus.decode(data)
            return try OpusCodec.decode(packets: stream.packets)
        }.value
        guard !samples.isEmpty else { throw Failure.emptyAudio }
        guard let format = OpusCodec.pcmFormat,
              let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)) else {
            throw Failure.formatUnavailable
        }
        pcm.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { source in
            if let base = source.baseAddress {
                pcm.floatChannelData?[0].update(from: base, count: samples.count)
            }
        }
        buffer = pcm
        duration = Double(samples.count) / OpusCodec.sampleRate
        progress = 0
        currentMediaPath = mediaPath
    }

    func play() throws {
        guard let buffer else { throw Failure.emptyAudio }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .spokenAudio)
        try session.setActive(true)

        if engine.outputNode.inputFormat(forBus: 0).sampleRate == 0 || !engine.isRunning {
            engine.connect(player, to: engine.mainMixerNode, format: buffer.format)
            engine.prepare()
            try engine.start()
        }
        if isPaused {
            // Ripresa dal punto di pausa (prima ripartiva da zero con lo stato UI incoerente).
            isPaused = false
            player.play()
            isPlaying = true
            startProgressUpdates()
            return
        }
        generation += 1
        let current = generation
        player.stop()
        Self.schedule(buffer, on: player) { [weak self] in
            self?.finishPlayback(generation: current)
        }
        player.play()
        isPlaying = true
        progress = 0
        startProgressUpdates()
    }

    private func startProgressUpdates() {
        progressTask?.cancel()
        progressTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self, self.isPlaying else { break }
                if let nodeTime = self.player.lastRenderTime,
                   let playerTime = self.player.playerTime(forNodeTime: nodeTime),
                   self.duration > 0 {
                    self.progress = min(1, Double(playerTime.sampleTime) / playerTime.sampleRate / self.duration)
                }
            }
        }
    }

    func pause() {
        guard isPlaying else { return }
        player.pause()
        isPaused = true
        isPlaying = false
        progressTask?.cancel()
    }

    func stop() {
        generation += 1
        player.stop()
        isPaused = false
        isPlaying = false
        progress = 0
        progressTask?.cancel()
        progressTask = nil
        releaseAudio()
    }

    /// Ferma il render loop e restituisce l'audio alle altre app (musica, podcast).
    private func releaseAudio() {
        if engine.isRunning { engine.pause() }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// La completion di `scheduleBuffer` arriva su un thread audio: il blocco nasce fuori dal main actor
    /// (vedi `VoiceNoteRecorder.installTap`) e salta sul main actor solo per aggiornare lo stato.
    nonisolated private static func schedule(_ buffer: AVAudioPCMBuffer, on player: AVAudioPlayerNode,
                                             completion: @escaping @MainActor @Sendable () -> Void) {
        player.scheduleBuffer(buffer, at: nil, options: [], completionCallbackType: .dataPlayedBack) { _ in
            Task { @MainActor in completion() }
        }
    }

    private func finishPlayback(generation finished: Int) {
        guard finished == generation else { return }
        isPlaying = false
        isPaused = false
        progress = 1
        progressTask?.cancel()
        progressTask = nil
        releaseAudio()
    }
}
