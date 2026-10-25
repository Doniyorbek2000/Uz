import Foundation

/// Words and word pairs learned from what the user types, kept per language.
final class UserDictionary {

    final class Entry {
        var display: String
        var count: Int
        var lastUsed: Double

        init(display: String, count: Int, lastUsed: Double) {
            self.display = display
            self.count = count
            self.lastUsed = lastUsed
        }
    }

    private struct Stored: Codable {
        struct Word: Codable {
            let display: String
            let count: Int
            let lastUsed: Double
        }

        var version: Int
        var words: [Word]
        var bigrams: [String: [String: Int]]
    }

    private let normalizer: KeyNormalizer
    private var words: [String: Entry] = [:]
    private var bigrams: [String: [String: Int]] = [:]
    private var bigramCount = 0
    private(set) var isDirty = false

    private static let maxWords = 6_000
    private static let maxBigrams = 20_000
    private static let maxCount = 10_000
    private static let maxWordLength = 40

    init(normalizer: KeyNormalizer = .standard) {
        self.normalizer = normalizer
    }

    var wordCount: Int { words.count }

    func count(_ key: String) -> Int { words[key]?.count ?? 0 }

    func bigramCount(previous: String, key: String) -> Int { bigrams[previous]?[key] ?? 0 }

    func learn(previous: String?, word: String, now: Double = Date().timeIntervalSince1970) {
        guard word.count >= 2, word.count <= Self.maxWordLength, !word.contains(where: { $0.isNumber }) else { return }
        let key = normalizer.normalize(word)
        if let entry = words[key] {
            if entry.display != word, entry.display.first?.isUppercase != word.first?.isUppercase {
                entry.display = word.lowercased()
            } else {
                entry.display = word
            }
            entry.count = min(entry.count + 1, Self.maxCount)
            entry.lastUsed = now
        } else {
            words[key] = Entry(display: word, count: 1, lastUsed: now)
        }
        if let previous {
            let previousKey = normalizer.normalize(previous)
            var followers = bigrams[previousKey] ?? [:]
            if followers[key] == nil { bigramCount += 1 }
            followers[key] = min((followers[key] ?? 0) + 1, Self.maxCount)
            bigrams[previousKey] = followers
        }
        isDirty = true
        prune()
    }

    func clear() {
        words.removeAll()
        bigrams.removeAll()
        bigramCount = 0
        isDirty = true
    }

    func forEachPrefix(_ prefix: String, _ body: (String, Entry) -> Void) {
        for (key, entry) in words where key.hasPrefix(prefix) { body(key, entry) }
    }

    func forEachWord(_ body: (String, Entry) -> Void) {
        for (key, entry) in words { body(key, entry) }
    }

    func nextWords(after previous: String, limit: Int) -> [String] {
        guard let followers = bigrams[normalizer.normalize(previous)] else { return [] }
        return followers
            .sorted { a, b in
                if a.value != b.value { return a.value > b.value }
                return (words[a.key]?.lastUsed ?? 0) > (words[b.key]?.lastUsed ?? 0)
            }
            .prefix(limit)
            .compactMap { words[$0.key]?.display }
    }

    func boost(_ key: String) -> Double {
        let c = count(key)
        return c == 0 ? 0 : 18 * log(1 + Double(c))
    }

    func serialize() throws -> Data {
        let stored = Stored(
            version: 1,
            words: words.values.map { Stored.Word(display: $0.display, count: $0.count, lastUsed: $0.lastUsed) },
            bigrams: bigrams
        )
        let data = try JSONEncoder().encode(stored)
        isDirty = false
        return data
    }

    func restore(_ data: Data) throws {
        let stored = try JSONDecoder().decode(Stored.self, from: data)
        words.removeAll()
        for word in stored.words {
            words[normalizer.normalize(word.display)] = Entry(display: word.display, count: word.count, lastUsed: word.lastUsed)
        }
        bigrams = stored.bigrams
        bigramCount = bigrams.values.reduce(0) { $0 + $1.count }
        isDirty = false
    }

    private func prune() {
        if words.count > Self.maxWords {
            let victims = words
                .sorted { a, b in a.value.count != b.value.count ? a.value.count < b.value.count : a.value.lastUsed < b.value.lastUsed }
                .prefix(words.count - Self.maxWords * 9 / 10)
                .map(\.key)
            victims.forEach { words.removeValue(forKey: $0) }
        }
        if bigramCount > Self.maxBigrams {
            var all: [(String, String, Int)] = []
            for (previous, followers) in bigrams {
                for (key, value) in followers { all.append((previous, key, value)) }
            }
            all.sort { $0.2 < $1.2 }
            for (previous, key, _) in all.prefix(bigramCount - Self.maxBigrams * 9 / 10) {
                bigrams[previous]?.removeValue(forKey: key)
                if bigrams[previous]?.isEmpty == true { bigrams.removeValue(forKey: previous) }
            }
            bigramCount = bigrams.values.reduce(0) { $0 + $1.count }
        }
    }
}
