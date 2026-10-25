import Foundation

/// Maps a word to the key used for every dictionary comparison.
struct KeyNormalizer {
    let normalize: (String) -> String

    static let standard = KeyNormalizer { TextRules.normalizeKey($0) }

    /// In the 2027 alphabet the old spellings (sh, ch, oʻ, gʻ) still find the new words.
    static let latin2027 = KeyNormalizer { UzbekTransliterator.latinToLatin2027(TextRules.normalizeKey($0)) }

    static func forDictionary(_ id: String) -> KeyNormalizer {
        id == LayoutIds.uzLatn2027 ? latin2027 : standard
    }
}

enum TextRules {

    static let turnedComma: Character = "\u{02BB}" // ʻ — part of oʻ / gʻ
    static let modifierApostrophe: Character = "\u{02BC}" // ʼ — tutuq belgisi

    private static let apostrophes: Set<Character> = ["'", "\u{2018}", "\u{2019}", "`", turnedComma, modifierApostrophe]
    private static let sentenceEnd: Set<Character> = [".", "!", "?", "…"]
    private static let attaching: Set<Character> = [".", ",", "!", "?", ";", ":", ")", "…", "%"]

    static func isApostrophe(_ c: Character) -> Bool { apostrophes.contains(c) }

    static func isWordChar(_ c: Character) -> Bool { c.isLetter || apostrophes.contains(c) }

    static func attachesToPreviousWord(_ text: String) -> Bool {
        text.count == 1 && attaching.contains(text.first!)
    }

    static func normalizeKey(_ word: String) -> String {
        var result = ""
        result.reserveCapacity(word.count)
        for c in word.lowercased() {
            if apostrophes.contains(c) {
                result.append("'")
            } else if c == "ё" {
                result.append("е")
            } else {
                result.append(c)
            }
        }
        return result
    }

    /// The word that ends exactly at the cursor.
    static func currentWord(_ textBeforeCursor: String) -> String {
        var characters: [Character] = []
        for c in textBeforeCursor.reversed() {
            guard isWordChar(c) else { break }
            characters.append(c)
        }
        var word = String(characters.reversed())
        while let first = word.first, isApostrophe(first) { word.removeFirst() }
        return word
    }

    /// The complete word before the current one, unless a sentence boundary intervenes.
    static func previousWord(_ textBeforeCursor: String) -> String? {
        var characters = Array(textBeforeCursor)
        while let last = characters.last, isWordChar(last) { characters.removeLast() }
        while let last = characters.last, !isWordChar(last) {
            if sentenceEnd.contains(last) || last == "\n" { return nil }
            characters.removeLast()
        }
        if characters.isEmpty { return nil }
        let word = currentWord(String(characters))
        return word.isEmpty ? nil : word
    }

    static func isSentenceStart(_ textBeforeCursor: String) -> Bool {
        guard let last = textBeforeCursor.last else { return true }
        if !last.isWhitespace { return false }
        for c in textBeforeCursor.reversed() {
            if c == "\n" { return true }
            if c.isWhitespace { continue }
            return sentenceEnd.contains(c)
        }
        return true
    }

    static func matchCase(_ candidate: String, typed: String) -> String {
        guard let firstTyped = typed.first, !candidate.isEmpty else { return candidate }
        let letters = typed.filter { $0.isLetter }
        if letters.count >= 2 && letters.allSatisfy({ $0.isUppercase }) { return candidate.uppercased() }
        if firstTyped.isUppercase { return candidate.prefix(1).uppercased() + candidate.dropFirst() }
        return candidate
    }

    static func uppercaseKeyText(_ text: String) -> String {
        text == "ß" ? "ẞ" : text.uppercased()
    }

    /// Rewrites an apostrophe typed after o/g into the proper letter for the active alphabet.
    static func apostropheReplacement(layoutId: String, previous: Character, typed: String) -> String? {
        guard typed.count == 1, let c = typed.first else { return nil }
        guard c == "'" || c == "\u{2018}" || c == "\u{2019}" || c == "`" || c == turnedComma else { return nil }
        switch layoutId {
        case LayoutIds.uzLatn2027:
            switch previous {
            case "o": return "ö"
            case "O": return "Ö"
            case "g": return "ğ"
            case "G": return "Ğ"
            default: return nil
            }
        case LayoutIds.uzLatn:
            switch previous {
            case "o", "O", "g", "G": return String(previous) + String(turnedComma)
            default: return previous.isLetter ? String(previous) + String(modifierApostrophe) : nil
            }
        default:
            return nil
        }
    }
}
