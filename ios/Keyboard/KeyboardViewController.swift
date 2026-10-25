import UIKit

/// Input view that enables system key clicks (`UIDevice.playInputClick`).
final class KeyboardInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

final class KeyboardViewController: UIInputViewController, KeyboardViewDelegate, EmojiPanelDelegate, ClipboardPanelDelegate {

    private enum Layer {
        case letters, symbols, symbolsMore, numpad
    }

    private enum LastAction {
        case none, character, space, pick, autocorrect, delete, gesture
    }

    private let settings = KeyboardSettings()
    private let keyboardView = KeyboardView()
    private let emojiView = EmojiPanelView()
    private let clipboardView = ClipboardPanelView()
    private let clipboardHistory = ClipboardHistory()
    private var lastPasteboardChange = -1
    private var lastGestureWord: String?
    private var gestureAlternatives: [String] = []
    private let globeButton = UIButton(type: .custom)
    private var heightConstraint: NSLayoutConstraint?
    private let feedbackGenerator = UIImpactFeedbackGenerator(style: .light)

    private var layouts: [String: LayoutSpec] = [:]
    private var dictionaries: [String: WordDictionary] = [:]
    private var loadingDictionaries: Set<String> = []
    private var users: [String: UserDictionary] = [:]
    private var engines: [String: SuggestionEngine] = [:]
    /// The dictionary of the language switched away from, kept so toggling back is instant.
    private var previousDictionary = ""
    private var emojiCategories: [EmojiCategory]?
    private var saveWork: DispatchWorkItem?

    private var layer = Layer.letters
    private var shift = ShiftState.off
    private var shiftIsAuto = false
    private var shiftHeld = false
    private var shiftChorded = false
    private var lastShiftTap: TimeInterval = 0
    private var fieldKind = FieldKind.text
    private var lastAction = LastAction.none
    private var lastSpaceTime: TimeInterval = 0
    private var autoSpacePending = false
    private var lastAutocorrect: (original: String, corrected: String)?
    private var userDictionaryEpoch: Double = 0

    private static let doubleTapInterval: TimeInterval = 0.38
    private static let doubleSpaceInterval: TimeInterval = 0.8

    private var proxy: UITextDocumentProxy { textDocumentProxy }

    // MARK: Lifecycle

