package uz.uzkey.keyboard.core

import java.util.Locale

/**
 * Converts Uzbek text between the three scripts in use:
 * the 2027 Latin alphabet (ö ğ ş ç), the current Latin alphabet (oʻ gʻ sh ch) and Cyrillic.
 */
object UzbekTransliterator {

    enum class Script { LATIN_2027, LATIN, CYRILLIC }

    private const val TURNED_COMMA = TextRules.TURNED_COMMA
    private const val TUTUQ = TextRules.MODIFIER_APOSTROPHE

    fun scriptForLayout(layoutId: String): Script? = when (layoutId) {
        LayoutIds.UZ_LATN_2027 -> Script.LATIN_2027
        LayoutIds.UZ_LATN -> Script.LATIN
        LayoutIds.UZ_CYRL -> Script.CYRILLIC
        else -> null
    }

    /** Converts text written in any of the three scripts (or a mix) into [target]. */
    fun convert(text: String, target: Script): String {
        val latin = latin2027ToLatin(cyrillicToLatin(text))
        return when (target) {
            Script.LATIN -> latin
            Script.LATIN_2027 -> latinToLatin2027(latin)
            Script.CYRILLIC -> latinToCyrillic(latin)
        }
    }

    // ---- Cyrillic → current Latin ----------------------------------------------------------

    private val CYRILLIC_TO_LATIN = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ё' to "yo", 'ж' to "j", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "x", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sh", 'ъ' to "$TUTUQ", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'ў' to "o$TURNED_COMMA", 'қ' to "q", 'ғ' to "g$TURNED_COMMA", 'ҳ' to "h",
    )
    private val CYRILLIC_VOWELS = setOf('а', 'е', 'ё', 'и', 'о', 'у', 'э', 'ю', 'я', 'ў', 'ъ', 'ь')

    fun cyrillicToLatin(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (i in text.indices) {
            val original = text[i]
            val lower = original.lowercaseChar()
            val mapped = when {
                lower == 'е' -> {
                    val prev = if (i > 0) text[i - 1].lowercaseChar() else ' '
                    if (i == 0 || !prev.isLetter() || prev in CYRILLIC_VOWELS) "ye" else "e"
                }
                lower == 'ц' -> {
                    val prev = if (i > 0) text[i - 1].lowercaseChar() else ' '
                    if (i == 0 || !prev.isLetter() || prev !in CYRILLIC_VOWELS) "s" else "ts"
                }
                else -> CYRILLIC_TO_LATIN[lower]
            }
            if (mapped == null) {
                out.append(original)
                continue
            }
            if (original.isUpperCase() && mapped.isNotEmpty()) {
                val next = text.getOrNull(i + 1)
                val prev = text.getOrNull(i - 1)
                val wholeWordUpper = (next != null && next.isUpperCase()) || (next?.isLetter() != true && prev != null && prev.isUpperCase())
                out.append(if (wholeWordUpper) mapped.uppercase(Locale.ROOT) else mapped.substring(0, 1).uppercase(Locale.ROOT) + mapped.substring(1))
            } else {
                out.append(mapped)
            }
        }
        return out.toString()
    }

    // ---- 2027 Latin ↔ current Latin -------------------------------------------------------

    fun latin2027ToLatin(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (i in text.indices) {
            val c = text[i]
            val upperWord = c.isUpperCase() && (text.getOrNull(i + 1)?.isUpperCase() == true || text.getOrNull(i - 1)?.isUpperCase() == true)
            when (c) {
                'ö' -> out.append("o").append(TURNED_COMMA)
                'Ö' -> out.append("O").append(TURNED_COMMA)
                'ğ' -> out.append("g").append(TURNED_COMMA)
                'Ğ' -> out.append("G").append(TURNED_COMMA)
                'ş' -> out.append("sh")
                'Ş' -> out.append(if (upperWord) "SH" else "Sh")
                'ç' -> out.append("ch")
                'Ç' -> out.append(if (upperWord) "CH" else "Ch")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private val TURNED_COMMA_LIKE = setOf('\'', '‘', '’', '`', TURNED_COMMA)

    fun latinToLatin2027(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                (c == 'o' || c == 'O' || c == 'g' || c == 'G') && next != null && next in TURNED_COMMA_LIKE -> {
                    out.append(
                        when (c) {
                            'o' -> 'ö'
                            'O' -> 'Ö'
                            'g' -> 'ğ'
                            else -> 'Ğ'
                        },
                    )
                    i += 2
                }
                (c == 's' || c == 'S') && (next == 'h' || next == 'H') -> {
                    out.append(if (c == 's') 'ş' else 'Ş')
                    i += 2
                }
                (c == 'c' || c == 'C') && (next == 'h' || next == 'H') -> {
                    out.append(if (c == 'c') 'ç' else 'Ç')
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    // ---- current Latin → Cyrillic ---------------------------------------------------------

    private val LATIN_TO_CYRILLIC = mapOf(
        'a' to "а", 'b' to "б", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "ҳ", 'i' to "и",
        'j' to "ж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "қ",
        'r' to "р", 's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'x' to "х", 'y' to "й", 'z' to "з",
        'c' to "с", 'w' to "в",
    )
    private val LATIN_VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    fun latinToCyrillic(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val lower = c.lowercaseChar()
            val next = text.getOrNull(i + 1)
            val nextLower = next?.lowercaseChar()
            val afterNext = text.getOrNull(i + 2)
            val prevLower = text.getOrNull(i - 1)?.lowercaseChar()
            val atWordStart = prevLower == null || !prevLower.isLetter()

            val (cyrillic, consumed) = when {
                (lower == 'o' || lower == 'g') && nextLower != null && nextLower in TURNED_COMMA_LIKE ->
                    (if (lower == 'o') "ў" else "ғ") to 2
                lower == 's' && nextLower == 'h' -> "ш" to 2
                lower == 'c' && nextLower == 'h' -> "ч" to 2
                lower == 'y' && nextLower == 'o' && (afterNext == null || afterNext !in TURNED_COMMA_LIKE) -> "ё" to 2
                lower == 'y' && nextLower == 'u' -> "ю" to 2
                lower == 'y' && nextLower == 'a' -> "я" to 2
                lower == 'y' && nextLower == 'e' -> "е" to 2
                lower == 'e' && (atWordStart || prevLower in LATIN_VOWELS) -> "э" to 1
                c == TUTUQ || ((c == '\'' || c == '’') && prevLower != null && prevLower.isLetter() && nextLower != null && nextLower.isLetter()) -> "ъ" to 1
                else -> (LATIN_TO_CYRILLIC[lower] ?: c.toString()) to 1
            }
            if (c.isUpperCase()) {
                val upperWord = (next?.isUpperCase() == true && consumed == 1) ||
                    (consumed == 2 && afterNext?.isUpperCase() == true) ||
                    (next?.isLetter() != true && text.getOrNull(i - 1)?.isUpperCase() == true)
                out.append(if (upperWord || cyrillic.length == 1) cyrillic.uppercase(Locale.ROOT) else cyrillic.substring(0, 1).uppercase(Locale.ROOT) + cyrillic.substring(1))
            } else {
                out.append(cyrillic)
            }
            i += consumed
        }
        return out.toString()
    }
}
