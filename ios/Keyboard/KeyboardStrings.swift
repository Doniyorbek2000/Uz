import Foundation

/// Keyboard-extension strings in Uzbek (2027 alphabet), Russian and English.
enum KeyboardStrings {
    private enum Language { case uz, ru, en }

    private static let language: Language = {
        let code = Locale.preferredLanguages.first?.lowercased() ?? "en"
        if code.hasPrefix("uz") { return .uz }
        if code.hasPrefix("ru") { return .ru }
        return .en
    }()

    private static func pick(_ uz: String, _ ru: String, _ en: String) -> String {
        switch language {
        case .uz: return uz
        case .ru: return ru
        case .en: return en
        }
    }

    static var delete: String { pick("Öçiriş", "Удалить", "Delete") }
    static var space: String { pick("Böş joy", "Пробел", "Space") }
    static var enter: String { pick("Kiritiş", "Ввод", "Return") }
    static var symbols: String { pick("Belgilar", "Символы", "Symbols") }
    static var letters: String { pick("Harflar", "Буквы", "Letters") }
    static var language: String { pick("Tilni almaştiriş", "Сменить язык", "Switch language") }
    static var nextKeyboard: String { pick("Keyingi klaviatura", "Следующая клавиатура", "Next keyboard") }
    static var clipboard: String { pick("Bufer", "Буфер обмена", "Clipboard") }
    static var clear: String { pick("Tozalaş", "Очистить", "Clear") }
    static var clipboardEmpty: String { pick("Nusxa olingan matnlar şu yerda çiqadi. Saqlab qöyiş uçun bosib turing.", "Скопированный текст появится здесь. Удерживайте запись, чтобы закрепить её.", "Copied text will appear here. Long-press an item to pin it.") }
    static var clipboardNeedsAccess: String { pick("Bufer tarixi uçun Sozlamalar → UzKey → Klaviaturalar → «Töliq ruxsat» ni yoqing.", "Для истории буфера включите «Полный доступ»: Настройки → UzKey → Клавиатуры.", "Turn on Allow Full Access (Settings → UzKey → Keyboards) to use clipboard history.") }

    static func toolbar(_ action: ToolbarAction) -> String {
        switch action {
        case .emoji: return "Emoji"
        case .clipboard: return clipboard
        case .convert: return pick("Alifboni ögiriş", "Сменить алфавит", "Convert alphabet")
        case .settings: return pick("Sozlamalar", "Настройки", "Settings")
        case .hide: return pick("Klaviaturani yaşiriş", "Скрыть клавиатуру", "Hide keyboard")
        }
    }
}
