import Foundation

enum ThemeChoice: String, CaseIterable {
    case system, light, dark, black, blue
}

enum KeyHeight: String, CaseIterable {
    case small, normal, large

    var scale: Double {
        switch self {
        case .small: return 0.88
        case .normal: return 1
        case .large: return 1.14
        }
    }
}

/// Preferences shared between the container app and the keyboard extension through the App Group.
final class KeyboardSettings {

    static let appGroup = "group.uz.uzkey.keyboard"
    static let changedNotification = "uz.uzkey.keyboard.settings-changed"

    enum Key {
        static let enabledLayouts = "enabled_layouts"
        static let currentLayout = "current_layout"
        static let theme = "theme"
        static let keyHeight = "key_height"
        static let keySound = "key_sound"
        static let soundPack = "sound_pack"
        static let soundVolume = "sound_volume"
        static let vibration = "vibration"
        static let autoCapitalize = "auto_capitalize"
        static let doubleSpacePeriod = "double_space_period"
        static let showSuggestions = "show_suggestions"
        static let autoCorrect = "auto_correct"
        static let learnWords = "learn_words"
        static let numberRow = "number_row"
        static let numberHints = "number_hints"
        static let keyPopup = "key_popup"
        static let apostropheFix = "apostrophe_fix"
        static let recentEmoji = "recent_emoji"
        static let userDictionaryEpoch = "user_dict_epoch"
        static let gestureTyping = "gesture_typing"
        static let clipboardHistory = "clipboard_history"
    }

    let defaults: UserDefaults
    /// Extension-local storage for things the keyboard writes itself (works without Full Access).
    let local: UserDefaults

    init(defaults: UserDefaults? = UserDefaults(suiteName: KeyboardSettings.appGroup), local: UserDefaults = .standard) {
        self.defaults = defaults ?? .standard
        self.local = local
        self.defaults.register(defaults: [
            Key.vibration: true,
            Key.autoCapitalize: true,
            Key.doubleSpacePeriod: true,
            Key.showSuggestions: true,
            Key.autoCorrect: false,
            Key.learnWords: true,
            Key.numberRow: false,
            Key.numberHints: true,
            Key.keyPopup: true,
            Key.apostropheFix: true,
            Key.keySound: false,
            Key.soundPack: SoundPacks.defaultPack,
            Key.soundVolume: 0.7,
            Key.gestureTyping: true,
            Key.clipboardHistory: true,
        ])
    }

    var enabledLayouts: [String] {
        get {
            let stored = (defaults.string(forKey: Key.enabledLayouts) ?? "")
                .split(separator: ",")
                .map(String.init)
                .filter { LayoutIds.languages.contains($0) }
            return stored.isEmpty ? LayoutIds.defaultEnabled : stored
        }
        set {
            var ordered = LayoutIds.languages.filter { newValue.contains($0) }
            if ordered.isEmpty { ordered = [LayoutIds.uzLatn2027] }
            defaults.set(ordered.joined(separator: ","), forKey: Key.enabledLayouts)
        }
    }

    var currentLayout: String {
        get {
            let enabled = enabledLayouts
            if let stored = local.string(forKey: Key.currentLayout), enabled.contains(stored) { return stored }
            return enabled[0]
        }
        set { local.set(newValue, forKey: Key.currentLayout) }
    }

    var theme: ThemeChoice {
        get { ThemeChoice(rawValue: defaults.string(forKey: Key.theme) ?? "") ?? .system }
        set { defaults.set(newValue.rawValue, forKey: Key.theme) }
    }

    var keyHeight: KeyHeight {
        get { KeyHeight(rawValue: defaults.string(forKey: Key.keyHeight) ?? "") ?? .normal }
        set { defaults.set(newValue.rawValue, forKey: Key.keyHeight) }
    }

    var keySound: Bool { get { defaults.bool(forKey: Key.keySound) } set { defaults.set(newValue, forKey: Key.keySound) } }
    /// Id of the key-click sound pack, see `SoundPacks`.
    var soundPack: String {
        get { defaults.string(forKey: Key.soundPack) ?? SoundPacks.defaultPack }
        set { defaults.set(newValue, forKey: Key.soundPack) }
    }
    /// Key-click volume, 0–1.
    var soundVolume: Double {
        get { min(1, max(0, defaults.double(forKey: Key.soundVolume))) }
        set { defaults.set(min(1, max(0, newValue)), forKey: Key.soundVolume) }
    }
    var vibration: Bool { get { defaults.bool(forKey: Key.vibration) } set { defaults.set(newValue, forKey: Key.vibration) } }
    var autoCapitalize: Bool { get { defaults.bool(forKey: Key.autoCapitalize) } set { defaults.set(newValue, forKey: Key.autoCapitalize) } }
    var doubleSpacePeriod: Bool { get { defaults.bool(forKey: Key.doubleSpacePeriod) } set { defaults.set(newValue, forKey: Key.doubleSpacePeriod) } }
    var showSuggestions: Bool { get { defaults.bool(forKey: Key.showSuggestions) } set { defaults.set(newValue, forKey: Key.showSuggestions) } }
    var autoCorrect: Bool { get { defaults.bool(forKey: Key.autoCorrect) } set { defaults.set(newValue, forKey: Key.autoCorrect) } }
    var learnWords: Bool { get { defaults.bool(forKey: Key.learnWords) } set { defaults.set(newValue, forKey: Key.learnWords) } }
    var numberRow: Bool { get { defaults.bool(forKey: Key.numberRow) } set { defaults.set(newValue, forKey: Key.numberRow) } }
    var numberHints: Bool { get { defaults.bool(forKey: Key.numberHints) } set { defaults.set(newValue, forKey: Key.numberHints) } }
    var keyPopup: Bool { get { defaults.bool(forKey: Key.keyPopup) } set { defaults.set(newValue, forKey: Key.keyPopup) } }
    var apostropheFix: Bool { get { defaults.bool(forKey: Key.apostropheFix) } set { defaults.set(newValue, forKey: Key.apostropheFix) } }
    var gestureTyping: Bool { get { defaults.bool(forKey: Key.gestureTyping) } set { defaults.set(newValue, forKey: Key.gestureTyping) } }
    var clipboardHistory: Bool { get { defaults.bool(forKey: Key.clipboardHistory) } set { defaults.set(newValue, forKey: Key.clipboardHistory) } }

    var recentEmojis: [String] {
        get { (local.string(forKey: Key.recentEmoji) ?? "").split(separator: " ").map(String.init) }
        set { local.set(newValue.prefix(32).joined(separator: " "), forKey: Key.recentEmoji) }
    }

    /// Bumped by the app to ask the keyboard to drop learned words.
    var userDictionaryEpoch: Double {
        get { defaults.double(forKey: Key.userDictionaryEpoch) }
        set { defaults.set(newValue, forKey: Key.userDictionaryEpoch) }
    }
}
