package uz.uzkey.keyboard.core

import java.util.Locale

/** Maps a word to the key used for dictionary comparisons. */
fun interface KeyNormalizer {
    fun normalize(word: String): String

    companion object {
        val DEFAULT = KeyNormalizer { TextRules.normalizeKey(it) }

        /** In the 2027 alphabet the old spellings (sh, ch, oʻ, gʻ) still find the new words. */
        val LATIN_2027 = KeyNormalizer { UzbekTransliterator.latinToLatin2027(TextRules.normalizeKey(it)) }

        fun forDictionary(dictionaryId: String): KeyNormalizer =
            if (dictionaryId == LayoutIds.UZ_LATN_2027) LATIN_2027 else DEFAULT
    }
}

object TextRules {

    const val TURNED_COMMA = 'ʻ' // ʻ — part of oʻ / gʻ
    const val MODIFIER_APOSTROPHE = 'ʼ' // ʼ — tutuq belgisi

    private val APOSTROPHES = setOf('\'', '‘', '’', '`', TURNED_COMMA, MODIFIER_APOSTROPHE)
    private val SENTENCE_END = setOf('.', '!', '?', '…')
    private val ATTACHING_PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', ')', '…', '%')

    fun isApostrophe(c: Char): Boolean = c in APOSTROPHES

    fun isWordChar(c: Char): Boolean =
        Character.isLetter(c) || c in APOSTROPHES || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    fun attachesToPreviousWord(text: String): Boolean = text.length == 1 && text[0] in ATTACHING_PUNCTUATION

    /** Lower-cased, apostrophe-folded form used for every dictionary comparison. */
    fun normalizeKey(word: String): String {
        val lower = word.lowercase(Locale.ROOT)
        val builder = StringBuilder(lower.length)
        for (c in lower) {
            builder.append(
                when {
                    c in APOSTROPHES -> '\''
                    c == 'ё' -> 'е'
                    else -> c
                },
            )
        }
        return builder.toString()
    }

    /** The word that ends exactly at the cursor (empty when the cursor follows a separator). */
    fun currentWord(textBeforeCursor: CharSequence): String {
        var start = textBeforeCursor.length
        while (start > 0 && isWordChar(textBeforeCursor[start - 1])) start--
        var word = textBeforeCursor.subSequence(start, textBeforeCursor.length).toString()
        while (word.isNotEmpty() && isApostrophe(word[0])) word = word.substring(1)
        return word
    }

    /** The complete word that precedes the current word (skipping spaces and punctuation). */
    fun previousWord(textBeforeCursor: CharSequence): String? {
        var end = textBeforeCursor.length
        while (end > 0 && isWordChar(textBeforeCursor[end - 1])) end--
        while (end > 0 && !isWordChar(textBeforeCursor[end - 1])) {
            if (textBeforeCursor[end - 1] in SENTENCE_END || textBeforeCursor[end - 1] == '\n') return null
            end--
        }
        if (end == 0) return null
        val word = currentWord(textBeforeCursor.subSequence(0, end))
        return word.ifEmpty { null }
    }

    /** True when the next character should be capitalised for sentence-case input. */
    fun isSentenceStart(textBeforeCursor: CharSequence): Boolean {
        var i = textBeforeCursor.length
        if (i == 0) return true
        if (!textBeforeCursor[i - 1].isWhitespace()) return false
        while (i > 0 && textBeforeCursor[i - 1].isWhitespace()) {
            if (textBeforeCursor[i - 1] == '\n') return true
            i--
        }
        if (i == 0) return true
        return textBeforeCursor[i - 1] in SENTENCE_END
    }

    /** Applies the capitalisation pattern of what the user typed to a dictionary candidate. */
    fun matchCase(candidate: String, typed: String): String {
        if (typed.isEmpty() || candidate.isEmpty()) return candidate
        val letters = typed.filter { it.isLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return candidate.uppercase(Locale.ROOT)
        if (typed[0].isUpperCase()) return candidate.substring(0, 1).uppercase(Locale.ROOT) + candidate.substring(1)
        return candidate
    }

    fun uppercaseKeyText(text: String): String = when (text) {
        "ß" -> "ẞ"
        else -> text.uppercase(Locale.ROOT)
    }

    /**
     * Rewrites an apostrophe typed right after o/g into the proper letter for the active alphabet:
     * new alphabet: o' → ö, g' → ğ; current alphabet: o' → oʻ, g' → gʻ.
     * Returns the text that replaces the previous character, or null when no rule applies.
     */
    fun apostropheReplacement(layoutId: String, previous: Char, typed: String): String? {
        if (typed.length != 1) return null
        val c = typed[0]
        if (c != '\'' && c != '‘' && c != '’' && c != '`' && c != TURNED_COMMA) return null
        return when (layoutId) {
            LayoutIds.UZ_LATN_2027 -> when (previous) {
                'o' -> "ö"
                'O' -> "Ö"
                'g' -> "ğ"
                'G' -> "Ğ"
                else -> null
            }
            LayoutIds.UZ_LATN -> when (previous) {
                'o', 'O', 'g', 'G' -> "$previous$TURNED_COMMA"
                else -> if (previous.isLetter()) "$previous$MODIFIER_APOSTROPHE" else null
            }
            else -> null
        }
    }
}
