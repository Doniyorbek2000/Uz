package uz.uzkey.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {

    @Test
    fun currentWordStopsAtSeparators() {
        assertEquals("salom", TextRules.currentWord("Men salom"))
        assertEquals("", TextRules.currentWord("Men salom "))
        assertEquals("oʻzbek", TextRules.currentWord("biz oʻzbek"))
        assertEquals("maʼno", TextRules.currentWord("(maʼno"))
        assertEquals("word", TextRules.currentWord("'word"))
    }

    @Test
    fun previousWordSkipsPunctuationButNotSentences() {
        assertEquals("salom", TextRules.previousWord("salom, do"))
        assertEquals("men", TextRules.previousWord("men "))
        assertNull(TextRules.previousWord("Tamom. Yan"))
        assertNull(TextRules.previousWord("bir"))
    }

    @Test
    fun sentenceStartDetection() {
        assertTrue(TextRules.isSentenceStart(""))
        assertTrue(TextRules.isSentenceStart("Salom. "))
        assertTrue(TextRules.isSentenceStart("Salom\n"))
        assertFalse(TextRules.isSentenceStart("Salom "))
        assertFalse(TextRules.isSentenceStart("Salom."))
    }

    @Test
    fun normalizeFoldsApostrophesAndYo() {
        assertEquals("o'zbek", TextRules.normalizeKey("Oʻzbek"))
        assertEquals("o'zbek", TextRules.normalizeKey("o'zbek"))
        assertEquals("ма'но", TextRules.normalizeKey("маʼно"))
        assertEquals("еж", TextRules.normalizeKey("Ёж"))
    }

    @Test
    fun matchCaseFollowsTypedWord() {
        assertEquals("Salom", TextRules.matchCase("salom", "Sa"))
        assertEquals("SALOM", TextRules.matchCase("salom", "SA"))
        assertEquals("salom", TextRules.matchCase("salom", "sa"))
    }

    @Test
    fun apostropheReplacementPerAlphabet() {
        assertEquals("ö", TextRules.apostropheReplacement(LayoutIds.UZ_LATN_2027, 'o', "'"))
        assertEquals("Ğ", TextRules.apostropheReplacement(LayoutIds.UZ_LATN_2027, 'G', "ʻ"))
        assertNull(TextRules.apostropheReplacement(LayoutIds.UZ_LATN_2027, 'a', "'"))
        assertNull(TextRules.apostropheReplacement(LayoutIds.UZ_LATN_2027, 'o', "ʼ"))
        assertEquals("oʻ", TextRules.apostropheReplacement(LayoutIds.UZ_LATN, 'o', "'"))
        assertEquals("aʼ", TextRules.apostropheReplacement(LayoutIds.UZ_LATN, 'a', "'"))
        assertNull(TextRules.apostropheReplacement(LayoutIds.EN, 'o', "'"))
    }
}
