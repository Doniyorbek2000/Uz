import AVFoundation

/// Plays the synthesised key-click packs from shared/sounds/ through a small pool of audio-engine players,
/// so fast typing overlaps sounds instead of cutting them off. Uses the ambient audio category: it mixes
/// with other audio and stays silent when the ring/silent switch is set to silent.
final class KeySoundPlayer {

    let packs: [SoundPack] = SoundPacks.load()
    /// Pack ids in display order, the system click first.
    var packIds: [String] { [SoundPacks.system] + packs.map(\.id) }

    private let engine = AVAudioEngine()
    private var players: [AVAudioPlayerNode] = []
    private var nextPlayer = 0
    private var pack: SoundPack?
    private var keyBuffers: [AVAudioPCMBuffer] = []
    private var deleteBuffer: AVAudioPCMBuffer?
    private var spaceBuffer: AVAudioPCMBuffer?
    private var enterBuffer: AVAudioPCMBuffer?
    private static let playerCount = 6

    deinit {
        engine.stop()
    }

    /// Plays the sound for a key of `type` at `volume` (0–1); `label` lets musical packs give every key its own note.
    /// Returns false when nothing was played (the system pack, an unknown pack or unavailable audio), so the caller
    /// can fall back to the system click.
    @discardableResult
    func play(packId: String, type: KeyType, label: String?, volume: Float) -> Bool {
        guard packId != SoundPacks.system, let pack = prepare(packId) else { return false }
        let buffer: AVAudioPCMBuffer?
        switch type {
        case .backspace: buffer = deleteBuffer
        case .space: buffer = spaceBuffer
        case .enter: buffer = enterBuffer
        default:
            let variant = SoundPacks.keyVariant(pack, label: label)
            buffer = variant < keyBuffers.count ? keyBuffers[variant] : keyBuffers.first
        }
        guard let buffer, !players.isEmpty, startEngine() else { return false }
        let player = players[nextPlayer]
        nextPlayer = (nextPlayer + 1) % players.count
        player.stop()
        player.volume = max(0, min(1, volume))
        player.scheduleBuffer(buffer, at: nil, options: [], completionHandler: nil)
        player.play()
        return true
    }

    /// Pauses the audio engine while the keyboard is hidden; the next sound restarts it.
    func suspend() {
        engine.pause()
    }

    /// Frees the loaded sounds, e.g. when key sounds are turned off or memory runs low.
    func release() {
        engine.stop()
        pack = nil
        keyBuffers = []
        deleteBuffer = nil
        spaceBuffer = nil
        enterBuffer = nil
    }

    private func prepare(_ packId: String) -> SoundPack? {
        if let pack, pack.id == packId { return pack }
        guard let selected = packs.first(where: { $0.id == packId }) else { return nil }
        func load(_ name: String) -> AVAudioPCMBuffer? {
            guard let url = SharedResources.url("sounds/\(selected.id)/\(name).wav"),
                  let file = try? AVAudioFile(forReading: url),
                  let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)),
                  (try? file.read(into: buffer)) != nil else { return nil }
            return buffer
        }
        let keys = (1...selected.keys).compactMap { load("key\($0)") }
        guard let format = keys.first?.format else { return nil }
        keyBuffers = keys
        deleteBuffer = load("delete")
        spaceBuffer = load("space")
        enterBuffer = load("enter")
        if players.isEmpty {
            for _ in 0..<Self.playerCount {
                let player = AVAudioPlayerNode()
                engine.attach(player)
                engine.connect(player, to: engine.mainMixerNode, format: format)
                players.append(player)
            }
        }
        pack = selected
        return selected
    }

    /// Also restarts the engine after a route change (headphones, Bluetooth), which stops it.
    private func startEngine() -> Bool {
        if engine.isRunning { return true }
        do {
            try AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default, options: [.mixWithOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            try engine.start()
            return true
        } catch {
            return false
        }
    }
}
