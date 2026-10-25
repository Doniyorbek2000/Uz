import Foundation

struct Suggestions: Equatable {
    var typed: String
    var candidates: [String]
    var typedIsKnown: Bool

    static let none = Suggestions(typed: "", candidates: [], typedIsKnown: true)
}

/// Completion, correction and next-word prediction over a bundled dictionary plus learned words.
final class SuggestionEngine {

    private enum Correction {
        case full, prefix, double

        var penalty: Double {
            switch self {
            case .full: return 55
            case .prefix: return 72
            case .double: return 105
            }
        }
    }

    private static let exactBonus = 40.0
    private static let completionBonus = 30.0
    private static let learnedBase = 150.0
    private static let learnedThreshold = 2
    private static let strongScore = 110.0
    private static let autocorrectMinScore = 120.0
    private static let maxKeyLength = 48

    private let dictionary: WordDictionary
    private let user: UserDictionary
    private let normalizer: KeyNormalizer
    private var rowA = [Int](repeating: 0, count: maxKeyLength + 1)
    private var rowB = [Int](repeating: 0, count: maxKeyLength + 1)
    private var rowC = [Int](repeating: 0, count: maxKeyLength + 1)

    init(dictionary: WordDictionary, user: UserDictionary, normalizer: KeyNormalizer = .standard) {
        self.dictionary = dictionary
        self.user = user
        self.normalizer = normalizer
    }

    func isKnown(_ word: String) -> Bool {
        let key = normalizer.normalize(word)
        return dictionary.index(of: Array(key.utf16)) != nil || user.count(key) >= Self.learnedThreshold
    }

    func suggest(_ typed: String, previousWord: String?, limit: Int = 3) -> Suggestions {
        if typed.isEmpty {
            return Suggestions(typed: "", candidates: predictNext(after: previousWord, limit: limit), typedIsKnown: true)
        }
        let typedKeyString = normalizer.normalize(typed)
        let typedKey = Array(typedKeyString.utf16)
        let previousKey = previousWord.map { normalizer.normalize($0) }
        var candidates: [String: (display: String, score: Double)] = [:]

        func offer(_ key: String, _ display: String, _ score: Double) {
            if let existing = candidates[key], existing.score >= score { return }
            candidates[key] = (display, score)
        }

        func contextBoost(_ key: String) -> Double {
            guard let previousKey else { return 0 }
            let count = user.bigramCount(previous: previousKey, key: key)
            return count == 0 ? 0 : 30 * log(1 + Double(count))
        }

        for index in dictionary.prefixRange(typedKey) {
            let keyUnits = dictionary.key(at: index)
            let key = String(decoding: keyUnits, as: UTF16.self)
            let lengthPenalty = Double(min(keyUnits.count - typedKey.count, 8)) * 1.5
            let exact = keyUnits == typedKey ? Self.exactBonus : 0
            offer(key, dictionary.word(at: index), Double(dictionary.frequency(at: index)) + Self.completionBonus + exact - lengthPenalty + user.boost(key) + contextBoost(key))
        }

        user.forEachPrefix(typedKeyString) { key, entry in
            if entry.count < Self.learnedThreshold && key != typedKeyString { return }
            if dictionary.index(of: Array(key.utf16)) != nil { return }
            let exact = key == typedKeyString ? Self.exactBonus : 0
            offer(key, entry.display, Self.learnedBase + Self.completionBonus + user.boost(key) + exact + contextBoost(key))
        }

        let bestScore = candidates.values.map { $0.score }.max() ?? 0
        if typedKey.count >= 2 && (candidates.count < limit || bestScore < Self.strongScore) {
            collectCorrections(typedKey) { key, display, frequency, kind in
                offer(key, display, Double(frequency) - kind.penalty + user.boost(key) + contextBoost(key))
            }
        }

        var ordered: [String] = []
        for entry in candidates.values.sorted(by: { $0.score > $1.score }).prefix(limit + 1) {
            let cased = TextRules.matchCase(entry.display, typed: typed)
            if !ordered.contains(cased) { ordered.append(cased) }
        }
        let known = isKnown(typed)
        let filtered = ordered.filter { $0 != typed || known }
        return Suggestions(typed: typed, candidates: Array(filtered.prefix(limit)), typedIsKnown: known)
    }

    func autocorrection(_ typed: String, previousWord: String?) -> String? {
        guard typed.count >= 3, !isKnown(typed), typed.allSatisfy({ $0.isLetter || TextRules.isApostrophe($0) }) else { return nil }
        let typedKey = Array(normalizer.normalize(typed).utf16)
        // Never "correct" the beginning of a real word.
        if !dictionary.prefixRange(typedKey).isEmpty { return nil }
        let previousKey = previousWord.map { normalizer.normalize($0) }
        var best: String?
        var bestScore = -Double.infinity
        collectCorrections(typedKey) { key, display, frequency, kind in
            guard kind == .full else { return }
            var score = Double(frequency) + user.boost(key)
            if let previousKey { score += 30 * log(1 + Double(user.bigramCount(previous: previousKey, key: key))) }
            if score > bestScore {
                bestScore = score
                best = display
            }
        }
        guard let candidate = best, bestScore >= Self.autocorrectMinScore else { return nil }
        return TextRules.matchCase(candidate, typed: typed)
    }

