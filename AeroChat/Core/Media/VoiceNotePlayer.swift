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

    init() {
        engine.attach(player)
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
        player.stop()
        player.scheduleBuffer(buffer, at: nil, options: []) { [weak self] in
            Task { @MainActor [weak self] in
                self?.finishPlayback()
            }
        }
        player.play()
        isPlaying = true
        progress = 0
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
        player.pause()
        isPlaying = false
        progressTask?.cancel()
    }

    func stop() {
        player.stop()
        isPlaying = false
        progress = 0
        progressTask?.cancel()
        progressTask = nil
    }

    private func finishPlayback() {
        isPlaying = false
        progress = 1
        progressTask?.cancel()
        progressTask = nil
    }
}
