import Foundation

/// Converts Uzbek text between the 2027 Latin alphabet, the current Latin alphabet and Cyrillic.
enum UzbekTransliterator {

    enum Script {
        case latin2027, latin, cyrillic
    }

    private static let turnedComma = TextRules.turnedComma
    private static let tutuq = TextRules.modifierApostrophe

    static func script(forLayout id: String) -> Script? {
        switch id {
        case LayoutIds.uzLatn2027: return .latin2027
        case LayoutIds.uzLatn: return .latin
        case LayoutIds.uzCyrl: return .cyrillic
        default: return nil
        }
    }

    static func convert(_ text: String, to target: Script) -> String {
        let latin = latin2027ToLatin(cyrillicToLatin(text))
        switch target {
        case .latin: return latin
        case .latin2027: return latinToLatin2027(latin)
        case .cyrillic: return latinToCyrillic(latin)
        }
    }

    // MARK: Cyrillic → current Latin

    private static let cyrillicToLatinMap: [Character: String] = [
        "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "ё": "yo", "ж": "j", "з": "z",
        "и": "i", "й": "y", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p",
        "р": "r", "с": "s", "т": "t", "у": "u", "ф": "f", "х": "x", "ч": "ch",
        "ш": "sh", "щ": "sh", "ъ": String(tutuq), "ы": "i", "ь": "", "э": "e", "ю": "yu", "я": "ya",
        "ў": "o" + String(turnedComma), "қ": "q", "ғ": "g" + String(turnedComma), "ҳ": "h",
    ]
    private static let cyrillicVowels: Set<Character> = ["а", "е", "ё", "и", "о", "у", "э", "ю", "я", "ў", "ъ", "ь"]

    private static func lower(_ c: Character) -> Character { c.lowercased().first ?? c }

    private static func capitalized(_ s: String) -> String { s.prefix(1).uppercased() + s.dropFirst() }

    static func cyrillicToLatin(_ text: String) -> String {
        let chars = Array(text)
        var out = ""
        for i in chars.indices {
            let original = chars[i]
            let lowerChar = lower(original)
            let prev: Character? = i > 0 ? lower(chars[i - 1]) : nil
            let mapped: String?
            if lowerChar == "е" {
                mapped = (prev == nil || !prev!.isLetter || cyrillicVowels.contains(prev!)) ? "ye" : "e"
            } else if lowerChar == "ц" {
                mapped = (prev == nil || !prev!.isLetter || !cyrillicVowels.contains(prev!)) ? "s" : "ts"
            } else {
                mapped = cyrillicToLatinMap[lowerChar]
            }
            guard let latin = mapped else {
                out.append(original)
                continue
            }
            if original.isUppercase && !latin.isEmpty {
                let next: Character? = i + 1 < chars.count ? chars[i + 1] : nil
                let previous: Character? = i > 0 ? chars[i - 1] : nil
                let wholeWordUpper = (next?.isUppercase ?? false) || (!(next?.isLetter ?? false) && (previous?.isUppercase ?? false))
                out += wholeWordUpper ? latin.uppercased() : capitalized(latin)
            } else {
                out += latin
            }
        }
        return out
    }

    // MARK: 2027 Latin ↔ current Latin

    static func latin2027ToLatin(_ text: String) -> String {
        let chars = Array(text)
        var out = ""
        for i in chars.indices {
            let c = chars[i]
            let neighbourUpper = (i + 1 < chars.count && chars[i + 1].isUppercase) || (i > 0 && chars[i - 1].isUppercase)
            switch c {
            case "ö": out += "o" + String(turnedComma)
            case "Ö": out += "O" + String(turnedComma)
            case "ğ": out += "g" + String(turnedComma)
            case "Ğ": out += "G" + String(turnedComma)
            case "ş": out += "sh"
            case "Ş": out += neighbourUpper ? "SH" : "Sh"
            case "ç": out += "ch"
            case "Ç": out += neighbourUpper ? "CH" : "Ch"
            default: out.append(c)
            }
        }
        return out
    }

    private static let turnedCommaLike: Set<Character> = ["'", "\u{2018}", "\u{2019}", "`", turnedComma]

    static func latinToLatin2027(_ text: String) -> String {
        let chars = Array(text)
        var out = ""
        var i = 0
        while i < chars.count {
            let c = chars[i]
            let next: Character? = i + 1 < chars.count ? chars[i + 1] : nil
            if let next, "oOgG".contains(c), turnedCommaLike.contains(next) {
                switch c {
                case "o": out.append("ö")
                case "O": out.append("Ö")
                case "g": out.append("ğ")
                default: out.append("Ğ")
                }
                i += 2
            } else if let next, c == "s" || c == "S", next == "h" || next == "H" {
                out.append(c == "s" ? "ş" : "Ş")
                i += 2
            } else if let next, c == "c" || c == "C", next == "h" || next == "H" {
                out.append(c == "c" ? "ç" : "Ç")
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out
    }

    // MARK: current Latin → Cyrillic

    private static let latinToCyrillicMap: [Character: String] = [
        "a": "а", "b": "б", "d": "д", "e": "е", "f": "ф", "g": "г", "h": "ҳ", "i": "и",
        "j": "ж", "k": "к", "l": "л", "m": "м", "n": "н", "o": "о", "p": "п", "q": "қ",
        "r": "р", "s": "с", "t": "т", "u": "у", "v": "в", "x": "х", "y": "й", "z": "з",
        "c": "с", "w": "в",
    ]
    private static let latinVowels: Set<Character> = ["a", "e", "i", "o", "u"]

    static func latinToCyrillic(_ text: String) -> String {
        let chars = Array(text)
        var out = ""
        var i = 0
        while i < chars.count {
            let c = chars[i]
            let l = lower(c)
            let next: Character? = i + 1 < chars.count ? chars[i + 1] : nil
            let nextLower = next.map(lower)
            let afterNext: Character? = i + 2 < chars.count ? chars[i + 2] : nil
            let prevLower: Character? = i > 0 ? lower(chars[i - 1]) : nil
            let atWordStart = prevLower == nil || !prevLower!.isLetter

            var cyrillic: String
            var consumed = 1
            if (l == "o" || l == "g"), let n = nextLower, turnedCommaLike.contains(n) {
                cyrillic = l == "o" ? "ў" : "ғ"
                consumed = 2
            } else if l == "s" && nextLower == "h" {
                cyrillic = "ш"; consumed = 2
            } else if l == "c" && nextLower == "h" {
                cyrillic = "ч"; consumed = 2
            } else if l == "y" && nextLower == "o" && !(afterNext.map { turnedCommaLike.contains($0) } ?? false) {
                cyrillic = "ё"; consumed = 2
            } else if l == "y" && nextLower == "u" {
                cyrillic = "ю"; consumed = 2
            } else if l == "y" && nextLower == "a" {
                cyrillic = "я"; consumed = 2
            } else if l == "y" && nextLower == "e" {
                cyrillic = "е"; consumed = 2
            } else if l == "e" && (atWordStart || latinVowels.contains(prevLower!)) {
                cyrillic = "э"
            } else if c == tutuq || ((c == "'" || c == "\u{2019}") && (prevLower?.isLetter ?? false) && (nextLower?.isLetter ?? false)) {
                cyrillic = "ъ"
            } else {
                cyrillic = latinToCyrillicMap[l] ?? String(c)
            }
            if c.isUppercase { cyrillic = cyrillic.uppercased() }
            out += cyrillic
            i += consumed
        }
        return out
    }
}
