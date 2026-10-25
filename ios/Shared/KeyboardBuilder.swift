import Foundation

enum FieldKind {
    case text, email, url, number, phone
}

struct BuildOptions {
    var numberRow: Bool
    var numberHints: Bool
    var fieldKind: FieldKind
    var showLanguageKey: Bool
    var showGlobeKey: Bool
}

enum KeyboardBuilder {

    private static let digits = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]
    private static let digitRowAlternates = ["!", "@", "#", "$", "%", "^", "&", "*", "(", ")"]
    private static let periodAlternates = [",", "?", "!", "…", "-", ":", ";", "'", "\"", "@", "/"]
    private static let webAlternates = [".uz", ".com", ".ru", ".org", ".net", ",", "-", "_"]

    static func build(_ layout: LayoutSpec, isLetterLayer: Bool, options: BuildOptions) -> [[KeySpec]] {
        if layout.ownBottomRow { return layout.rows }
        var rows: [[KeySpec]] = []
        if isLetterLayer && options.numberRow {
            rows.append(digits.enumerated().map { index, digit in
                KeySpec(type: .char, label: digit, output: digit, alternates: [digitRowAlternates[index]])
            })
            rows.append(contentsOf: layout.rows)
        } else if isLetterLayer && options.numberHints, let first = layout.rows.first {
            rows.append(first.enumerated().map { index, key in
                guard key.type == .char, index < digits.count else { return key }
                var hinted = key
                hinted.hint = digits[index]
                hinted.alternates = [digits[index]] + key.alternates
                return hinted
            })
            rows.append(contentsOf: layout.rows.dropFirst())
        } else {
            rows.append(contentsOf: layout.rows)
        }
        rows.append(bottomRow(isLetterLayer: isLetterLayer, options: options))
        return rows
    }

    private static func bottomRow(isLetterLayer: Bool, options: BuildOptions) -> [KeySpec] {
        var row: [KeySpec] = []
        row.append(isLetterLayer
            ? KeySpec(type: .symbols, label: "123", weight: 1.3)
            : KeySpec(type: .letters, label: "ABC", weight: 1.3))
        if options.showGlobeKey {
            row.append(KeySpec(type: .globe))
        }
        row.append(KeySpec(type: options.showLanguageKey ? .language : .emoji))
        switch options.fieldKind {
        case .email:
            row.append(KeySpec(type: .char, label: "@", output: "@", alternates: ["_", "-"]))
        case .url:
            row.append(KeySpec(type: .char, label: "/", output: "/", alternates: [":", "-", "_"]))
        default:
            if !options.showGlobeKey {
                row.append(KeySpec(type: .char, label: ",", output: ",", alternates: [";", ":", "-"]))
            }
        }
        row.append(KeySpec(type: .space, flex: true))
        let alternates = (options.fieldKind == .email || options.fieldKind == .url) ? webAlternates : periodAlternates
        row.append(KeySpec(type: .char, label: ".", output: ".", alternates: alternates))
        row.append(KeySpec(type: .enter, weight: 1.7))
        return row
    }
}