    func predictNext(after previousWord: String?, limit: Int = 3) -> [String] {
        guard let previousWord, !previousWord.isEmpty else { return [] }
        return user.nextWords(after: previousWord, limit: limit)
    }

    private func collectCorrections(_ typed: WordKey, _ sink: (String, String, Int, Correction) -> Void) {
        let typedLength = typed.count
        guard typedLength <= Self.maxKeyLength, typedLength > 0 else { return }
        let maxDistance = typedLength >= 7 ? 2 : 1
        let allowShorter = typedLength >= 5
        let first = typed[0]
        let second = typedLength > 1 ? typed[1] : first
        for index in 0..<dictionary.count {
            let key = dictionary.key(at: index)
            let keyLength = key.count
            if keyLength > Self.maxKeyLength || keyLength < 2 { continue }
            if keyLength < typedLength && (!allowShorter || typedLength - keyLength > maxDistance) { continue }
            let firstMatches = key[0] == first || (key[0] == second && key[1] == first)
            if !firstMatches && !(allowShorter && abs(keyLength - typedLength) <= 1) { continue }
            var kind: Correction?
            if abs(keyLength - typedLength) <= maxDistance {
                let d = distance(typed, key, keyLength, maxDistance)
                if d == 0 { continue }
                if d == 1 { kind = .full } else if d == 2 && maxDistance >= 2 { kind = .double }
            }
            if kind == nil && keyLength > typedLength && typedLength >= 3 && firstMatches && distance(typed, key, typedLength, 1) == 1 {
                kind = .prefix
            }
            if let kind {
                sink(String(decoding: key, as: UTF16.self), dictionary.word(at: index), dictionary.frequency(at: index), kind)
            }
        }
        user.forEachWord { key, entry in
            guard entry.count >= Self.learnedThreshold else { return }
            let units = Array(key.utf16)
            guard units.count <= Self.maxKeyLength, abs(units.count - typedLength) <= 1 else { return }
            if Self.distanceUpToOne(typed, units, units.count) == 1 {
                sink(key, entry.display, Int(Self.learnedBase), .full)
            }
        }
    }

    private func distance(_ a: WordKey, _ b: WordKey, _ bLength: Int, _ maxDistance: Int) -> Int {
        if maxDistance == 1 { return Self.distanceUpToOne(a, b, bLength) }
        return Self.boundedDistance(a, b, maxDistance: maxDistance, bLength: bLength, rowA: &rowA, rowB: &rowB, rowC: &rowC)
    }

    /// 0 if equal, 1 for one edit or an adjacent swap, 2 otherwise; compares with the first bLength units of b.
    static func distanceUpToOne(_ a: WordKey, _ b: WordKey, _ bLength: Int) -> Int {
        let n = a.count
        let m = bLength
        if abs(n - m) > 1 { return 2 }
        var i = 0
        while i < n && i < m && a[i] == b[i] { i += 1 }
        if i == n && i == m { return 0 }
        func tailEquals(_ aFrom: Int, _ bFrom: Int) -> Bool {
            if n - aFrom != m - bFrom { return false }
            var k = 0
            while aFrom + k < n {
                if a[aFrom + k] != b[bFrom + k] { return false }
                k += 1
            }
            return true
        }
        if n == m {
            if tailEquals(i + 1, i + 1) { return 1 }
            if i + 1 < n && a[i] == b[i + 1] && a[i + 1] == b[i] && tailEquals(i + 2, i + 2) { return 1 }
            return 2
        }
        if n == m + 1 { return tailEquals(i + 1, i) ? 1 : 2 }
        return tailEquals(i, i + 1) ? 1 : 2
    }

    /// Optimal-string-alignment distance, returning maxDistance + 1 once it is exceeded.
    static func boundedDistance(_ a: WordKey, _ b: WordKey, maxDistance: Int, bLength: Int,
                                rowA: inout [Int], rowB: inout [Int], rowC: inout [Int]) -> Int {
        let n = a.count
        let m = bLength
        if abs(n - m) > maxDistance { return maxDistance + 1 }
        if rowA.count < m + 1 {
            rowA = [Int](repeating: 0, count: m + 1)
            rowB = rowA
            rowC = rowA
        }
        // Rows rotate through the three buffers: twoBack, previous, current.
        var twoBack = rowA
        var previous = rowB
        var current = rowC
        for j in 0...m { previous[j] = j }
        if n == 0 { return min(m, maxDistance + 1) }
        for i in 1...n {
            current[0] = i
            var rowMin = i
            let ca = a[i - 1]
            if m > 0 {
                for j in 1...m {
                    let cb = b[j - 1]
                    var value = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + (ca == cb ? 0 : 1))
                    if i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb {
                        value = min(value, twoBack[j - 2] + 1)
                    }
                    current[j] = value
                    if value < rowMin { rowMin = value }
                }
            }
            if rowMin > maxDistance { return maxDistance + 1 }
            let recycled = twoBack
            twoBack = previous
            previous = current
            current = recycled
        }
        return min(previous[m], maxDistance + 1)
    }

    static func boundedDistance(_ a: String, _ b: String, maxDistance: Int) -> Int {
        let au = Array(a.utf16)
        let bu = Array(b.utf16)
        var r1 = [Int](repeating: 0, count: bu.count + 1)
        var r2 = r1
        var r3 = r1
        return boundedDistance(au, bu, maxDistance: maxDistance, bLength: bu.count, rowA: &r1, rowB: &r2, rowC: &r3)
    }
}
