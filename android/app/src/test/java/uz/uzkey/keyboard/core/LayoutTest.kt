package uz.uzkey.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayoutTest {

    private val sharedDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/layouts") }
        .first { it.isDirectory }

    private fun load(id: String) = LayoutParser.parse(File(sharedDir, "$id.json").readText())

    @Test
    fun everyLayoutParses() {
        val ids = LayoutIds.LANGUAGES + listOf(LayoutIds.SYMBOLS, LayoutIds.SYMBOLS_MORE, LayoutIds.NUMPAD)
        for (id in ids) {
            val layout = load(id)
            assertEquals(id, layout.id)
            assertTrue("$id has rows", layout.rows.isNotEmpty())
        }
    }

    @Test
    fun newAlphabetHasAllUzbekLetters() {
        val letters = load(LayoutIds.UZ_LATN_2027).rows.flatten().filter { it.type == KeyType.CHAR }.map { it.output }.toSet()
        for (letter in listOf("ö", "ğ", "ş", "ç", "ʼ", "q", "x", "h")) assertTrue(letter in letters)
    }

    @Test
    fun builderAddsBottomRowAndHints() {
        val options = BuildOptions(numberRow = false, numberHints = true, fieldKind = FieldKind.TEXT, showLanguageKey = true)
        val rows = KeyboardBuilder.build(load(LayoutIds.EN), true, options)
        assertEquals(4, rows.size)
        assertEquals("1", rows[0][0].hint)
        assertEquals("1", rows[0][0].alternates.first())
        assertTrue(rows.last().any { it.type == KeyType.SPACE })
        assertTrue(rows.last().any { it.type == KeyType.LANGUAGE })
        val numpad = KeyboardBuilder.build(load(LayoutIds.NUMPAD), false, options)
        assertEquals(4, numpad.size)
    }
}
