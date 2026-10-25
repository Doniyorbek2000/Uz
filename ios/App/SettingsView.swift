import SwiftUI
import UIKit

final class SettingsModel: ObservableObject {
    private let settings = KeyboardSettings()
    static let extensionBundleId = "uz.uzkey.app.keyboard"

    @Published var enabledLayouts: Set<String>
    @Published var theme: ThemeChoice { didSet { settings.theme = theme } }
    @Published var keyHeight: KeyHeight { didSet { settings.keyHeight = keyHeight } }
    @Published var showSuggestions: Bool { didSet { settings.showSuggestions = showSuggestions } }
    @Published var autoCorrect: Bool { didSet { settings.autoCorrect = autoCorrect } }
    @Published var learnWords: Bool { didSet { settings.learnWords = learnWords } }
    @Published var autoCapitalize: Bool { didSet { settings.autoCapitalize = autoCapitalize } }
    @Published var doubleSpacePeriod: Bool { didSet { settings.doubleSpacePeriod = doubleSpacePeriod } }
    @Published var apostropheFix: Bool { didSet { settings.apostropheFix = apostropheFix } }
    @Published var numberRow: Bool { didSet { settings.numberRow = numberRow } }
    @Published var numberHints: Bool { didSet { settings.numberHints = numberHints } }
    @Published var keyPopup: Bool { didSet { settings.keyPopup = keyPopup } }
    @Published var vibration: Bool { didSet { settings.vibration = vibration } }
    @Published var keySound: Bool { didSet { settings.keySound = keySound } }
    @Published var gestureTyping: Bool { didSet { settings.gestureTyping = gestureTyping } }
    @Published var clipboardHistory: Bool { didSet { settings.clipboardHistory = clipboardHistory } }
    @Published var keyboardEnabled = false

    let layoutNames: [String: (name: String, short: String)]

    init() {
        enabledLayouts = Set(settings.enabledLayouts)
        theme = settings.theme
        keyHeight = settings.keyHeight
        showSuggestions = settings.showSuggestions
        autoCorrect = settings.autoCorrect
        learnWords = settings.learnWords
        autoCapitalize = settings.autoCapitalize
        doubleSpacePeriod = settings.doubleSpacePeriod
        apostropheFix = settings.apostropheFix
        numberRow = settings.numberRow
        numberHints = settings.numberHints
        keyPopup = settings.keyPopup
        vibration = settings.vibration
        keySound = settings.keySound
        gestureTyping = settings.gestureTyping
        clipboardHistory = settings.clipboardHistory
        var names: [String: (name: String, short: String)] = [:]
        for id in LayoutIds.languages {
            if let data = SharedResources.data("layouts/\(id).json"), let spec = try? LayoutParser.parse(data) {
                names[id] = (spec.name, spec.shortLabel)
            } else {
                names[id] = (id, id)
            }
        }
        layoutNames = names
        refreshStatus()
    }

    func refreshStatus() {
        let keyboards = UserDefaults.standard.object(forKey: "AppleKeyboards") as? [String] ?? []
        keyboardEnabled = keyboards.contains(Self.extensionBundleId)
    }

    /// Returns false when the change would leave no language enabled.
    func toggle(_ id: String) -> Bool {
        var next = enabledLayouts
        if next.contains(id) { next.remove(id) } else { next.insert(id) }
        guard !next.isEmpty else { return false }
        enabledLayouts = next
        settings.enabledLayouts = Array(next)
        return true
    }

    func clearLearnedWords() {
        settings.userDictionaryEpoch = Date().timeIntervalSince1970
    }
}

private let brand = Color(red: 0x0B / 255, green: 0x8B / 255, blue: 0xC4 / 255)
private let brandDark = Color(red: 0x0B / 255, green: 0x6F / 255, blue: 0xA4 / 255)
private let brandGreen = Color(red: 0x1E / 255, green: 0xB5 / 255, blue: 0x3A / 255)

struct SettingsView: View {
    @StateObject private var model = SettingsModel()
    @State private var tryText = ""
    @State private var alertMessage: String?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    var body: some View {
        NavigationView {
            List {
                Section { header }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)

                Section(header: Text(L10n.setup)) {
                    if model.keyboardEnabled {
                        Label(L10n.enabled, systemImage: "checkmark.circle.fill")
                            .foregroundColor(brandGreen)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L10n.step1Title).font(.headline)
                        Text(L10n.step1Text).font(.subheadline).foregroundColor(.secondary)
                        Button(L10n.openSettings) {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(brand)
                        .padding(.top, 4)
                    }
                    .padding(.vertical, 6)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L10n.step2Title).font(.headline)
                        Text(L10n.step2Text).font(.subheadline).foregroundColor(.secondary)
                    }
                    .padding(.vertical, 6)
                    TextField(L10n.tryHint, text: $tryText)
                        .textFieldStyle(.roundedBorder)
                        .padding(.vertical, 4)
                }

