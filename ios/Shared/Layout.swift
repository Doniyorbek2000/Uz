import Foundation

enum KeyType: Equatable {
    case char, shift, backspace, space, enter, symbols, symbolsMore, letters, language, emoji, globe
}

struct KeySpec: Equatable {
    var type: KeyType
    var label: String = ""
    var output: String = ""
    var alternates: [String] = []
    var weight: Double = 1
    var flex: Bool = false
    var hint: String?
}

struct LayoutSpec: Equatable {
    let id: String
    let name: String
    let spaceLabel: String
    let shortLabel: String
    let locale: String
    let dictionary: String
    let legacyConversion: Bool
    let ownBottomRow: Bool
    let rows: [[KeySpec]]

    var isUzbek: Bool { id.hasPrefix("uz") }
}

enum LayoutIds {
    static let uzLatn2027 = "uz_latn_2027"
    static let uzLatn = "uz_latn"
    static let uzCyrl = "uz_cyrl"
    static let ru = "ru"
    static let en = "en"
    static let symbols = "symbols"
    static let symbolsMore = "symbols2"
    static let numpad = "numpad"

    static let languages = [uzLatn2027, uzLatn, uzCyrl, ru, en]
    static let defaultEnabled = [uzLatn2027, ru, en]
}

enum LayoutError: Error {
    case invalidFormat(String)
}

enum LayoutParser {

    static func parse(_ data: Data) throws -> LayoutSpec {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw LayoutError.invalidFormat("root is not an object")
        }
        guard let id = root["id"] as? String, let name = root["name"] as? String, let rowsJson = root["rows"] as? [[Any]] else {
            throw LayoutError.invalidFormat("missing id, name or rows")
        }
        let alternates = (root["alternates"] as? [String: [String]]) ?? [:]
        let rows = try rowsJson.map { row in try row.map { try parseKey($0, alternates: alternates) } }
        return LayoutSpec(
            id: id,
            name: name,
            spaceLabel: root["spaceLabel"] as? String ?? "",
            shortLabel: root["short"] as? String ?? "",
            locale: root["locale"] as? String ?? "",
            dictionary: root["dictionary"] as? String ?? "",
            legacyConversion: root["legacyConversion"] as? Bool ?? false,
            ownBottomRow: root["ownBottomRow"] as? Bool ?? false,
            rows: rows
        )
    }

    private static func parseKey(_ item: Any, alternates: [String: [String]]) throws -> KeySpec {
        if let text = item as? String {
            return KeySpec(type: .char, label: text, output: text, alternates: alternates[text] ?? [])
        }
        guard let object = item as? [String: Any] else {
            throw LayoutError.invalidFormat("unsupported key \(item)")
        }
        let explicitWeight = (object["w"] as? NSNumber)?.doubleValue
        let weight = explicitWeight ?? 1
        if let text = object["k"] as? String {
            let alt = (object["alt"] as? [String]) ?? alternates[text] ?? []
            return KeySpec(type: .char, label: text, output: text, alternates: alt, weight: weight)
        }
        guard let token = object["t"] as? String else {
            throw LayoutError.invalidFormat("key without k or t")
        }
        let type: KeyType
        switch token {
        case "shift": type = .shift
        case "bksp": type = .backspace
        case "space": type = .space
        case "enter": type = .enter
        case "sym1": type = .symbols
        case "sym2": type = .symbolsMore
        case "letters": type = .letters
        case "lang": type = .language
        case "emoji": type = .emoji
        default: throw LayoutError.invalidFormat("unknown key type \(token)")
        }
        let flex = explicitWeight == nil && (type == .shift || type == .backspace)
        return KeySpec(type: type, label: object["l"] as? String ?? "", output: "", alternates: [], weight: weight, flex: flex)
    }
}

/// Locates the files shipped in the `shared` folder reference of whichever bundle contains this code.
enum SharedResources {
    private final class BundleMarker {}

    static var bundle: Bundle { Bundle(for: BundleMarker.self) }

    static func url(_ path: String) -> URL? {
        guard let base = bundle.resourceURL?.appendingPathComponent("shared", isDirectory: true) else { return nil }
        let url = base.appendingPathComponent(path)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    static func data(_ path: String) -> Data? {
        guard let url = url(path) else { return nil }
        return try? Data(contentsOf: url)
    }
}
