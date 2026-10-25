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

    /// First line of files written by tools/build_dictionaries.py.
    static let sortedKeysHeader = "#sorted-keys"

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

    /// Parses `word<TAB>frequency[<TAB>key]` lines. Bundled files carry their normalised keys and are already
    /// sorted by key, so loading is a single pass over the bytes; any other input is normalised and sorted here.
    static func load(data: Data, normalizer: KeyNormalizer = .standard) -> WordDictionary {
        var words: [String] = []
        var keys: [WordKey] = []
        var frequencies: [Int] = []
        words.reserveCapacity(52_000)
        keys.reserveCapacity(52_000)
        frequencies.reserveCapacity(52_000)
        var sorted = true
        var keyed = false
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            let bytes = buffer.bindMemory(to: UInt8.self)
            let tab: UInt8 = 9, newline: UInt8 = 10, carriageReturn: UInt8 = 13
            var lineStart = 0
            while lineStart < bytes.count {
                var lineEnd = lineStart
                while lineEnd < bytes.count && bytes[lineEnd] != newline { lineEnd += 1 }
                var contentEnd = lineEnd
                if contentEnd > lineStart && bytes[contentEnd - 1] == carriageReturn { contentEnd -= 1 }
                defer { lineStart = lineEnd + 1 }
                guard contentEnd > lineStart else { continue }
                if bytes[lineStart] == 35 { // "#": header or comment
                    let header = String(decoding: UnsafeBufferPointer(rebasing: bytes[lineStart..<contentEnd]), as: UTF8.self)
                    if header.trimmingCharacters(in: .whitespaces) == Self.sortedKeysHeader { keyed = true }
                    continue
                }
                var firstTab = lineStart
                while firstTab < contentEnd && bytes[firstTab] != tab { firstTab += 1 }
                let word = String(decoding: UnsafeBufferPointer(rebasing: bytes[lineStart..<firstTab]), as: UTF8.self)
                    .trimmingCharacters(in: .whitespaces)
                guard !word.isEmpty else { continue }
                var frequency = 0
                var position = firstTab + 1
                var sawDigit = false
                while position < contentEnd && bytes[position] != tab {
                    let byte = bytes[position]
                    if byte >= 48 && byte <= 57 {
                        frequency = frequency * 10 + Int(byte - 48)
                        sawDigit = true
                    }
                    position += 1
                }
                if !sawDigit { frequency = 1 }
                // In keyed files a missing key column means the word is its own key; elsewhere keys are computed.
                let key: WordKey
                if position < contentEnd {
                    key = Array(String(decoding: UnsafeBufferPointer(rebasing: bytes[(position + 1)..<contentEnd]), as: UTF8.self).utf16)
                } else if keyed {
                    key = Array(word.utf16)
                } else {
                    key = Array(normalizer.normalize(word).utf16)
                }
                if sorted, let last = keys.last, !last.keyLess(key) { sorted = false }
                words.append(word)
                keys.append(key)
                frequencies.append(frequency)
            }
        }
        if sorted { return WordDictionary(words: words, keys: keys, frequencies: frequencies) }

        let order = keys.indices.sorted { a, b in
            if keys[a] == keys[b] { return frequencies[a] > frequencies[b] }
            return keys[a].keyLess(keys[b])
        }
        var keptWords: [String] = [], keptKeys: [WordKey] = [], keptFrequencies: [Int] = []
        for index in order where keptKeys.last != keys[index] {
            keptWords.append(words[index])
            keptKeys.append(keys[index])
            keptFrequencies.append(frequencies[index])
        }
        return WordDictionary(words: keptWords, keys: keptKeys, frequencies: keptFrequencies)
    }

    static func load(text: String, normalizer: KeyNormalizer = .standard) -> WordDictionary {
        load(data: Data(text.utf8), normalizer: normalizer)
    }

    static func load(url: URL, normalizer: KeyNormalizer = .standard) -> WordDictionary {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return .empty }
        return load(data: data, normalizer: normalizer)
    }
}