                Section(header: Text(L10n.languages), footer: Text(L10n.languagesFooter)) {
                    ForEach(LayoutIds.languages, id: \.self) { id in
                        Button {
                            if !model.toggle(id) { alertMessage = L10n.keepOneLanguage }
                        } label: {
                            HStack {
                                Image(systemName: model.enabledLayouts.contains(id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(model.enabledLayouts.contains(id) ? brand : .secondary)
                                    .font(.title3)
                                Text(model.layoutNames[id]?.name ?? id).foregroundColor(.primary)
                                Spacer()
                                Text(model.layoutNames[id]?.short ?? id)
                                    .font(.caption.weight(.semibold))
                                    .foregroundColor(brand)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(brand.opacity(0.12))
                                    .clipShape(Capsule())
                            }
                        }
                    }
                }

                Section(header: Text(L10n.theme)) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(ThemeChoice.allCases, id: \.self) { choice in
                                ThemePreview(theme: palette(for: choice), title: L10n.themeName(choice), selected: model.theme == choice)
                                    .onTapGesture { model.theme = choice }
                            }
                        }
                        .padding(.vertical, 8)
                    }
                }

                Section(header: Text(L10n.typing)) {
                    Toggle(L10n.suggestions, isOn: $model.showSuggestions)
                    Toggle(L10n.gesture, isOn: $model.gestureTyping)
                    Toggle(L10n.clipboard, isOn: $model.clipboardHistory)
                    Toggle(L10n.autocorrect, isOn: $model.autoCorrect)
                    Toggle(L10n.learn, isOn: $model.learnWords)
                    Toggle(L10n.autocap, isOn: $model.autoCapitalize)
                    Toggle(L10n.doubleSpace, isOn: $model.doubleSpacePeriod)
                    Toggle(L10n.apostrophe, isOn: $model.apostropheFix)
                }
                .tint(brand)

                Section(header: Text(L10n.keys)) {
                    Toggle(L10n.numberRow, isOn: $model.numberRow)
                    Toggle(L10n.numberHints, isOn: $model.numberHints)
                    Toggle(L10n.popup, isOn: $model.keyPopup)
                    Toggle(L10n.vibration, isOn: $model.vibration)
                    Toggle(L10n.sound, isOn: $model.keySound)
                    VStack(alignment: .leading) {
                        Text(L10n.keyHeight)
                        Picker(L10n.keyHeight, selection: $model.keyHeight) {
                            ForEach(KeyHeight.allCases, id: \.self) { Text(L10n.keyHeightName($0)).tag($0) }
                        }
                        .pickerStyle(.segmented)
                    }
                    .padding(.vertical, 4)
                }
                .tint(brand)

                Section(header: Text(L10n.dictionary)) {
                    Button(L10n.clearLearned, role: .destructive) {
                        model.clearLearnedWords()
                        alertMessage = L10n.clearedLearned
                    }
                }

                Section(header: Text(L10n.tips)) {
                    ForEach(L10n.tipList, id: \.self) { tip in
                        Label(tip, systemImage: "lightbulb")
                    }
                }

                Section(header: Text(L10n.privacy), footer: Text("\(L10n.version) \(version)")) {
                    Label(L10n.privacyText, systemImage: "lock.shield")
                        .font(.subheadline)
                }
            }
            .listStyle(.insetGrouped)
            .navigationBarHidden(true)
        }
        .navigationViewStyle(.stack)
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.refreshStatus() }
        }
        .alert(isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Alert(title: Text(alertMessage ?? ""))
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Text("Ö")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundColor(brandDark)
                    .frame(width: 56, height: 56)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("UzKey").font(.system(size: 28, weight: .bold)).foregroundColor(.white)
                    Text(L10n.tagline).font(.subheadline).foregroundColor(.white.opacity(0.85))
                }
            }
            HStack(spacing: 6) {
                ForEach(["Özbekça", "Oʻzbekcha", "Ўзбекча", "Русский", "English"], id: \.self) { name in
                    Text(name)
                        .font(.caption2)
                        .foregroundColor(.white)
                        .lineLimit(1)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 4)
                        .background(Color.white.opacity(0.18))
                        .clipShape(Capsule())
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [brandDark, brand, Color(red: 0.08, green: 0.65, blue: 0.79)], startPoint: .topLeading, endPoint: .bottomTrailing))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .padding(.vertical, 8)
    }

    private func palette(for choice: ThemeChoice) -> KeyboardTheme {
        switch choice {
        case .system: return colorScheme == .dark ? .dark : .light
        case .light: return .light
        case .dark: return .dark
        case .black: return .black
        case .blue: return .blue
        }
    }
}

private struct ThemePreview: View {
    let theme: KeyboardTheme
    let title: String
    let selected: Bool

    var body: some View {
        VStack(spacing: 6) {
            VStack(spacing: 4) {
                ForEach(0..<3, id: \.self) { row in
                    HStack(spacing: 3) {
                        if row == 2 {
                            RoundedRectangle(cornerRadius: 3).fill(Color(theme.special)).frame(maxWidth: .infinity)
                            RoundedRectangle(cornerRadius: 3).fill(Color(theme.key)).frame(maxWidth: .infinity).layoutPriority(3)
                            RoundedRectangle(cornerRadius: 3).fill(Color(theme.accent)).frame(maxWidth: .infinity)
                        } else {
                            ForEach(0..<5, id: \.self) { _ in
                                RoundedRectangle(cornerRadius: 3).fill(Color(theme.key))
                            }
                        }
                    }
                }
            }
            .padding(7)
            .frame(width: 96, height: 76)
            .background(Color(theme.background))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(selected ? brand : Color.secondary.opacity(0.25), lineWidth: selected ? 3 : 1)
            )
            Text(title)
                .font(.caption.weight(selected ? .semibold : .regular))
                .foregroundColor(selected ? brand : .primary)
        }
    }
}
