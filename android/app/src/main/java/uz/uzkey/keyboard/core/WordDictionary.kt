package uz.uzkey.keyboard.core

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** Immutable word list sorted by normalised key, supporting prefix ranges and exact lookups. */
class WordDictionary private constructor(
    private val words: Array<String>,
    private val keys: Array<String>,
    private val frequencies: IntArray,
) {
    /** Letter-presence bitmask of every key, see [maskOf]. */
    private val masks = LongArray(keys.size) { maskOf(keys[it]) }

    /** Key lengths and first two letters as primitives, so full scans rarely touch the strings. */
    private val lengths = IntArray(keys.size) { keys[it].length }
    private val firstChars = CharArray(keys.size) { keys[it].getOrElse(0) { ' ' } }
    private val secondChars = CharArray(keys.size) { keys[it].getOrElse(1) { ' ' } }

    val size: Int get() = words.size

    fun word(index: Int): String = words[index]
    fun key(index: Int): String = keys[index]
    fun frequency(index: Int): Int = frequencies[index]
    fun mask(index: Int): Long = masks[index]
    fun keyLength(index: Int): Int = lengths[index]
    fun firstChar(index: Int): Char = firstChars[index]
    fun secondChar(index: Int): Char = secondChars[index]

    fun indexOf(key: String): Int {
        val index = lowerBound(key)
        return if (index < keys.size && keys[index] == key) index else -1
    }

    /** Indices [start, end) of every entry whose key starts with [prefix]. */
    fun prefixRange(prefix: String): IntRange {
        if (prefix.isEmpty()) return 0 until size
        val start = lowerBound(prefix)
        var low = start
        var high = keys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keys[mid].startsWith(prefix)) low = mid + 1 else high = mid
        }
        return start until low
    }

    private fun lowerBound(key: String): Int {
        var low = 0
        var high = keys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keys[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        val EMPTY = WordDictionary(emptyArray(), emptyArray(), IntArray(0))

        /** First line of files written by tools/build_dictionaries.py. */
        const val SORTED_KEYS_HEADER = "#sorted-keys"

        /**
         * One bit per letter present (letters hashed into 64 bits). An edit changes at most two bits, so two
         * strings within edit distance d differ in at most 2·d bits — a cheap filter before computing distances.
         */
        fun maskOf(text: String, length: Int = text.length): Long {
            var mask = 0L
            for (i in 0 until length) mask = mask or (1L shl (text[i].code and 63))
            return mask
        }

        /**
         * Parses `word<TAB>frequency[<TAB>key]` lines. Bundled files carry their normalised keys and are
         * already sorted by key, so loading is a single pass; any other input is normalised and sorted here.
         */
        fun load(stream: InputStream, normalizer: KeyNormalizer = KeyNormalizer.DEFAULT): WordDictionary {
            val words = ArrayList<String>(52_000)
            val keys = ArrayList<String>(52_000)
            val frequencies = ArrayList<Int>(52_000)
            var sorted = true
            var keyed = false
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 1 shl 16).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    if (line[0] == '#') {
                        if (line.trim() == SORTED_KEYS_HEADER) keyed = true
                        return@forEach
                    }
                    val tab = line.indexOf('\t')
                    val word = if (tab < 0) line.trim() else line.substring(0, tab)
                    if (word.isEmpty()) return@forEach
                    val secondTab = if (tab < 0) -1 else line.indexOf('\t', tab + 1)
                    val frequency = when {
                        tab < 0 -> 1
                        secondTab < 0 -> line.substring(tab + 1).trim().toIntOrNull() ?: 1
                        else -> line.substring(tab + 1, secondTab).trim().toIntOrNull() ?: 1
                    }
                    // In keyed files a missing key column means the word is its own key; elsewhere keys are computed.
                    // Most keys equal their word, and sharing the String halves the memory of the key column.
                    val key = when {
                        secondTab >= 0 -> line.substring(secondTab + 1).trim()
                        keyed -> word
                        else -> normalizer.normalize(word).let { if (it == word) word else it }
                    }
                    if (sorted && keys.isNotEmpty() && keys[keys.size - 1] >= key) sorted = false
                    words += word
                    keys += key
                    frequencies += frequency
                }
            }
            if (sorted) return WordDictionary(words.toTypedArray(), keys.toTypedArray(), frequencies.toIntArray())

            val order = words.indices.sortedWith(compareBy<Int> { keys[it] }.thenByDescending { frequencies[it] })
            val kept = ArrayList<Int>(order.size)
            for (index in order) {
                if (kept.isEmpty() || keys[kept.last()] != keys[index]) kept += index
            }
            return WordDictionary(
                Array(kept.size) { words[kept[it]] },
                Array(kept.size) { keys[kept[it]] },
                IntArray(kept.size) { frequencies[kept[it]] },
            )
        }
    }
}
