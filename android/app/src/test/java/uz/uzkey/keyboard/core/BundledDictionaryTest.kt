package uz.uzkey.keyboard.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quality and speed checks against the dictionaries that actually ship in the app. */
class BundledDictionaryTest {

    private val dictionaryDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/dictionaries") }
        .first { it.isDirectory }

    private fun load(id: String): WordDictionary =
        File(dictionaryDir, "$id.txt").inputStream().use { WordDictionary.load(it, KeyNormalizer.forDictionary(id)) }

    private fun engine(id: String) = SuggestionEngine(load(id), UserDictionary(KeyNormalizer.forDictionary(id)), KeyNormalizer.forDictionary(id))

    @Test
    fun everyDictionaryLoadsWithPlausibleSize() {
        for (id in LayoutIds.LANGUAGES) {
            val dictionary = load(id)
            assertTrue("$id has only ${dictionary.size} words", dictionary.size > 900)
        }
    }

    @Test
    fun uzbekScriptsStayInSync() {
        // The three Uzbek lists are generated from one source, so they must cover the same words.
        val sizes = listOf(LayoutIds.UZ_LATN_2027, LayoutIds.UZ_LATN, LayoutIds.UZ_CYRL).map { load(it).size }
        assertTrue("Uzbek dictionary sizes diverge: $sizes", sizes.max() - sizes.min() <= sizes.max() / 50)
    }

    @Test
    fun uzbekDictionariesUseTheirOwnAlphabet() {
        val latin2027 = File(dictionaryDir, "uz_latn_2027.txt").readLines().map { it.substringBefore('\t') }
        assertTrue("legacy oʻ/gʻ/sh/ch left in the new-alphabet list", latin2027.none { "oʻ" in it || "gʻ" in it || "o'" in it })
        val cyrillic = File(dictionaryDir, "uz_cyrl.txt").readLines().map { it.substringBefore('\t') }
        val latinInCyrillic = cyrillic.count { word -> word.any { it in 'a'..'z' } }
        assertTrue("$latinInCyrillic Cyrillic entries contain Latin letters", latinInCyrillic <= cyrillic.size / 200)
    }

    @Test
    fun commonWordsAreSuggested() {
        val cases = mapOf(
            LayoutIds.UZ_LATN_2027 to listOf("yax" to "yaxşi", "özbe" to "özbek", "rahm" to "rahmat"),
            LayoutIds.UZ_LATN to listOf("yax" to "yaxshi", "oʻzbe" to "oʻzbek", "rahm" to "rahmat"),
            LayoutIds.UZ_CYRL to listOf("ях" to "яхши", "ўзбе" to "ўзбек", "раҳм" to "раҳмат"),
            LayoutIds.RU to listOf("спас" to "спасибо", "приве" to "привет"),
            LayoutIds.EN to listOf("thank" to "thanks", "keyb" to "keyboard"),
        )
        for ((id, pairs) in cases) {
            val engine = engine(id)
            for ((typed, expected) in pairs) {
                val candidates = engine.suggest(typed, null).candidates.map { it.lowercase() }
                assertTrue("$id: “$typed” → $candidates, expected “$expected”", expected in candidates)
            }
        }
    }

    @Test
    fun typosAreCorrected() {
        assertEquals("kitob", engine(LayoutIds.UZ_LATN).autocorrection("kiotb", null))
        assertTrue("салом" in engine(LayoutIds.UZ_CYRL).suggest("саолм", null).candidates.map { it.lowercase() })
    }

    @Test
    fun suggestionsAreFastEnoughForEveryKeystroke() {
        for (id in LayoutIds.LANGUAGES) {
            val engine = engine(id)
            val inputs = when (id) {
                LayoutIds.UZ_CYRL -> listOf("с", "са", "сал", "салом", "китпб", "ўзбекистонн")
                LayoutIds.RU -> listOf("п", "пр", "при", "привет", "превет", "спосибо")
                LayoutIds.EN -> listOf("t", "th", "the", "helo", "becuase", "keyboard")
                LayoutIds.UZ_LATN_2027 -> listOf("s", "sa", "sal", "salom", "kiotb", "özbekistonn", "yaxsh")
                else -> listOf("s", "sa", "sal", "salom", "kiotb", "oʻzbekistonn", "yaxsh")
            }
            repeat(3) { inputs.forEach { engine.suggest(it, "bu") } } // JIT warm-up
            val rounds = 10
            val start = System.nanoTime()
            repeat(rounds) { inputs.forEach { engine.suggest(it, "bu") } }
            val perQueryMs = (System.nanoTime() - start) / 1e6 / (rounds * inputs.size)
            println("suggest latency $id: %.2f ms per keystroke".format(perQueryMs))
            // A desktop JVM is several times faster than a budget phone; 8 ms leaves room for a 60 Hz frame there.
            assertTrue("$id suggestions take $perQueryMs ms", perQueryMs < 8.0)
        }
    }
}
