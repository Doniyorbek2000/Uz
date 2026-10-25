package uz.uzkey.keyboard.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import java.text.BreakIterator
import kotlin.math.abs
import uz.uzkey.keyboard.core.BuildOptions
import uz.uzkey.keyboard.core.FieldKind
import uz.uzkey.keyboard.core.GestureDecoder
import uz.uzkey.keyboard.core.GesturePoint
import uz.uzkey.keyboard.core.KeyNormalizer
import uz.uzkey.keyboard.core.KeySpec
import uz.uzkey.keyboard.core.KeyType
import uz.uzkey.keyboard.core.KeyboardBuilder
import uz.uzkey.keyboard.core.LayoutIds
import uz.uzkey.keyboard.core.LayoutSpec
import uz.uzkey.keyboard.core.SuggestionEngine
import uz.uzkey.keyboard.core.Suggestions
import uz.uzkey.keyboard.core.TextRules
import uz.uzkey.keyboard.core.UzbekTransliterator
import uz.uzkey.keyboard.settings.KeyboardSettings
import uz.uzkey.keyboard.settings.SettingsActivity

class UzKeyService :
    InputMethodService(),
    KeyboardView.Listener,
    EmojiPanelView.Listener,
    ClipboardPanelView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private enum class Layer { LETTERS, SYMBOLS, SYMBOLS_MORE, NUMPAD }

    private enum class LastAction { NONE, CHARACTER, SPACE, PICK, AUTOCORRECT, DELETE, GESTURE }

    private lateinit var settings: KeyboardSettings
    private lateinit var store: DictionaryStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshState() }

    private var root: FrameLayout? = null
    private var keyboardView: KeyboardView? = null
    private var emojiView: EmojiPanelView? = null
    private var clipboardView: ClipboardPanelView? = null
    private lateinit var clipboardHistory: ClipboardHistory
    private var clipboardManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }
    private var isPasswordField = false
    private var lastGestureWord: String? = null
    private var gestureAlternatives: List<String> = emptyList()

    private var layer = Layer.LETTERS
    private var shift = ShiftState.OFF
    private var shiftIsAuto = false
    private var shiftHeld = false
    private var shiftChorded = false
    private var lastShiftTap = 0L

    private var fieldKind = FieldKind.TEXT
    private var inputType = InputType.TYPE_NULL
    private var suggestionsAllowed = true
    private var learningAllowed = true
    private var autoCapAllowed = true
    private var enterAction = EditorInfo.IME_ACTION_NONE
    private var lastAction = LastAction.NONE
        set(value) {
            field = value
            lastEditTime = SystemClock.uptimeMillis()
        }
    private var lastEditTime = 0L
    private var lastSpaceTime = 0L
    private var autoSpacePending = false
    private var lastAutocorrect: Pair<String, String>? = null
    private var userDictionaryEpoch = 0L
    private var needsRebuild = true

    private val currentLayout: LayoutSpec get() = store.layout(settings.currentLayout)
    private val engine: SuggestionEngine? get() = store.engine(currentLayout.dictionary)

    // ---- Lifecycle ------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        store = DictionaryStore(this)
        store.onDictionaryLoaded = { scheduleRefresh() }
        userDictionaryEpoch = settings.userDictionaryEpoch
        settings.preferences.registerOnSharedPreferenceChangeListener(this)
        settings.enabledLayouts.forEach { store.preload(store.layout(it).dictionary) }
        clipboardHistory = ClipboardHistory(this)
        clipboardManager = getSystemService(ClipboardManager::class.java)
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        settings.preferences.unregisterOnSharedPreferenceChangeListener(this)
        mainHandler.removeCallbacks(refreshRunnable)
        store.shutdown()
        soundPlayer.release()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val keyboard = KeyboardView(this, this)
        val emoji = EmojiPanelView(this, this).apply { visibility = View.GONE }
        val clips = ClipboardPanelView(this, this).apply { visibility = View.GONE }
        val container = FrameLayout(this).apply {
            addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(emoji, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(clips, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnApplyWindowInsetsListener { _, insets ->
                val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                keyboard.bottomInset = bottom
                insets
            }
        }
        root = container
        keyboardView = keyboard
        emojiView = emoji
        clipboardView = clips
        needsRebuild = true
        return container
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        needsRebuild = true
        super.onConfigurationChanged(newConfig)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        configureForField(info)
        if (settings.userDictionaryEpoch != userDictionaryEpoch) {
            userDictionaryEpoch = settings.userDictionaryEpoch
            store.clearLearnedWords()
        }
        if (!restarting) {
            layer = if (fieldKind == FieldKind.NUMBER || fieldKind == FieldKind.PHONE) Layer.NUMPAD else Layer.LETTERS
            shift = ShiftState.OFF
            shiftIsAuto = false
            lastAction = LastAction.NONE
            autoSpacePending = false
            lastAutocorrect = null
        }
        showKeyboardPanel()
        rebuildKeyboard()
        refreshState()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.cancelAllPointers()
        store.saveAll()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Updates shortly after our own edit are echoes of it; anything else means the user moved the cursor.
        val ownEdit = SystemClock.uptimeMillis() - lastEditTime < OWN_EDIT_WINDOW_MS
        if (!ownEdit || newSelStart != newSelEnd) {
            autoSpacePending = false
            lastAutocorrect = null
            lastAction = LastAction.NONE
            lastEditTime = 0L
        }
        scheduleRefresh()
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        if (key == KeyboardSettings.KEY_SOUND && !settings.keySound) soundPlayer.release()
        if (key == KeyboardSettings.KEY_USER_DICT_EPOCH) {
            userDictionaryEpoch = settings.userDictionaryEpoch
            store.clearLearnedWords()
            return
        }
        if (key != null && key in KeyboardSettings.VOLATILE_KEYS) return
        if (key == KeyboardSettings.KEY_CLIPBOARD_HISTORY && !settings.clipboardHistory) clipboardHistory.clearAll()
        needsRebuild = true
        if (key == KeyboardSettings.KEY_ENABLED_LAYOUTS) settings.enabledLayouts.forEach { store.preload(store.layout(it).dictionary) }
        if (keyboardView?.isShown == true) {
            rebuildKeyboard()
            refreshState()
        }
    }

    // ---- Field configuration --------------------------------------------------------------

    private fun configureForField(info: EditorInfo) {
        inputType = info.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        fieldKind = when (inputClass) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
            InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
        val isPassword = inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noSuggestionsFlag = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val textLike = inputClass == InputType.TYPE_CLASS_TEXT && fieldKind == FieldKind.TEXT &&
            variation != InputType.TYPE_TEXT_VARIATION_FILTER
        isPasswordField = isPassword
        suggestionsAllowed = textLike && !isPassword && !noSuggestionsFlag
        learningAllowed = textLike && !isPassword && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        autoCapAllowed = textLike && !isPassword
        enterAction = if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            EditorInfo.IME_ACTION_NONE
        } else {
            info.imeOptions and EditorInfo.IME_MASK_ACTION
        }
    }

    private fun enterIcon(): Icon = when (enterAction) {
        EditorInfo.IME_ACTION_SEARCH -> Icon.SEARCH
        EditorInfo.IME_ACTION_SEND -> Icon.SEND
        EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icon.NEXT
        EditorInfo.IME_ACTION_DONE -> Icon.DONE
        else -> Icon.RETURN
    }

    // ---- Keyboard building ----------------------------------------------------------------

    private fun rebuildKeyboard() {
        val view = keyboardView ?: return
        needsRebuild = false
        val theme = KeyboardTheme.resolve(this, settings.theme)
        view.theme = theme
        emojiView?.theme = theme
        clipboardView?.theme = theme
        val configuration = resources.configuration
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val tablet = configuration.smallestScreenWidthDp >= 600
        view.baseRowHeightDp = when {
            tablet && landscape -> 50f
            tablet -> 60f
            landscape -> 40f
            else -> 54f
        }
        view.stripHeightDp = if (landscape && !tablet) 38f else 46f
        view.gestureTyping = settings.gestureTyping && suggestionsAllowed
        view.voiceAvailable = findVoiceInputMethod() != null
        view.heightScale = settings.keyHeight.scale
        view.showKeyPopup = settings.keyPopup
        view.enterIcon = enterIcon()
        val language = currentLayout
        val spec = when (layer) {
            Layer.LETTERS -> language
            Layer.SYMBOLS -> store.layout(LayoutIds.SYMBOLS)
            Layer.SYMBOLS_MORE -> store.layout(LayoutIds.SYMBOLS_MORE)
            Layer.NUMPAD -> store.layout(LayoutIds.NUMPAD)
        }
        val options = BuildOptions(
            numberRow = settings.numberRow,
            numberHints = settings.numberHints,
            fieldKind = fieldKind,
            showLanguageKey = settings.enabledLayouts.size > 1,
        )
        view.isLetterLayer = layer == Layer.LETTERS
        view.spaceLabel = if (layer == Layer.LETTERS || layer == Layer.SYMBOLS) language.spaceLabel else ""
        view.languageLabel = language.shortLabel
        view.setKeys(KeyboardBuilder.build(spec, layer == Layer.LETTERS, options))
        view.shiftState = shift
    }

    private fun setLayer(newLayer: Layer) {
        if (layer == newLayer) return
        layer = newLayer
        if (newLayer != Layer.LETTERS) {
            shift = ShiftState.OFF
            shiftIsAuto = false
        }
        rebuildKeyboard()
        scheduleRefresh()
    }

    private fun showKeyboardPanel() {
        emojiView?.visibility = View.GONE
        clipboardView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    private fun showClipboardPanel() {
        val keyboard = keyboardView ?: return
        val clips = clipboardView ?: return
        captureClip()
        clips.panelHeight = keyboard.height
        clips.setItems(clipboardHistory.items())
        keyboard.cancelAllPointers()
        keyboard.visibility = View.INVISIBLE
        clips.visibility = View.VISIBLE
    }

    private fun captureClip() {
        if (!settings.clipboardHistory || isPasswordField) return
        val clip = try {
            clipboardManager?.primaryClip
        } catch (error: SecurityException) {
            null
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) {
            return
        }
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        clipboardHistory.add(text)
    }

    /** An enabled voice-typing input method (e.g. Google voice typing), if the user has one. */
    private fun findVoiceInputMethod(): Pair<String, InputMethodSubtype>? {
        val imm = getSystemService(InputMethodManager::class.java) ?: return null
        for (info in imm.enabledInputMethodList) {
            if (info.packageName == packageName) continue
            for (subtype in imm.getEnabledInputMethodSubtypeList(info, true)) {
                if (subtype.mode == "voice") return info.id to subtype
            }
        }
        return null
    }

    private fun switchToVoice() {
        val (id, subtype) = findVoiceInputMethod() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchInputMethod(id, subtype)
        } else {
            val token = window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            getSystemService(InputMethodManager::class.java)?.setInputMethodAndSubtype(token, id, subtype)
        }
    }

    private fun showEmojiPanel() {
        val keyboard = keyboardView ?: return
        val emoji = emojiView ?: return
        emoji.panelHeight = keyboard.height
        emoji.setCategories(settings.recentEmojis, store.emojiCategories())
        emoji.scrollToStart()
        keyboard.cancelAllPointers()
        keyboard.visibility = View.INVISIBLE
        emoji.visibility = View.VISIBLE
    }

    // ---- State refresh (suggestions + auto shift) ----------------------------------------

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    private fun refreshState() {
        val view = keyboardView ?: return
        if (needsRebuild) rebuildKeyboard()
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(64, 0) ?: ""
        updateAutoShift(ic, before)
        view.shiftState = shift
        val convertAvailable = layer == Layer.LETTERS && UzbekTransliterator.scriptForLayout(currentLayout.id) != null
        if (!settings.showSuggestions || !suggestionsAllowed || layer == Layer.NUMPAD) {
            view.setSuggestions(Suggestions.NONE, convertAvailable)
            return
        }
        val selected = ic?.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            view.setSuggestions(Suggestions.NONE, convertAvailable)
            return
        }
        val gestureWord = lastGestureWord
        if (lastAction == LastAction.GESTURE && gestureWord != null && gestureAlternatives.isNotEmpty()) {
            view.setSuggestions(Suggestions("", listOf(gestureWord) + gestureAlternatives, true), convertAvailable)
            return
        }
        val typed = TextRules.currentWord(before)
        val engine = engine
        val suggestions = when {
            engine == null -> if (typed.isEmpty()) Suggestions.NONE else Suggestions(typed, emptyList(), true)
            typed.isEmpty() -> {
                val endsWithSpace = before.isNotEmpty() && before.last() == ' '
                if (endsWithSpace) Suggestions("", engine.predictNext(TextRules.previousWord(before)), true) else Suggestions.NONE
            }
            else -> engine.suggest(typed, TextRules.previousWord(before))
        }
        view.setSuggestions(suggestions, convertAvailable)
    }

    private fun updateAutoShift(ic: InputConnection?, before: CharSequence) {
        if (layer != Layer.LETTERS || shift == ShiftState.LOCKED || shiftHeld) return
        val wantsCaps = settings.autoCapitalize && autoCapAllowed && ic != null && run {
            val fieldCaps = inputType and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
            if (fieldCaps != 0) ic.getCursorCapsMode(inputType) != 0 else TextRules.isSentenceStart(before)
        }
        if (wantsCaps && shift == ShiftState.OFF) {
            shift = ShiftState.ONCE
            shiftIsAuto = true
        } else if (!wantsCaps && shift == ShiftState.ONCE && shiftIsAuto) {
            shift = ShiftState.OFF
            shiftIsAuto = false
        }
    }

    // ---- Feedback -------------------------------------------------------------------------

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }
    private val soundPlayer by lazy { KeySoundPlayer(this) }

    private fun feedback(type: KeyType, label: String? = null) {
        if (settings.vibration) {
            val vibrator = vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(14L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }
        if (settings.keySound) {
            soundPlayer.play(settings.soundPack, type, label, settings.soundVolume / 100f)
        }
    }

    // ---- KeyboardView.Listener ------------------------------------------------------------

    override fun onKeyDown(key: KeySpec) {
        feedback(key.type, key.label)
        when (key.type) {
            KeyType.SHIFT -> handleShiftDown()
            KeyType.BACKSPACE -> handleBackspace()
            else -> Unit
        }
    }

    override fun onKeyUp(key: KeySpec) {
        if (key.type != KeyType.SHIFT) return
        shiftHeld = false
        if (shiftChorded) {
            shiftChorded = false
            shift = ShiftState.OFF
            keyboardView?.shiftState = shift
            scheduleRefresh()
        }
    }

    override fun onCharacter(key: KeySpec, text: String) {
        commitCharacter(text)
    }

    override fun onSpecial(key: KeySpec) {
        when (key.type) {
            KeyType.SPACE -> handleSpace()
            KeyType.ENTER -> handleEnter()
            KeyType.SYMBOLS -> setLayer(Layer.SYMBOLS)
            KeyType.SYMBOLS_MORE -> setLayer(Layer.SYMBOLS_MORE)
            KeyType.LETTERS -> setLayer(Layer.LETTERS)
            KeyType.LANGUAGE -> switchLanguage()
            KeyType.EMOJI -> showEmojiPanel()
            KeyType.CHAR, KeyType.SHIFT, KeyType.BACKSPACE -> Unit
        }
    }

    override fun onBackspaceRepeat() {
        feedback(KeyType.BACKSPACE)
        handleBackspace()
    }

    override fun onSwitchKeyboardRequested() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(steps)) { sendDownUpKeyEvents(keyCode) }
        autoSpacePending = false
        lastAutocorrect = null
        lastAction = LastAction.NONE
        if (ic.getSelectedText(0).isNullOrEmpty()) scheduleRefresh()
    }

    override fun onSuggestionPicked(text: String) {
        val ic = currentInputConnection ?: return
        feedback(KeyType.CHAR)
        val gestureWord = lastGestureWord
        if (lastAction == LastAction.GESTURE && gestureWord != null) {
            ic.beginBatchEdit()
            val before = ic.getTextBeforeCursor(gestureWord.length + 1, 0)?.toString()
            if (before == "$gestureWord ") {
                ic.deleteSurroundingText(gestureWord.length + 1, 0)
                ic.commitText("$text ", 1)
            }
            ic.endBatchEdit()
            learn(null, text)
            lastGestureWord = null
            gestureAlternatives = emptyList()
            autoSpacePending = true
            lastAction = LastAction.PICK
            scheduleRefresh()
            return
        }
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val typed = TextRules.currentWord(before)
        val previous = TextRules.previousWord(before)
        if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        learn(previous, text)
        autoSpacePending = true
        lastAutocorrect = null
        lastAction = LastAction.PICK
        if (shift == ShiftState.ONCE) shift = ShiftState.OFF
        scheduleRefresh()
    }

    override fun onToolbarAction(action: ToolbarAction) {
        feedback(KeyType.CHAR)
        when (action) {
            ToolbarAction.EMOJI -> showEmojiPanel()
            ToolbarAction.CONVERT -> convertText()
            ToolbarAction.SETTINGS -> {
                val intent = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            }
            ToolbarAction.HIDE -> requestHideSelf(0)
            ToolbarAction.CLIPBOARD -> showClipboardPanel()
            ToolbarAction.VOICE -> switchToVoice()
        }
    }

    override fun onGesture(path: List<GesturePoint>) {
        val view = keyboardView ?: return
        val ic = currentInputConnection ?: return
        val layout = currentLayout
        val dictionary = store.dictionary(layout.dictionary) ?: return
        val decoder = GestureDecoder(
            dictionary,
            KeyNormalizer.forDictionary(layout.dictionary),
            view.gestureKeyCenters(),
            view.gestureKeyWidth(),
            store.user(layout.dictionary),
        )
        val words = decoder.decode(path).map { applyShift(it.word) }.distinct()
        if (words.isEmpty()) return
        val word = words.first()
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val previousChar = before.lastOrNull()
        val needsSpace = previousChar != null && !previousChar.isWhitespace() && previousChar !in "([{«\"'"
        ic.commitText((if (needsSpace) " " else "") + word + " ", 1)
        ic.endBatchEdit()
        val previous = if (needsSpace) TextRules.currentWord(before).ifEmpty { null } else TextRules.previousWord(before)
        learn(previous, word)
        lastGestureWord = word
        gestureAlternatives = words.drop(1).take(2)
        autoSpacePending = true
        lastAutocorrect = null
        lastAction = LastAction.GESTURE
        if (shift == ShiftState.ONCE) {
            shift = ShiftState.OFF
            shiftIsAuto = false
        }
        scheduleRefresh()
    }

    private fun applyShift(word: String): String = when (shift) {
        ShiftState.OFF -> word
        ShiftState.ONCE -> word.substring(0, 1).uppercase() + word.substring(1)
        ShiftState.LOCKED -> word.uppercase()
    }

    // ---- ClipboardPanelView.Listener ------------------------------------------------------

    override fun onClipPaste(text: String) {
        currentInputConnection?.commitText(text, 1)
        lastAction = LastAction.CHARACTER
        autoSpacePending = false
    }

    override fun onClipRemove(text: String) {
        clipboardHistory.remove(text)
        clipboardView?.setItems(clipboardHistory.items())
    }

    override fun onClipTogglePin(text: String) {
        clipboardHistory.togglePin(text)
        clipboardView?.setItems(clipboardHistory.items())
    }

    override fun onClipClear() {
        clipboardHistory.clearUnpinned()
        clipboardView?.setItems(clipboardHistory.items())
    }

    override fun onClipClose() {
        showKeyboardPanel()
        scheduleRefresh()
    }

    override fun onClipFeedback() {
        feedback(KeyType.CHAR)
    }

    // ---- EmojiPanelView.Listener ----------------------------------------------------------

    override fun onEmoji(emoji: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(emoji, 1)
        settings.recentEmojis = listOf(emoji) + settings.recentEmojis.filter { it != emoji }
        lastAction = LastAction.CHARACTER
        autoSpacePending = false
    }

    override fun onEmojiBackspace() {
        handleBackspace()
    }

    override fun onEmojiSpace() {
        currentInputConnection?.commitText(" ", 1)
        lastAction = LastAction.SPACE
    }

    override fun onEmojiClose() {
        showKeyboardPanel()
        scheduleRefresh()
    }

    override fun onEmojiKeyFeedback() {
        feedback(KeyType.CHAR)
    }

    // ---- Editing actions ------------------------------------------------------------------

    private fun handleShiftDown() {
        val now = SystemClock.uptimeMillis()
        shift = when (shift) {
            ShiftState.OFF -> ShiftState.ONCE
            ShiftState.ONCE -> if (!shiftIsAuto && now - lastShiftTap < DOUBLE_TAP_MS) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        shiftIsAuto = false
        shiftHeld = true
        shiftChorded = false
        lastShiftTap = now
        keyboardView?.shiftState = shift
    }

    private fun commitCharacter(rawText: String) {
        val ic = currentInputConnection ?: return
        val text = if (layer == Layer.LETTERS && shift != ShiftState.OFF) TextRules.uppercaseKeyText(rawText) else rawText
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val layout = currentLayout
        val previousChar = before.lastOrNull()

        val apostrophe = if (settings.apostropheFix && previousChar != null) {
            TextRules.apostropheReplacement(layout.id, previousChar, text)
        } else {
            null
        }
        when {
            apostrophe != null -> {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(apostrophe, 1)
            }
            autoSpacePending && previousChar == ' ' && TextRules.attachesToPreviousWord(text) -> {
                ic.deleteSurroundingText(1, 0)
                ic.commitText("$text ", 1)
            }
            else -> {
                val isSeparator = text.none { TextRules.isWordChar(it) || it.isDigit() }
                if (isSeparator) completeWord(ic, before, applyAutocorrect = false)
                ic.commitText(text, 1)
            }
        }
        ic.endBatchEdit()

        autoSpacePending = false
        lastAutocorrect = null
        lastAction = LastAction.CHARACTER
        if (layer == Layer.LETTERS) {
            if (shiftHeld) {
                shiftChorded = true
            } else if (shift == ShiftState.ONCE) {
                shift = ShiftState.OFF
                shiftIsAuto = false
            }
        }
        keyboardView?.shiftState = shift
        scheduleRefresh()
    }

    /** Learns the word ending at the cursor and optionally autocorrects it. Returns the final word. */
    private fun completeWord(ic: InputConnection, before: CharSequence, applyAutocorrect: Boolean): String? {
        val word = TextRules.currentWord(before)
        if (word.isEmpty()) return null
        val previous = TextRules.previousWord(before)
        var finalWord = word
        if (applyAutocorrect && settings.autoCorrect && suggestionsAllowed && layer == Layer.LETTERS) {
            val correction = engine?.autocorrection(word, previous)
            if (correction != null && correction != word) {
                ic.deleteSurroundingText(word.length, 0)
                ic.commitText(correction, 1)
                lastAutocorrect = word to correction
                finalWord = correction
            }
        }
        learn(previous, finalWord)
        return finalWord
    }

    private fun learn(previous: String?, word: String) {
        if (!learningAllowed || !settings.learnWords || !settings.showSuggestions) return
        store.user(currentLayout.dictionary).learn(previous, word)
        store.scheduleSave()
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val doubleSpace = settings.doubleSpacePeriod && (lastAction == LastAction.SPACE || lastAction == LastAction.AUTOCORRECT) &&
            now - lastSpaceTime < DOUBLE_SPACE_MS &&
            before.length >= 2 && before[before.length - 1] == ' ' &&
            (before[before.length - 2].isLetterOrDigit() || before[before.length - 2] in "\"')»”")
        var autocorrected = false
        if (doubleSpace) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTime = 0L
        } else {
            lastAutocorrect = null
            completeWord(ic, before, applyAutocorrect = true)
            autocorrected = lastAutocorrect != null
            ic.commitText(" ", 1)
            lastSpaceTime = now
        }
        ic.endBatchEdit()
        autoSpacePending = false
        lastAction = if (autocorrected) LastAction.AUTOCORRECT else LastAction.SPACE
        if (layer == Layer.SYMBOLS || layer == Layer.SYMBOLS_MORE) setLayer(Layer.LETTERS)
        scheduleRefresh()
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        completeWord(ic, before, applyAutocorrect = false)
        ic.endBatchEdit()
        when (enterAction) {
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS -> ic.performEditorAction(enterAction)
            else -> ic.commitText("\n", 1)
        }
        autoSpacePending = false
        lastAutocorrect = null
        lastAction = LastAction.CHARACTER
        scheduleRefresh()
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        val revert = lastAutocorrect
        if (lastAction == LastAction.AUTOCORRECT && revert != null) {
            val (original, corrected) = revert
            val before = ic.getTextBeforeCursor(corrected.length + 1, 0)?.toString()
            if (before == "$corrected ") {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(corrected.length + 1, 0)
                ic.commitText(original, 1)
                ic.endBatchEdit()
                store.user(currentLayout.dictionary).learn(null, original)
                lastAutocorrect = null
                lastAction = LastAction.DELETE
                scheduleRefresh()
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val before = ic.getTextBeforeCursor(16, 0)
            if (before.isNullOrEmpty()) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            } else {
                ic.deleteSurroundingText(lastGraphemeLength(before), 0)
            }
        }
        autoSpacePending = false
        lastAutocorrect = null
        lastAction = LastAction.DELETE
        scheduleRefresh()
    }

    private fun lastGraphemeLength(text: CharSequence): Int {
        val iterator = BreakIterator.getCharacterInstance()
        val string = text.toString()
        iterator.setText(string)
        val end = iterator.last()
        val start = iterator.previous()
        return if (start == BreakIterator.DONE) 1 else (end - start).coerceAtLeast(1)
    }

    private fun switchLanguage() {
        val enabled = settings.enabledLayouts
        if (enabled.size <= 1) return
        val next = enabled[(enabled.indexOf(settings.currentLayout) + 1) % enabled.size]
        settings.currentLayout = next
        store.preload(store.layout(next).dictionary)
        layer = Layer.LETTERS
        rebuildKeyboard()
        scheduleRefresh()
    }

    private fun convertText() {
        val ic = currentInputConnection ?: return
        val script = UzbekTransliterator.scriptForLayout(currentLayout.id) ?: return
        val selected = ic.getSelectedText(0)
        ic.beginBatchEdit()
        if (!selected.isNullOrEmpty()) {
            ic.commitText(UzbekTransliterator.convert(selected.toString(), script), 1)
        } else {
            val before = ic.getTextBeforeCursor(MAX_CONVERT_CHARS, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(MAX_CONVERT_CHARS, 0)?.toString().orEmpty()
            if (before.isNotEmpty() || after.isNotEmpty()) {
                ic.deleteSurroundingText(before.length, after.length)
                ic.commitText(UzbekTransliterator.convert(before, script), 1)
                if (after.isNotEmpty()) ic.commitText(UzbekTransliterator.convert(after, script), 0)
            }
        }
        ic.endBatchEdit()
        lastAction = LastAction.NONE
        scheduleRefresh()
    }

    companion object {
        private const val DOUBLE_TAP_MS = 380L
        private const val DOUBLE_SPACE_MS = 800L
        private const val MAX_CONVERT_CHARS = 10_000
        private const val OWN_EDIT_WINDOW_MS = 250L
    }
}
