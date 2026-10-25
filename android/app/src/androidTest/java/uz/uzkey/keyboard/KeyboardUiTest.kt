package uz.uzkey.keyboard

import android.content.Intent
import android.graphics.Point
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.uzkey.keyboard.debug.TestFieldsActivity
import uz.uzkey.keyboard.settings.KeyboardSettings

/**
 * Drives the real keyboard on a device or emulator: keys are found through the keyboard's accessibility
 * tree (every key is a virtual node) and pressed with injected touches, and results are read back from
 * an editor running in another process.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardUiTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = KeyboardSettings(context)

    @Before
    fun setUp() {
        settings.preferences.edit()
            .clear()
            .putString(KeyboardSettings.KEY_ENABLED_LAYOUTS, "uz_latn_2027,uz_cyrl,ru,en")
            .putString(KeyboardSettings.KEY_CURRENT_LAYOUT, "uz_latn_2027")
            .putBoolean(KeyboardSettings.KEY_VIBRATION, false)
            .putBoolean(KeyboardSettings.KEY_CLIPBOARD_HISTORY, false)
            .putLong(KeyboardSettings.KEY_USER_DICT_EPOCH, System.nanoTime())
            .commit()
        selectKeyboard()
        context.startActivity(
            Intent(context, TestFieldsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertNotNull("test screen did not open", device.wait(Until.findObject(field(TEXT)), TIMEOUT_MS))
    }

    @After
    fun tearDown() {
        device.pressBack()
        device.pressBack()
    }

    @Test
    fun typesWordWithAutomaticCapital() {
        focus(TEXT)
        type("salom")
        assertFieldText(TEXT, "Salom")
    }

    @Test
    fun backspaceDeletesLastCharacter() {
        focus(TEXT)
        type("kitob")
        key(string(R.string.a11y_delete)).click()
        assertFieldText(TEXT, "Kito")
    }

    @Test
    fun doubleSpaceEndsSentence() {
        focus(TEXT)
        type("salom")
        val space = key(string(R.string.a11y_space))
        space.click()
        space.click()
        assertFieldText(TEXT, "Salom. ")
    }

    @Test
    fun shiftUppercasesOneLetterAndDoubleTapLocks() {
        focus(TEXT)
        type("a")
        key(string(R.string.a11y_shift)).click()
        type("b")
        type("c")
        assertFieldText(TEXT, "ABc")
        val shift = key(string(R.string.a11y_shift))
        shift.click()
        shift.click()
        assertNotNull("caps lock is not shown", findKey(string(R.string.a11y_caps_lock)))
        type("de")
        assertFieldText(TEXT, "ABcDE")
    }

    @Test
    fun pickingSuggestionCompletesWord() {
        focus(TEXT)
        type("yax")
        val suggestion = device.wait(Until.findObject(keyDesc("yaxşi")), TIMEOUT_MS)
        assertNotNull("no suggestion for “yax”", suggestion)
        suggestion.click()
        assertFieldText(TEXT, "Yaxşi ")
    }

    @Test
    fun languageKeyCyclesLayouts() {
        focus(TEXT)
        val uzbekLabel = string(R.string.a11y_language, "Özbekça")
        key(uzbekLabel).click()
        assertNotNull("Cyrillic layout did not appear", device.wait(Until.findObject(keyDesc("ў")), TIMEOUT_MS))
        type("салом")
        assertFieldText(TEXT, "Салом")
        assertEquals("uz_cyrl", settings.currentLayout)
    }

    @Test
    fun symbolsLayerTypesDigitsAndReturnsToLetters() {
        focus(TEXT)
        key(string(R.string.a11y_symbols)).click()
        type("12")
        key(string(R.string.a11y_letters)).click()
        type("a")
        assertFieldText(TEXT, "12a")
    }

    @Test
    fun numberFieldShowsNumberPad() {
        focus(NUMBER)
        assertNotNull(device.wait(Until.findObject(keyDesc("5")), TIMEOUT_MS))
        assertNull("letters must not be shown on the number pad", findKey("q"))
        type("2027")
        assertFieldText(NUMBER, "2027")
    }

    @Test
    fun emailFieldTypesWithoutCapitalOrAutoSpace() {
        focus(EMAIL)
        type("ali")
        assertFieldText(EMAIL, "ali")
    }

    @Test
    fun searchActionDoesNotInsertNewline() {
        focus(SEARCH)
        type("ok")
        key(string(R.string.a11y_enter)).click()
        SystemClock.sleep(SETTLE_MS)
        assertFieldText(SEARCH, "Ok")
    }

    @Test
    fun passwordFieldIsNeverLearned() {
        focus(PASSWORD)
        repeat(3) {
            type("qwzxj")
            key(string(R.string.a11y_space)).click()
        }
        focus(TEXT)
        type("qwz")
        SystemClock.sleep(SETTLE_MS)
        assertNull("a word typed in a password field was suggested", findKey("qwzxj"))
    }

    @Test
    fun repeatedWordIsLearned() {
        focus(TEXT)
        repeat(3) {
            type("qwzxj")
            key(string(R.string.a11y_space)).click()
        }
        type("qwz")
        assertNotNull("a repeated word was not learned", device.wait(Until.findObject(keyDesc("qwzxj")), TIMEOUT_MS))
    }

    @Test
    fun convertButtonTransliteratesToCyrillic() {
        focus(TEXT)
        key(string(R.string.a11y_language, "Özbekça")).click()
        device.wait(Until.findObject(keyDesc("ў")), TIMEOUT_MS)
        // The toolbar replaces suggestions once the word is finished with punctuation.
        textInto(TEXT, "Salom dunyo.")
        val convert = device.wait(Until.findObject(keyDesc(string(R.string.a11y_convert))), TIMEOUT_MS)
        assertNotNull("convert button is not shown", convert)
        convert.click()
        assertFieldText(TEXT, "Салом дунё.")
    }

    @Test
    fun swipeTypesWord() {
        focus(TEXT)
        val points = "salom".map { letter ->
            val bounds = key(letter.toString()).visibleBounds
            Point(bounds.centerX(), bounds.centerY())
        }
        assertTrue(device.swipe(points.toTypedArray(), 12))
        val text = waitForFieldText(TEXT) { it.trim().equals("salom", ignoreCase = true) }
        assertEquals("Salom ", text)
    }

    // ---- Helpers --------------------------------------------------------------------------

    private fun selectKeyboard() {
        shell("ime enable $IME")
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            shell("ime set $IME")
            if (Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == IME) return
            SystemClock.sleep(500)
        }
        fail("could not make UzKey the current keyboard")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.bufferedReader().readText()
        }

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun field(name: String): BySelector = By.res(context.packageName, name)

    private fun focus(name: String) {
        device.findObject(field(name)).click()
        // Any key will do: the number pad has no space bar.
        assertNotNull("keyboard did not open", device.wait(Until.findObject(By.clazz(KEY_CLASS)), TIMEOUT_MS))
        SystemClock.sleep(SETTLE_MS)
    }

    private fun textInto(name: String, text: String) {
        device.findObject(field(name)).text = text
        SystemClock.sleep(SETTLE_MS)
    }

    private fun keyDesc(label: String): BySelector =
        By.clazz(KEY_CLASS).desc(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE))

    private fun findKey(label: String): UiObject2? = device.findObject(keyDesc(label))

    private fun key(label: String): UiObject2 =
        device.wait(Until.findObject(keyDesc(label)), TIMEOUT_MS) ?: throw AssertionError("key “$label” not found")

    private fun type(text: String) {
        for (char in text) {
            key(char.toString()).click()
            device.waitForIdle(IDLE_MS)
        }
    }

    private fun fieldText(name: String): String = device.findObject(field(name))?.text.orEmpty()

    private fun waitForFieldText(name: String, accept: (String) -> Boolean): String {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var text = fieldText(name)
        while (!accept(text) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            text = fieldText(name)
        }
        return text
    }

    private fun assertFieldText(name: String, expected: String) {
        assertEquals(expected, waitForFieldText(name) { it == expected })
    }

    private companion object {
        const val IME = "uz.uzkey.keyboard/.ime.UzKeyService"
        const val KEY_CLASS = "android.inputmethodservice.Keyboard\$Key"
        const val TEXT = "field_text"
        const val PASSWORD = "field_password"
        const val NUMBER = "field_number"
        const val EMAIL = "field_email"
        const val SEARCH = "field_search"
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 600L
        const val IDLE_MS = 300L
    }
}
