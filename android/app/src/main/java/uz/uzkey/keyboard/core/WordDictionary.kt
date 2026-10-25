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
    val size: Int get() = words.size

    fun word(index: Int): String = words[index]
    fun key(index: Int): String = keys[index]
    fun frequency(index: Int): Int = frequencies[index]

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

        /** Parses `word<TAB>frequency` lines. */
        fun load(stream: InputStream, normalizer: KeyNormalizer = KeyNormalizer.DEFAULT): WordDictionary {
            data class Row(val word: String, val key: String, val frequency: Int)

            val rows = ArrayList<Row>(32_000)
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val tab = line.indexOf('\t')
                    val word = if (tab < 0) line.trim() else line.substring(0, tab)
                    val frequency = if (tab < 0) 1 else line.substring(tab + 1).trim().toIntOrNull() ?: 1
                    if (word.isNotEmpty()) rows += Row(word, normalizer.normalize(word), frequency)
                }
            }
            rows.sortWith(compareBy<Row> { it.key }.thenByDescending { it.frequency })
            val deduplicated = ArrayList<Row>(rows.size)
            for (row in rows) {
                if (deduplicated.isEmpty() || deduplicated.last().key != row.key) deduplicated += row
            }
            return WordDictionary(
                Array(deduplicated.size) { deduplicated[it].word },
                Array(deduplicated.size) { deduplicated[it].key },
                IntArray(deduplicated.size) { deduplicated[it].frequency },
            )
        }
    }
}
