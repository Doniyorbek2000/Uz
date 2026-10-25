package uz.uzkey.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import uz.uzkey.keyboard.core.LayoutIds

enum class ThemeChoice(val id: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark"), BLACK("black"), BLUE("blue");

    companion object {
        fun from(id: String?): ThemeChoice = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class KeyHeight(val id: String, val scale: Float) {
    SMALL("small", 0.88f), NORMAL("normal", 1f), LARGE("large", 1.14f);

    companion object {
        fun from(id: String?): KeyHeight = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/**
 * Preferences shared by the settings screen and the input method service.
 * Stored in device-protected storage so the keyboard keeps working before the first unlock.
 */
class KeyboardSettings(context: Context) {

    val preferences: SharedPreferences = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabledLayouts: List<String>
        get() {
            val stored = preferences.getString(KEY_ENABLED_LAYOUTS, null)
                ?.split(',')
                ?.filter { it in LayoutIds.LANGUAGES }
                .orEmpty()
            return stored.ifEmpty { LayoutIds.DEFAULT_ENABLED }
        }
        set(value) {
            val ordered = LayoutIds.LANGUAGES.filter { it in value }.ifEmpty { listOf(LayoutIds.UZ_LATN_2027) }
            preferences.edit().putString(KEY_ENABLED_LAYOUTS, ordered.joinToString(",")).apply()
        }

    var currentLayout: String
        get() {
            val enabled = enabledLayouts
            val stored = preferences.getString(KEY_CURRENT_LAYOUT, null)
            return if (stored != null && stored in enabled) stored else enabled.first()
        }
        set(value) = preferences.edit().putString(KEY_CURRENT_LAYOUT, value).apply()

    var theme: ThemeChoice
        get() = ThemeChoice.from(preferences.getString(KEY_THEME, null))
        set(value) = preferences.edit().putString(KEY_THEME, value.id).apply()

    var keyHeight: KeyHeight
        get() = KeyHeight.from(preferences.getString(KEY_KEY_HEIGHT, null))
        set(value) = preferences.edit().putString(KEY_KEY_HEIGHT, value.id).apply()

    var keySound by BooleanPreference(KEY_SOUND, false)
    var vibration by BooleanPreference(KEY_VIBRATION, true)
    var autoCapitalize by BooleanPreference(KEY_AUTO_CAPITALIZE, true)
    var doubleSpacePeriod by BooleanPreference(KEY_DOUBLE_SPACE, true)
    var showSuggestions by BooleanPreference(KEY_SUGGESTIONS, true)
    var autoCorrect by BooleanPreference(KEY_AUTO_CORRECT, false)
    var learnWords by BooleanPreference(KEY_LEARN_WORDS, true)
    var numberRow by BooleanPreference(KEY_NUMBER_ROW, false)
    var numberHints by BooleanPreference(KEY_NUMBER_HINTS, true)
    var keyPopup by BooleanPreference(KEY_POPUP, true)
    var apostropheFix by BooleanPreference(KEY_APOSTROPHE_FIX, true)

    var recentEmojis: List<String>
        get() = preferences.getString(KEY_RECENT_EMOJI, null)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        set(value) = preferences.edit().putString(KEY_RECENT_EMOJI, value.take(MAX_RECENT_EMOJI).joinToString(" ")).apply()

    /** Bumped by the settings screen to ask the running keyboard to drop learned words. */
    var userDictionaryEpoch: Long
        get() = preferences.getLong(KEY_USER_DICT_EPOCH, 0L)
        set(value) = preferences.edit().putLong(KEY_USER_DICT_EPOCH, value).apply()

    private inner class BooleanPreference(private val key: String, private val default: Boolean) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean = preferences.getBoolean(key, default)
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            preferences.edit().putBoolean(key, value).apply()
        }
    }

    companion object {
        const val FILE_NAME = "uzkey_settings"
        const val KEY_ENABLED_LAYOUTS = "enabled_layouts"
        const val KEY_CURRENT_LAYOUT = "current_layout"
        const val KEY_THEME = "theme"
        const val KEY_KEY_HEIGHT = "key_height"
        const val KEY_SOUND = "key_sound"
        const val KEY_VIBRATION = "vibration"
        const val KEY_AUTO_CAPITALIZE = "auto_capitalize"
        const val KEY_DOUBLE_SPACE = "double_space_period"
        const val KEY_SUGGESTIONS = "show_suggestions"
        const val KEY_AUTO_CORRECT = "auto_correct"
        const val KEY_LEARN_WORDS = "learn_words"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_NUMBER_HINTS = "number_hints"
        const val KEY_POPUP = "key_popup"
        const val KEY_APOSTROPHE_FIX = "apostrophe_fix"
        const val KEY_RECENT_EMOJI = "recent_emoji"
        const val KEY_USER_DICT_EPOCH = "user_dict_epoch"
        private const val MAX_RECENT_EMOJI = 32

        /** Keys whose change does not require rebuilding the keyboard. */
        val VOLATILE_KEYS = setOf(KEY_CURRENT_LAYOUT, KEY_RECENT_EMOJI)
    }
}