    override func loadView() {
        let input = KeyboardInputView(frame: .zero, inputViewStyle: .keyboard)
        input.allowsSelfSizing = true
        inputView = input
        view = input
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        keyboardView.delegate = self
        keyboardView.translatesAutoresizingMaskIntoConstraints = false
        emojiView.delegate = self
        emojiView.translatesAutoresizingMaskIntoConstraints = false
        emojiView.isHidden = true
        clipboardView.delegate = self
        clipboardView.translatesAutoresizingMaskIntoConstraints = false
        clipboardView.isHidden = true
        view.addSubview(keyboardView)
        view.addSubview(emojiView)
        view.addSubview(clipboardView)
        NSLayoutConstraint.activate([
            clipboardView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            clipboardView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            clipboardView.topAnchor.constraint(equalTo: view.topAnchor),
            clipboardView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            keyboardView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keyboardView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keyboardView.topAnchor.constraint(equalTo: view.topAnchor),
            keyboardView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            emojiView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            emojiView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            emojiView.topAnchor.constraint(equalTo: view.topAnchor),
            emojiView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        globeButton.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
        keyboardView.addSubview(globeButton)
        keyboardView.onLayout = { [weak self] in self?.positionGlobeButton() }
        userDictionaryEpoch = settings.userDictionaryEpoch
        // Only the active language is loaded up front: keyboard extensions run under a tight memory limit.
        preloadDictionary(currentLayout.dictionary)
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        evictDictionaries(keeping: [currentLayout.dictionary])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if settings.userDictionaryEpoch != userDictionaryEpoch {
            userDictionaryEpoch = settings.userDictionaryEpoch
            clearLearnedWords()
        }
        configureForField()
        layer = (fieldKind == .number || fieldKind == .phone) ? .numpad : .letters
        shift = .off
        shiftIsAuto = false
        lastAction = .none
        autoSpacePending = false
        lastAutocorrect = nil
        showKeyboardPanel()
        rebuildKeyboard()
        refreshState()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // System gesture recognizers delay touches near the screen edges; keys must react instantly.
        view.window?.gestureRecognizers?.forEach { $0.delaysTouchesBegan = false }
        if hasFullAccess { feedbackGenerator.prepare() }
        // The pasteboard is read only when the clipboard panel is opened: reading it here would make iOS
        // show its "UzKey pasted from …" notice every time the keyboard appears after a copy.
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.rebuildKeyboard() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        keyboardView.cancelAllPointers()
        saveLearnedWords()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyTheme()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        // Rebuild only when the host switched to a field of another kind; rebuilding cancels touches.
        let previousKind = fieldKind
        configureForField()
        if fieldKind != previousKind || keyboardView.enterIcon != enterIcon() {
            if fieldKind != previousKind {
                layer = (fieldKind == .number || fieldKind == .phone) ? .numpad : .letters
            }
            rebuildKeyboard()
        }
        refreshState()
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        super.selectionDidChange(textInput)
        autoSpacePending = false
        lastAutocorrect = nil
        refreshState()
    }

    // MARK: Resources

    private func layout(_ id: String) -> LayoutSpec {
        if let cached = layouts[id] { return cached }
        let parsed = SharedResources.data("layouts/\(id).json").flatMap { try? LayoutParser.parse($0) }
        let spec = parsed ?? LayoutSpec(id: id, name: id, spaceLabel: "", shortLabel: id, locale: "", dictionary: "", legacyConversion: false, ownBottomRow: false, rows: [])
        layouts[id] = spec
        return spec
    }

    private var currentLayout: LayoutSpec { layout(settings.currentLayout) }

    private func preloadDictionary(_ id: String) {
        guard !id.isEmpty, dictionaries[id] == nil, !loadingDictionaries.contains(id) else { return }
        loadingDictionaries.insert(id)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let dictionary = SharedResources.url("dictionaries/\(id).txt")
                .map { WordDictionary.load(url: $0, normalizer: KeyNormalizer.forDictionary(id)) } ?? .empty
            DispatchQueue.main.async {
                guard let self else { return }
                self.dictionaries[id] = dictionary
                self.loadingDictionaries.remove(id)
                self.evictDictionaries(keeping: [self.currentLayout.dictionary, id, self.previousDictionary])
                self.refreshState()
            }
        }
    }

    /// Drops bundled dictionaries (and their engines) that are not in `kept`; learned words stay loaded.
    private func evictDictionaries(keeping kept: Set<String>) {
        for id in dictionaries.keys where !kept.contains(id) {
            dictionaries[id] = nil
            engines[id] = nil
        }
    }

    private func userDictionary(_ id: String) -> UserDictionary {
        if let cached = users[id] { return cached }
        let dictionary = UserDictionary(normalizer: KeyNormalizer.forDictionary(id))
        if let url = userFileURL(id), let data = try? Data(contentsOf: url) {
            try? dictionary.restore(data)
        }
        users[id] = dictionary
        return dictionary
    }

    private func engine(_ id: String) -> SuggestionEngine? {
        guard !id.isEmpty else { return nil }
        if let cached = engines[id] { return cached }
        guard let dictionary = dictionaries[id] else {
            preloadDictionary(id)
            return nil
        }
        let engine = SuggestionEngine(dictionary: dictionary, user: userDictionary(id), normalizer: KeyNormalizer.forDictionary(id))
        engines[id] = engine
        return engine
    }

    private func userFileURL(_ id: String) -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("user_words_\(id).json")
    }

