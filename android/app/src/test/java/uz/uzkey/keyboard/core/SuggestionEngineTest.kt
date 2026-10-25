package uz.uzkey.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {

    private val dictionary = WordDictionary.load(
        """
        salom	220
        salomatlik	180
        sabr	150
        shahar	190
        oʻzbek	200
        oʻzbekcha	170
        kitob	185
        kitoblar	150
        yaxshi	210
        """.trimIndent().byteInputStream(),
    )

    @Test
    fun prefixRangeFindsCompletions() {
        val range = dictionary.prefixRange("sal")
        val words = range.map { dictionary.word(it) }
        assertEquals(listOf("salom", "salomatlik"), words)
        assertTrue(dictionary.prefixRange("zzz").isEmpty())
    }

    @Test
    fun completionsRankedByFrequencyAndCased() {
        val engine = SuggestionEngine(dictionary, UserDictionary())
        val result = engine.suggest("Sal", null)
        assertEquals("Salom", result.candidates.first())
        assertTrue("Salomatlik" in result.candidates)
    }

    @Test
    fun apostropheVariantsMatchDictionary() {
        val engine = SuggestionEngine(dictionary, UserDictionary())
        assertEquals("oʻzbek", engine.suggest("o'zb", null).candidates.first())
    }

    @Test
    fun typosAreCorrected() {
        val engine = SuggestionEngine(dictionary, UserDictionary())
        assertTrue("kitob" in engine.suggest("kiotb", null).candidates)
        assertEquals("yaxshi", engine.autocorrection("yaxsho", null))
        assertNull(engine.autocorrection("salom", null))
    }

    @Test
    fun learnedWordsAndNextWordPrediction() {
        val user = UserDictionary()
        val engine = SuggestionEngine(dictionary, user)
        user.learn("salom", "Doniyor")
        user.learn("salom", "Doniyor")
        assertTrue("Doniyor" in engine.suggest("Don", null).candidates)
        assertEquals(listOf("Doniyor"), engine.predictNext("salom"))
    }

    @Test
    fun userDictionarySurvivesSerialization() {
        val user = UserDictionary()
        user.learn(null, "uzkey")
        user.learn("uzkey", "zor")
        val restored = UserDictionary().apply { restore(user.serialize()) }
        assertEquals(1, restored.count("uzkey"))
        assertEquals(1, restored.bigramCount("uzkey", "zor"))
    }

    @Test
    fun boundedDistanceHandlesTranspositions() {
        assertEquals(1, SuggestionEngine.boundedDistance("kiotb", "kitob", 2))
        assertEquals(0, SuggestionEngine.boundedDistance("abc", "abc", 1))
        assertEquals(2, SuggestionEngine.boundedDistance("abcdef", "abxdyf", 1))
        assertEquals(1, SuggestionEngine.distanceUpToOne("kiotb", "kitob"))
        assertEquals(1, SuggestionEngine.distanceUpToOne("helo", "hello"))
        assertEquals(1, SuggestionEngine.distanceUpToOne("helllo", "hello"))
        assertEquals(1, SuggestionEngine.distanceUpToOne("hallo", "hello"))
        assertEquals(0, SuggestionEngine.distanceUpToOne("abc", "abcdef", 3))
        assertEquals(2, SuggestionEngine.distanceUpToOne("abcd", "badc"))
    }

    @Test
    fun oldSpellingsFindNewAlphabetWords() {
        val newAlphabet = WordDictionary.load("yaxşi\t210\nşahar\t190\nözbek\t200\nğalaba\t150\n".byteInputStream(), KeyNormalizer.LATIN_2027)
        val engine = SuggestionEngine(newAlphabet, UserDictionary(KeyNormalizer.LATIN_2027), KeyNormalizer.LATIN_2027)
        assertEquals("yaxşi", engine.suggest("yaxsh", null).candidates.first())
        assertEquals("şahar", engine.suggest("Shah", null).candidates.first().lowercase())
        assertEquals("özbek", engine.suggest("o'zb", null).candidates.first())
        assertEquals("ğalaba", engine.suggest("gʻal", null).candidates.first())
    }

    @Test
    fun prefixesAreNotAutocorrected() {
        val engine = SuggestionEngine(dictionary, UserDictionary())
        assertNull(engine.autocorrection("kitobl", null))
        assertEquals("salom", engine.suggest("sal", null).candidates.first())
    }
}
