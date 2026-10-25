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
    /// Letter-presence bitmask of every key, see `mask(of:)`.
    private let masks: [UInt64]
    /// Key lengths and first two units as plain values, so full scans rarely touch the key arrays.
    private let lengths: [Int]
    private let firstUnits: [UInt16]
    private let secondUnits: [UInt16]

    static let empty = WordDictionary(words: [], keys: [], frequencies: [])

    private init(words: [String], keys: [WordKey], frequencies: [Int]) {
        self.words = words
        self.keys = keys
        self.frequencies = frequencies
        masks = keys.map { Self.mask(of: $0) }
        lengths = keys.map { $0.count }
        firstUnits = keys.map { $0.first ?? 0 }
        secondUnits = keys.map { $0.count > 1 ? $0[1] : 0 }
    }

    var count: Int { words.count }

    func word(at index: Int) -> String { words[index] }
    func key(at index: Int) -> WordKey { keys[index] }
    func keyString(at index: Int) -> String { String(decoding: keys[index], as: UTF16.self) }
    func frequency(at index: Int) -> Int { frequencies[index] }
    func mask(at index: Int) -> UInt64 { masks[index] }
    func keyLength(at index: Int) -> Int { lengths[index] }
    func firstUnit(at index: Int) -> UInt16 { firstUnits[index] }
    func secondUnit(at index: Int) -> UInt16 { secondUnits[index] }

    /// One bit per letter present (letters hashed into 64 bits). An edit changes at most two bits, so two
    /// keys within edit distance d differ in at most 2·d bits — a cheap filter before computing distances.
    static func mask(of key: WordKey) -> UInt64 {
        var mask: UInt64 = 0
        for unit in key { mask |= 1 << UInt64(unit & 63) }
        return mask
    }

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
