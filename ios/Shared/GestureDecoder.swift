import CoreGraphics
import Foundation

/// Decodes a swipe across letter keys into dictionary words by comparing the finger path with the
/// ideal path through each candidate's key centres (location-sensitive shape matching).
final class GestureDecoder {

    struct Candidate: Equatable {
        let word: String
        let score: Double
    }

    private static let samples = 32
    private static let startRadius: CGFloat = 1.05
    private static let endRadius: CGFloat = 1.15
    private static let maxDistance = 1.1
    private static let distanceWeight = 10.0
    private static let frequencyDivisor = 55.0

    private let dictionary: WordDictionary
    private let normalizer: KeyNormalizer
    private let outputs: [(String, CGPoint)]
    private let keyWidth: CGFloat
    private let user: UserDictionary?

    /// - Parameter keyCenters: lower-case key output (e.g. "a", "oʻ") → key centre.
    init(dictionary: WordDictionary, normalizer: KeyNormalizer, keyCenters: [String: CGPoint], keyWidth: CGFloat, user: UserDictionary? = nil) {
        self.dictionary = dictionary
        self.normalizer = normalizer
        self.outputs = keyCenters.map { ($0.key, $0.value) }.sorted { $0.0.count > $1.0.count }
        self.keyWidth = keyWidth
        self.user = user
    }

    func decode(_ path: [CGPoint], limit: Int = 4) -> [Candidate] {
        guard path.count >= 2, !outputs.isEmpty, let first = path.first, let last = path.last else { return [] }
        let sampledPath = Self.resample(path, count: Self.samples)
        let pathLength = Self.length(path)
        let startKeys = nearestKeys(first, radius: Self.startRadius)
        let endKeys = Set(nearestKeys(last, radius: Self.endRadius))
        guard !startKeys.isEmpty, !endKeys.isEmpty else { return [] }

        var best: [Candidate] = []
        var seen = Set<String>()
        for startKey in startKeys {
            let range = dictionary.prefixRange(Array(normalizer.normalize(startKey).utf16))
            for index in range {
                let display = dictionary.word(at: index)
                let lower = display.lowercased()
                guard seen.insert(lower).inserted, let keys = keySequence(lower), keys.count >= 2,
                      keys.first == startKey, let lastKey = keys.last, endKeys.contains(lastKey) else { continue }
                let template = keys.compactMap(center(of:))
                let templateLength = Self.length(template)
                if templateLength > 0 && (pathLength < templateLength * 0.45 || pathLength > templateLength * 3.2 + keyWidth) { continue }
                let distance = Self.meanDistance(sampledPath, Self.resample(template, count: Self.samples)) / Double(keyWidth)
                if distance > Self.maxDistance { continue }
                let learned = user?.count(normalizer.normalize(display)) ?? 0
                let score = -distance * Self.distanceWeight + Double(dictionary.frequency(at: index)) / Self.frequencyDivisor
                    + (learned > 0 ? log(1 + Double(learned)) : 0)
                insert(Candidate(word: display, score: score), into: &best, limit: limit)
            }
        }
        return best
    }

    private func insert(_ candidate: Candidate, into best: inout [Candidate], limit: Int) {
        let position = best.firstIndex { $0.score < candidate.score } ?? best.count
        guard position < limit else { return }
        best.insert(candidate, at: position)
        if best.count > limit { best.removeLast() }
    }

    /// Splits a word into key outputs (longest match first); nil when a letter has no key.
    func keySequence(_ word: String) -> [String]? {
        var keys: [String] = []
        var rest = Substring(word)
        while !rest.isEmpty {
            guard let match = outputs.first(where: { rest.hasPrefix($0.0) }) else { return nil }
            if keys.last != match.0 { keys.append(match.0) }
            rest = rest.dropFirst(match.0.count)
        }
        return keys
    }

    private func center(of key: String) -> CGPoint? {
        outputs.first { $0.0 == key }?.1
    }

    private func nearestKeys(_ point: CGPoint, radius: CGFloat) -> [String] {
        outputs
            .map { ($0.0, hypot($0.1.x - point.x, $0.1.y - point.y)) }
            .filter { $0.1 <= keyWidth * radius }
            .sorted { $0.1 < $1.1 }
            .prefix(3)
            .map { $0.0 }
    }

    static func length(_ points: [CGPoint]) -> CGFloat {
        guard points.count > 1 else { return 0 }
        var total: CGFloat = 0
        for i in 1..<points.count { total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y) }
        return total
    }

    /// Resamples a polyline into `count` points evenly spaced along its length.
    static func resample(_ points: [CGPoint], count: Int) -> [CGPoint] {
        guard let first = points.first else { return [] }
        let total = length(points)
        if points.count == 1 || total == 0 { return Array(repeating: first, count: count) }
        let step = total / CGFloat(count - 1)
        var result = [first]
        var accumulated: CGFloat = 0
        var previous = first
        var i = 1
        while i < points.count && result.count < count {
            let current = points[i]
            let segment = hypot(current.x - previous.x, current.y - previous.y)
            if segment > 0 && accumulated + segment >= step {
                let t = (step - accumulated) / segment
                let point = CGPoint(x: previous.x + t * (current.x - previous.x), y: previous.y + t * (current.y - previous.y))
                result.append(point)
                previous = point
                accumulated = 0
            } else {
                accumulated += segment
                previous = current
                i += 1
            }
        }
        while result.count < count { result.append(points[points.count - 1]) }
        return result
    }

    static func meanDistance(_ a: [CGPoint], _ b: [CGPoint]) -> Double {
        let n = min(a.count, b.count)
        guard n > 0 else { return .greatestFiniteMagnitude }
        var total = 0.0
        for i in 0..<n { total += Double(hypot(a[i].x - b[i].x, a[i].y - b[i].y)) }
        return total / Double(n)
    }
}