    private func scheduleSave() {
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.saveLearnedWords() }
        saveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: work)
    }

    private func saveLearnedWords() {
        saveWork?.cancel()
        for (id, dictionary) in users where dictionary.isDirty {
            guard let url = userFileURL(id), let data = try? dictionary.serialize() else { continue }
            try? data.write(to: url, options: .atomic)
        }
    }

    private func clearLearnedWords() {
        users.values.forEach { $0.clear() }
        for id in LayoutIds.languages {
            let dictionaryId = layout(id).dictionary
            if let url = userFileURL(dictionaryId) { try? FileManager.default.removeItem(at: url) }
        }
    }

    // MARK: Field configuration

    private func configureForField() {
        switch proxy.keyboardType ?? .default {
        case .numberPad, .phonePad, .decimalPad, .asciiCapableNumberPad, .numbersAndPunctuation:
            fieldKind = proxy.keyboardType == .phonePad ? .phone : .number
        case .emailAddress:
            fieldKind = .email
        case .URL, .webSearch:
            fieldKind = .url
        default:
            fieldKind = .text
        }
    }

    private var suggestionsAllowed: Bool {
        fieldKind == .text && proxy.autocorrectionType != .no && proxy.isSecureTextEntry != true
    }

    private var learningAllowed: Bool {
        suggestionsAllowed && settings.learnWords && settings.showSuggestions
    }

    private func enterIcon() -> Icon {
        switch proxy.returnKeyType ?? .default {
        case .search, .google, .yahoo: return .search
        case .send: return .send
        case .go, .next, .continue, .route, .join: return .next
        case .done: return .done
        default: return .returnKey
        }
    }

    // MARK: Building

    private func applyTheme() {
        let theme = KeyboardTheme.resolve(settings.theme, traits: traitCollection)
        keyboardView.theme = theme
        emojiView.theme = theme
        clipboardView.theme = theme
        view.backgroundColor = theme.background
    }

    private func rebuildKeyboard() {
        applyTheme()
        keyboardView.heightScale = CGFloat(settings.keyHeight.scale)
        let isPad = traitCollection.userInterfaceIdiom == .pad
        let screen = view.window?.windowScene?.screen.bounds.size ?? UIScreen.main.bounds.size
        let landscape = screen.width > screen.height
        keyboardView.baseRowHeight = isPad ? (landscape ? 62 : 58) : (landscape ? 40 : 54)
        keyboardView.stripHeight = landscape && !isPad ? 36 : 44
        keyboardView.gestureTyping = settings.gestureTyping && suggestionsAllowed
        keyboardView.showKeyPopup = settings.keyPopup
        keyboardView.enterIcon = enterIcon()
        let language = currentLayout
        let spec: LayoutSpec
        switch layer {
        case .letters: spec = language
        case .symbols: spec = layout(LayoutIds.symbols)
        case .symbolsMore: spec = layout(LayoutIds.symbolsMore)
        case .numpad: spec = layout(LayoutIds.numpad)
        }
        let options = BuildOptions(
            numberRow: settings.numberRow,
            numberHints: settings.numberHints,
            fieldKind: fieldKind,
            showLanguageKey: settings.enabledLayouts.count > 1,
            showGlobeKey: needsInputModeSwitchKey
        )
        keyboardView.isLetterLayer = layer == .letters
        keyboardView.spaceLabel = (layer == .letters || layer == .symbols) ? language.spaceLabel : ""
        keyboardView.languageLabel = language.shortLabel
        keyboardView.setKeys(KeyboardBuilder.build(spec, isLetterLayer: layer == .letters, options: options))
        keyboardView.shiftState = shift
        updateHeight()
    }

    private func updateHeight() {
        let height = keyboardView.preferredHeight
        if let constraint = heightConstraint {
            constraint.constant = height
        } else {
            let constraint = view.heightAnchor.constraint(equalToConstant: height)
            constraint.priority = UILayoutPriority(999)
            constraint.isActive = true
            heightConstraint = constraint
        }
    }

    private func positionGlobeButton() {
        if let frame = keyboardView.globeKeyFrame {
            globeButton.frame = frame
            globeButton.isHidden = false
        } else {
            globeButton.isHidden = true
        }
    }

    private func setLayer(_ newLayer: Layer) {
        guard layer != newLayer else { return }
        layer = newLayer
        if newLayer != .letters {
            shift = .off
            shiftIsAuto = false
        }
        rebuildKeyboard()
        refreshState()
    }

    private func showKeyboardPanel() {
        emojiView.isHidden = true
        clipboardView.isHidden = true
        keyboardView.isHidden = false
    }

    private func showClipboardPanel() {
        keyboardView.cancelAllPointers()
        if hasFullAccess {
            captureClipboard()
            clipboardView.setItems(settings.clipboardHistory ? clipboardHistory.items : [])
        } else {
            clipboardView.setItems([], message: KeyboardStrings.clipboardNeedsAccess)
        }
        keyboardView.isHidden = true
        clipboardView.isHidden = false
    }

    /// Records the current pasteboard text (needs Full Access; skipped for secure fields).
    private func captureClipboard() {
        guard hasFullAccess, settings.clipboardHistory, proxy.isSecureTextEntry != true else { return }
        let pasteboard = UIPasteboard.general
        guard pasteboard.changeCount != lastPasteboardChange else { return }
        lastPasteboardChange = pasteboard.changeCount
        guard pasteboard.hasStrings, let text = pasteboard.string else { return }
        clipboardHistory.add(text)
    }

    private func showEmojiPanel() {
        if emojiCategories == nil { emojiCategories = EmojiData.load() }
        emojiView.setCategories(recent: settings.recentEmojis, all: emojiCategories ?? [])
        keyboardView.cancelAllPointers()
        keyboardView.isHidden = true
        emojiView.isHidden = false
    }

    // MARK: State

    private func refreshState() {
        let before = proxy.documentContextBeforeInput ?? ""
        updateAutoShift(before)
        keyboardView.shiftState = shift
        let convertAvailable = layer == .letters && UzbekTransliterator.script(forLayout: currentLayout.id) != nil
        guard settings.showSuggestions, suggestionsAllowed, layer != .numpad, (proxy.selectedText ?? "").isEmpty else {
            keyboardView.setSuggestions(.none, convertAvailable: convertAvailable)
            return
        }
        if lastAction == .gesture, let gestureWord = lastGestureWord, !gestureAlternatives.isEmpty {
            keyboardView.setSuggestions(Suggestions(typed: "", candidates: [gestureWord] + gestureAlternatives, typedIsKnown: true), convertAvailable: convertAvailable)
            return
        }
        let typed = TextRules.currentWord(before)
        let result: Suggestions
        if let engine = engine(currentLayout.dictionary) {
            if typed.isEmpty {
                result = before.last == " "
                    ? Suggestions(typed: "", candidates: engine.predictNext(after: TextRules.previousWord(before)), typedIsKnown: true)
                    : .none
            } else {
                result = engine.suggest(typed, previousWord: TextRules.previousWord(before))
            }
        } else {
            result = typed.isEmpty ? .none : Suggestions(typed: typed, candidates: [], typedIsKnown: true)
        }
        keyboardView.setSuggestions(result, convertAvailable: convertAvailable)
    }

    private func updateAutoShift(_ before: String) {
        guard layer == .letters, shift != .locked, !shiftHeld else { return }
        let wantsCaps: Bool
        if !settings.autoCapitalize || fieldKind != .text {
            wantsCaps = false
        } else {
            switch proxy.autocapitalizationType ?? .sentences {
            case .none: wantsCaps = false
            case .words: wantsCaps = before.isEmpty || before.last?.isWhitespace == true
            case .allCharacters: wantsCaps = true
            default: wantsCaps = TextRules.isSentenceStart(before)
            }
        }
        if wantsCaps && shift == .off {
            shift = .once
            shiftIsAuto = true
        } else if !wantsCaps && shift == .once && shiftIsAuto {
            shift = .off
            shiftIsAuto = false
        }
    }

    private func feedback(_ type: KeyType) {
        if settings.vibration && hasFullAccess {
            feedbackGenerator.impactOccurred()
            feedbackGenerator.prepare()
        }
        if settings.keySound {
            UIDevice.current.playInputClick()
        }
    }

    // MARK: KeyboardViewDelegate

    func keyDown(_ key: KeySpec) {
        feedback(key.type)
        switch key.type {
        case .shift: handleShiftDown()
        case .backspace: handleBackspace()
        default: break
        }
    }

    func keyUp(_ key: KeySpec) {
        guard key.type == .shift else { return }
        shiftHeld = false
        if shiftChorded {
            shiftChorded = false
            shift = .off
            keyboardView.shiftState = shift
            refreshState()
        }
    }

    func character(_ key: KeySpec, text: String) {
        commitCharacter(text)
    }

    func special(_ key: KeySpec) {
        switch key.type {
        case .space: handleSpace()
        case .enter: handleEnter()
        case .symbols: setLayer(.symbols)
        case .symbolsMore: setLayer(.symbolsMore)
        case .letters: setLayer(.letters)
        case .language: switchLanguage()
        case .emoji: showEmojiPanel()
        case .char, .shift, .backspace, .globe: break
        }
    }

    func backspaceRepeat() {
        feedback(.backspace)
        handleBackspace()
    }

    func switchKeyboardRequested() {
        advanceToNextInputMode()
    }

    func cursorMove(_ steps: Int) {
        proxy.adjustTextPosition(byCharacterOffset: steps)
        autoSpacePending = false
        lastAutocorrect = nil
        lastAction = .none
    }

    func suggestionPicked(_ text: String) {
        feedback(.char)
        if lastAction == .gesture, let gestureWord = lastGestureWord {
            if (proxy.documentContextBeforeInput ?? "").hasSuffix(gestureWord + " ") {
                for _ in 0..<(gestureWord.count + 1) { proxy.deleteBackward() }
                proxy.insertText(text + " ")
            }
            learn(nil, text)
            lastGestureWord = nil
            gestureAlternatives = []
            autoSpacePending = true
            lastAction = .pick
            refreshState()
            return
        }
        let before = proxy.documentContextBeforeInput ?? ""
        let typed = TextRules.currentWord(before)
        let previous = TextRules.previousWord(before)
        for _ in 0..<typed.count { proxy.deleteBackward() }
        proxy.insertText(text + " ")
        learn(previous, text)
        autoSpacePending = true
        lastAutocorrect = nil
        lastAction = .pick
        if shift == .once { shift = .off }
        refreshState()
    }

    func toolbarAction(_ action: ToolbarAction) {
        feedback(.char)
        switch action {
        case .emoji: showEmojiPanel()
        case .convert: convertText()
        case .settings: openContainerApp()
        case .hide: dismissKeyboard()
        case .clipboard: showClipboardPanel()
        }
    }

    func gesture(_ path: [CGPoint]) {
        let layout = currentLayout
        guard let dictionary = dictionaries[layout.dictionary] else {
            preloadDictionary(layout.dictionary)
            return
        }
        let decoder = GestureDecoder(
            dictionary: dictionary,
            normalizer: KeyNormalizer.forDictionary(layout.dictionary),
            keyCenters: keyboardView.gestureKeyCenters(),
            keyWidth: keyboardView.gestureKeyWidth(),
            user: userDictionary(layout.dictionary)
        )
        var words: [String] = []
        for candidate in decoder.decode(path) {
            let word = applyShift(candidate.word)
            if !words.contains(word) { words.append(word) }
        }
        guard let word = words.first else { return }
        let before = proxy.documentContextBeforeInput ?? ""
        let needsSpace: Bool
        if let last = before.last { needsSpace = !last.isWhitespace && !"([{«\"'".contains(last) } else { needsSpace = false }
        proxy.insertText((needsSpace ? " " : "") + word + " ")
        let previous: String? = needsSpace ? TextRules.currentWord(before) : TextRules.previousWord(before)
        learn(previous?.isEmpty == true ? nil : previous, word)
        lastGestureWord = word
        gestureAlternatives = Array(words.dropFirst().prefix(2))
        autoSpacePending = true
        lastAutocorrect = nil
        lastAction = .gesture
        if shift == .once {
            shift = .off
            shiftIsAuto = false
        }
        refreshState()
    }

    private func applyShift(_ word: String) -> String {
        switch shift {
        case .off: return word
        case .once: return word.prefix(1).uppercased() + word.dropFirst()
        case .locked: return word.uppercased()
        }
    }

    // MARK: ClipboardPanelDelegate

    func clipPaste(_ text: String) {
        proxy.insertText(text)
        lastAction = .character
        autoSpacePending = false
    }

    func clipRemove(_ text: String) {
        clipboardHistory.remove(text)
        clipboardView.setItems(clipboardHistory.items)
    }

    func clipTogglePin(_ text: String) {
        clipboardHistory.togglePin(text)
        clipboardView.setItems(clipboardHistory.items)
    }

    func clipClear() {
        clipboardHistory.clearUnpinned()
        clipboardView.setItems(clipboardHistory.items)
    }

    func clipClose() {
        showKeyboardPanel()
        refreshState()
    }

    func clipFeedback() { feedback(.char) }

    // MARK: EmojiPanelDelegate

    func emojiSelected(_ emoji: String) {
        proxy.insertText(emoji)
        settings.recentEmojis = [emoji] + settings.recentEmojis.filter { $0 != emoji }
        lastAction = .character
        autoSpacePending = false
    }

    func emojiBackspace() { handleBackspace() }

    func emojiSpace() {
        proxy.insertText(" ")
        lastAction = .space
    }

    func emojiClose() {
        showKeyboardPanel()
        refreshState()
    }

    func emojiFeedback() { feedback(.char) }

    // MARK: Editing

    private func handleShiftDown() {
        let now = ProcessInfo.processInfo.systemUptime
        switch shift {
        case .off: shift = .once
        case .once: shift = (!shiftIsAuto && now - lastShiftTap < Self.doubleTapInterval) ? .locked : .off
        case .locked: shift = .off
        }
        shiftIsAuto = false
        shiftHeld = true
        shiftChorded = false
        lastShiftTap = now
        keyboardView.shiftState = shift
    }

    private func commitCharacter(_ rawText: String) {
        let text = layer == .letters && shift != .off ? TextRules.uppercaseKeyText(rawText) : rawText
        let before = proxy.documentContextBeforeInput ?? ""
        let previousChar = before.last
        if settings.apostropheFix, let previousChar,
           let replacement = TextRules.apostropheReplacement(layoutId: currentLayout.id, previous: previousChar, typed: text) {
            proxy.deleteBackward()
            proxy.insertText(replacement)
        } else if autoSpacePending && previousChar == " " && TextRules.attachesToPreviousWord(text) {
            proxy.deleteBackward()
            proxy.insertText(text + " ")
        } else {
            let isSeparator = !text.contains { TextRules.isWordChar($0) || $0.isNumber }
            if isSeparator { completeWord(before, applyAutocorrect: false) }
            proxy.insertText(text)
        }
        autoSpacePending = false
        lastAutocorrect = nil
        lastAction = .character
        if layer == .letters {
            if shiftHeld {
                shiftChorded = true
            } else if shift == .once {
                shift = .off
                shiftIsAuto = false
            }
        }
        keyboardView.shiftState = shift
        refreshState()
    }

    @discardableResult
    private func completeWord(_ before: String, applyAutocorrect: Bool) -> String? {
        let word = TextRules.currentWord(before)
        guard !word.isEmpty else { return nil }
        let previous = TextRules.previousWord(before)
        var finalWord = word
        if applyAutocorrect, settings.autoCorrect, suggestionsAllowed, layer == .letters,
           let correction = engine(currentLayout.dictionary)?.autocorrection(word, previousWord: previous), correction != word {
            for _ in 0..<word.count { proxy.deleteBackward() }
            proxy.insertText(correction)
            lastAutocorrect = (word, correction)
            finalWord = correction
        }
        learn(previous, finalWord)
        return finalWord
    }

    private func learn(_ previous: String?, _ word: String) {
        guard learningAllowed else { return }
        userDictionary(currentLayout.dictionary).learn(previous: previous, word: word)
        scheduleSave()
    }

    private func handleSpace() {
        let now = ProcessInfo.processInfo.systemUptime
        let before = proxy.documentContextBeforeInput ?? ""
        let chars = Array(before.suffix(2))
        let doubleSpace = settings.doubleSpacePeriod && (lastAction == .space || lastAction == .autocorrect) &&
            now - lastSpaceTime < Self.doubleSpaceInterval &&
            chars.count == 2 && chars[1] == " " && (chars[0].isLetter || chars[0].isNumber || "\"')»”".contains(chars[0]))
        var autocorrected = false
        if doubleSpace {
            proxy.deleteBackward()
            proxy.insertText(". ")
            lastSpaceTime = 0
        } else {
            lastAutocorrect = nil
            completeWord(before, applyAutocorrect: true)
            autocorrected = lastAutocorrect != nil
            proxy.insertText(" ")
            lastSpaceTime = now
        }
        autoSpacePending = false
        lastAction = autocorrected ? .autocorrect : .space
        if layer == .symbols || layer == .symbolsMore { setLayer(.letters) }
        refreshState()
    }

    private func handleEnter() {
        completeWord(proxy.documentContextBeforeInput ?? "", applyAutocorrect: false)
        proxy.insertText("\n")
        autoSpacePending = false
        lastAutocorrect = nil
        lastAction = .character
        refreshState()
    }

    private func handleBackspace() {
        if lastAction == .autocorrect, let revert = lastAutocorrect,
           (proxy.documentContextBeforeInput ?? "").hasSuffix(revert.corrected + " ") {
            for _ in 0..<(revert.corrected.count + 1) { proxy.deleteBackward() }
            proxy.insertText(revert.original)
            userDictionary(currentLayout.dictionary).learn(previous: nil, word: revert.original)
            lastAutocorrect = nil
            lastAction = .delete
            refreshState()
            return
        }
        proxy.deleteBackward()
        autoSpacePending = false
        lastAutocorrect = nil
        lastAction = .delete
        refreshState()
    }

    private func switchLanguage() {
        let enabled = settings.enabledLayouts
        guard enabled.count > 1 else { return }
        let index = enabled.firstIndex(of: settings.currentLayout) ?? 0
        let next = enabled[(index + 1) % enabled.count]
        previousDictionary = currentLayout.dictionary
        settings.currentLayout = next
        preloadDictionary(layout(next).dictionary)
        layer = .letters
        rebuildKeyboard()
        refreshState()
    }

    private func convertText() {
        guard let script = UzbekTransliterator.script(forLayout: currentLayout.id) else { return }
        if let selected = proxy.selectedText, !selected.isEmpty {
            proxy.insertText(UzbekTransliterator.convert(selected, to: script))
        } else {
            let before = proxy.documentContextBeforeInput ?? ""
            let after = proxy.documentContextAfterInput ?? ""
            guard !before.isEmpty || !after.isEmpty else { return }
            if !after.isEmpty { proxy.adjustTextPosition(byCharacterOffset: after.count) }
            for _ in 0..<(before.count + after.count) { proxy.deleteBackward() }
            let convertedBefore = UzbekTransliterator.convert(before, to: script)
            let convertedAfter = UzbekTransliterator.convert(after, to: script)
            proxy.insertText(convertedBefore + convertedAfter)
            if !convertedAfter.isEmpty { proxy.adjustTextPosition(byCharacterOffset: -convertedAfter.count) }
        }
        lastAction = .none
        refreshState()
    }

    /// Keyboard extensions cannot use UIApplication.shared; find the application in the responder chain.
    /// iOS 18 ignores the deprecated `openURL:`, so `openURL:options:completionHandler:` is called when present.
    private func openContainerApp() {
        guard let url = URL(string: "uzkey://settings"), let applicationClass = NSClassFromString("UIApplication") else { return }
        var responder: UIResponder? = self
        while let current = responder {
            if current.isKind(of: applicationClass) {
                let modern = NSSelectorFromString("openURL:options:completionHandler:")
                if current.responds(to: modern), let implementation = current.method(for: modern) {
                    typealias OpenURL = @convention(c) (AnyObject, Selector, NSURL, NSDictionary, (@convention(block) (Bool) -> Void)?) -> Void
                    unsafeBitCast(implementation, to: OpenURL.self)(current, modern, url as NSURL, NSDictionary(), nil)
                    return
                }
                let legacy = NSSelectorFromString("openURL:")
                if current.responds(to: legacy) { current.perform(legacy, with: url) }
                return
            }
            responder = current.next
        }
    }
}
