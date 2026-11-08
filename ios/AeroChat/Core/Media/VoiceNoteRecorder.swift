import Foundation
import AVFoundation
import Observation

/// Registratore note vocali: cattura mono dal microfono, ricampiona a 16 kHz, codifica Opus 12 kbps
/// VBR in container Ogg e produce i 32 campioni di forma d'onda per la bolla.
@MainActor
@Observable
final class VoiceNoteRecorder {

    struct Recording: Sendable, Equatable {
        let oggOpus: Data
        let waveform: Data
        let durationMs: Int
        let sampleCount: Int
    }

    enum Failure: Error, Equatable {
        case permissionDenied
        case alreadyRecording
        case notRecording
        case formatUnavailable
        case tooShort
    }

    static let maximumDuration: TimeInterval = 120
    static let minimumDuration: TimeInterval = 0.5

    private(set) var isRecording = false
    private(set) var level: Float = 0
    private(set) var elapsed: TimeInterval = 0

    private let engine = AVAudioEngine()
    private var sink: SampleSink?
    private var meterTask: Task<Void, Never>?
    private var startedAt: Date?
    /// Chiamato quando la registrazione si ferma da sola al limite dei 120 s (la nota va inviata, non persa).
    var onAutoStop: ((Recording) -> Void)?

    init() {}

    func requestPermission() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }

    func start() throws {
        guard !isRecording else { throw Failure.alreadyRecording }
        guard let target = OpusCodec.pcmFormat else { throw Failure.formatUnavailable }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)

        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, let converter = AVAudioConverter(from: inputFormat, to: target) else {
            throw Failure.formatUnavailable
        }
        let sink = SampleSink(converter: converter, targetFormat: target)
        self.sink = sink
        // Un tap rimasto da un avvio fallito farebbe sollevare un'eccezione ObjC (crash) a `installTap`.
        input.removeTap(onBus: 0)
        Self.installTap(on: input, format: inputFormat, sink: sink)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            // Microfono occupato (chiamata, Siri): pulizia completa, il prossimo tentativo deve poter ripartire.
            input.removeTap(onBus: 0)
            self.sink = nil
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            throw error
        }
        isRecording = true
        startedAt = Date()
        elapsed = 0
        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self else { break }
                self.level = sink.currentLevel()
                self.elapsed = Date().timeIntervalSince(self.startedAt ?? Date())
                if self.elapsed >= Self.maximumDuration {
                    // `stop` cancella questo stesso task: la codifica deve partire comunque.
                    let finish = Task { try? await self.stop() }
                    if let recording = await finish.value {
                        self.onAutoStop?(recording)
                    }
                    break
                }
            }
        }
    }

    /// Ferma la registrazione e restituisce il file Ogg Opus pronto per la mesh.
    /// La codifica Opus (fino a 120 s di audio, centinaia di ms) gira fuori dal main actor.
    func stop() async throws -> Recording {
        guard isRecording, let sink else { throw Failure.notRecording }
        meterTask?.cancel()
        meterTask = nil
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRecording = false
        level = 0
        self.sink = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)

        let samples = sink.drain()
        guard Double(samples.count) / OpusCodec.sampleRate >= Self.minimumDuration else { throw Failure.tooShort }
        return try await Task.detached(priority: .userInitiated) { try Self.makeRecording(samples: samples) }.value
    }

    func cancel() {
        meterTask?.cancel()
        meterTask = nil
        if isRecording {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        isRecording = false
        level = 0
        sink = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Il blocco del tap gira sul thread audio in tempo reale: va creato fuori dal main actor, altrimenti
    /// Swift 6 lo considera `@MainActor` e il controllo di isolamento a runtime fa crashare l'app.
    nonisolated private static func installTap(on input: AVAudioInputNode, format: AVAudioFormat, sink: SampleSink) {
        input.installTap(onBus: 0, bufferSize: 4_096, format: format) { buffer, _ in
            sink.append(buffer)
        }
    }

    /// Pipeline pura (testabile): PCM 16 kHz -> Opus -> Ogg + forma d'onda.
    nonisolated static func makeRecording(samples: [Float]) throws -> Recording {
        let packets = try OpusCodec.encode(samples: samples)
        let ogg = OggOpus.encode(packets: packets)
        let waveform = OpusCodec.waveform(samples: samples)
        let durationMs = Int((Double(samples.count) / OpusCodec.sampleRate * 1_000).rounded())
        return Recording(oggOpus: ogg, waveform: waveform, durationMs: durationMs, sampleCount: samples.count)
    }
}

/// Accumula i campioni ricampionati a 16 kHz. Usato dal thread audio (tap) e dal main actor (drain):
/// l'accesso e' serializzato dal lock.
final class SampleSink: @unchecked Sendable {

    private let lock = NSLock()
    private let converter: AVAudioConverter
    private let targetFormat: AVAudioFormat
    private var samples: [Float] = []
    private var lastLevel: Float = 0

    init(converter: AVAudioConverter, targetFormat: AVAudioFormat) {
        self.converter = converter
        self.targetFormat = targetFormat
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 32
        guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else { return }
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
        guard error == nil, output.frameLength > 0, let channel = output.floatChannelData?[0] else { return }
        let chunk = Array(UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
        var sum: Float = 0
        for value in chunk { sum += value * value }
        let rms = (sum / Float(max(1, chunk.count))).squareRoot()
        lock.withLock {
            samples.append(contentsOf: chunk)
            lastLevel = min(1, rms * 4)
        }
    }

    func currentLevel() -> Float {
        lock.withLock { lastLevel }
    }

    func drain() -> [Float] {
        lock.withLock {
            let all = samples
            samples.removeAll()
            return all
        }
    }
}
