import Foundation

typealias WordKey = [UInt16]

extension Array where Element == UInt16 {
    func hasKeyPrefix(_ prefix: WordKey) -> Bool {
        guard prefix.count <= count else { return false }
        for i in 0..<prefix.count where self[i] != prefix[i] { return false }
        return true
    }

    func keyLess(_ other: WordKey) -> Bool {
        let n = Swift.min(count, other.count)
        for i in 0..<n where self[i] != other[i] { return self[i] < other[i] }
        return count < other.count
    }
}

/// Immutable word list sorted by normalised UTF-16 key, supporting prefix ranges and exact lookups.
final class WordDictionary {
    private let words: [String]
    private let keys: [WordKey]
    private let frequencies: [Int]

    static let empty = WordDictionary(words: [], keys: [], frequencies: [])

    private init(words: [String], keys: [WordKey], frequencies: [Int]) {
        self.words = words
        self.keys = keys
        self.frequencies = frequencies
    }

    var count: Int { words.count }

    func word(at index: Int) -> String { words[index] }
    func key(at index: Int) -> WordKey { keys[index] }
    func frequency(at index: Int) -> Int { frequencies[index] }

    func index(of key: WordKey) -> Int? {
        let i = lowerBound(key)
        return i < keys.count && keys[i] == key ? i : nil
    }

    func prefixRange(_ prefix: WordKey) -> Range<Int> {
        if prefix.isEmpty { return 0..<count }
        let start = lowerBound(prefix)
        var low = start
        var high = keys.count
        while low < high {
            let mid = (low + high) / 2
            if keys[mid].hasKeyPrefix(prefix) { low = mid + 1 } else { high = mid }
        }
        return start..<low
    }

    private func lowerBound(_ key: WordKey) -> Int {
        var low = 0
        var high = keys.count
        while low < high {
            let mid = (low + high) / 2
            if keys[mid].keyLess(key) { low = mid + 1 } else { high = mid }
        }
        return low
    }

    /// Parses `word<TAB>frequency` lines.
    static func load(text: String, normalizer: KeyNormalizer = .standard) -> WordDictionary {
        struct Row {
            let word: String
            let key: WordKey
            let frequency: Int
        }
        var rows: [Row] = []
        rows.reserveCapacity(32_000)
        text.enumerateLines { line, _ in
            guard !line.isEmpty else { return }
            let parts = line.split(separator: "\t", maxSplits: 1)
            let word = String(parts[0]).trimmingCharacters(in: .whitespaces)
            guard !word.isEmpty else { return }
            let frequency = parts.count > 1 ? Int(parts[1].trimmingCharacters(in: .whitespaces)) ?? 1 : 1
            rows.append(Row(word: word, key: Array(normalizer.normalize(word).utf16), frequency: frequency))
        }
        rows.sort { a, b in
            if a.key == b.key { return a.frequency > b.frequency }
            return a.key.keyLess(b.key)
        }
        var words: [String] = []
        var keys: [WordKey] = []
        var frequencies: [Int] = []
        words.reserveCapacity(rows.count)
        keys.reserveCapacity(rows.count)
        frequencies.reserveCapacity(rows.count)
        for row in rows where keys.last != row.key {
            words.append(row.word)
            keys.append(row.key)
            frequencies.append(row.frequency)
        }
        return WordDictionary(words: words, keys: keys, frequencies: frequencies)
    }

    static func load(url: URL, normalizer: KeyNormalizer = .standard) -> WordDictionary {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return .empty }
        return load(text: text, normalizer: normalizer)
    }
}
