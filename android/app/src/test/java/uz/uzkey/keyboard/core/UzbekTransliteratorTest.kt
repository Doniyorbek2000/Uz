package uz.uzkey.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.uzkey.keyboard.core.UzbekTransliterator.Script

class UzbekTransliteratorTest {

    @Test
    fun currentLatinToNewAlphabet() {
        assertEquals("Özbekiston çiroyli şahar", UzbekTransliterator.convert("Oʻzbekiston chiroyli shahar", Script.LATIN_2027))
        assertEquals("ğalaba", UzbekTransliterator.convert("g'alaba", Script.LATIN_2027))
        assertEquals("ŞAHAR", UzbekTransliterator.convert("SHAHAR", Script.LATIN_2027))
        assertEquals("Isʼhoq", UzbekTransliterator.convert("Isʼhoq", Script.LATIN_2027))
    }

    @Test
    fun newAlphabetToCurrentLatin() {
        assertEquals("Oʻzbekiston chiroyli shahar", UzbekTransliterator.convert("Özbekiston çiroyli şahar", Script.LATIN))
        assertEquals("SHAHAR", UzbekTransliterator.convert("ŞAHAR", Script.LATIN))
    }

    @Test
    fun cyrillicToLatinScripts() {
        assertEquals("Oʻzbekiston", UzbekTransliterator.convert("Ўзбекистон", Script.LATIN))
        assertEquals("Özbekiston", UzbekTransliterator.convert("Ўзбекистон", Script.LATIN_2027))
        assertEquals("yetti", UzbekTransliterator.convert("етти", Script.LATIN))
        assertEquals("maʼno", UzbekTransliterator.convert("маъно", Script.LATIN))
        assertEquals("Shahar", UzbekTransliterator.convert("Шаҳар", Script.LATIN))
        assertEquals("ŞAHAR", UzbekTransliterator.convert("ШАҲАР", Script.LATIN_2027))
        assertEquals("sirk litsey", UzbekTransliterator.convert("цирк лицей", Script.LATIN))
    }

    @Test
    fun latinToCyrillic() {
        assertEquals("Ўзбекистон", UzbekTransliterator.convert("Oʻzbekiston", Script.CYRILLIC))
        assertEquals("Ўзбекистон", UzbekTransliterator.convert("Özbekiston", Script.CYRILLIC))
        assertEquals("эртага", UzbekTransliterator.convert("ertaga", Script.CYRILLIC))
        assertEquals("йўл", UzbekTransliterator.convert("yo'l", Script.CYRILLIC))
        assertEquals("ёки", UzbekTransliterator.convert("yoki", Script.CYRILLIC))
        assertEquals("маъно", UzbekTransliterator.convert("ma'no", Script.CYRILLIC))
        assertEquals("ШАҲАР", UzbekTransliterator.convert("ŞAHAR", Script.CYRILLIC))
        assertEquals("Чиройли", UzbekTransliterator.convert("Chiroyli", Script.CYRILLIC))
    }

    @Test
    fun roundTripKeepsPunctuationAndOtherScripts() {
        val text = "Salom, dunyo! 2027-yil: Hello."
        assertEquals("Салом, дунё! 2027-йил: Ҳелло.", UzbekTransliterator.convert(text, Script.CYRILLIC))
        assertEquals(text, UzbekTransliterator.convert(text, Script.LATIN))
    }
}
