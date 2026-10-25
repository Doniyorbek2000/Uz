package uz.uzkey.keyboard.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

data class Suggestions(
    val typed: String,
    val candidates: List<String>,
    val typedIsKnown: Boolean,
) {
    val isEmpty: Boolean get() = candidates.isEmpty() && typed.isEmpty()

    companion object {
        val NONE = Suggestions("", emptyList(), true)
    }
}

/** Completion, correction and next-word prediction over a bundled dictionary plus learned words. */
class SuggestionEngine(
    private val dictionary: WordDictionary,
    private val user: UserDictionary,
    private val normalizer: KeyNormalizer = KeyNormalizer.DEFAULT,
) {

    private class Candidate(val display: String, var score: Double)

    fun isKnown(word: String): Boolean {
        val key = normalizer.normalize(word)
        return dictionary.indexOf(key) >= 0 || user.count(key) >= LEARNED_THRESHOLD
    }

    fun suggest(typed: String, previousWord: String?, limit: Int = 3): Suggestions {
        if (typed.isEmpty()) return Suggestions("", predictNext(previousWord, limit), true)
        val typedKey = normalizer.normalize(typed)
        val previousKey = previousWord?.let { normalizer.normalize(it) }
        val candidates = HashMap<String, Candidate>()

        fun offer(key: String, display: String, score: Double) {
            val existing = candidates[key]
            if (existing == null) candidates[key] = Candidate(display, score) else if (existing.score < score) existing.score = score
        }

        fun contextBoost(key: String): Double {
            if (previousKey == null) return 0.0
            val count = user.bigramCount(previousKey, key)
            return if (count == 0) 0.0 else 30.0 * ln(1.0 + count)
        }

        // 1. Completions from the bundled dictionary. Short prefixes match thousands of words, so only the
        // best few are kept (a word outside them cannot reach the final list).
        val keep = limit + 2
        val bestIndices = IntArray(keep)
        val bestScores = DoubleArray(keep)
        var kept = 0
        for (index in dictionary.prefixRange(typedKey)) {
            val key = dictionary.key(index)
            val lengthPenalty = (key.length - typedKey.length).coerceAtMost(8) * 1.5
            val exactBonus = if (key == typedKey) EXACT_BONUS else 0.0
            val score = dictionary.frequency(index) + COMPLETION_BONUS + exactBonus - lengthPenalty + user.boost(key) + contextBoost(key)
            if (kept == keep && score <= bestScores[keep - 1]) continue
            var position = if (kept < keep) kept++ else keep - 1
            while (position > 0 && bestScores[position - 1] < score) {
                bestScores[position] = bestScores[position - 1]
                bestIndices[position] = bestIndices[position - 1]
                position--
            }
            bestScores[position] = score
            bestIndices[position] = index
        }
        for (i in 0 until kept) {
            val index = bestIndices[i]
            offer(dictionary.key(index), dictionary.word(index), bestScores[i])
        }

        // 2. Completions from learned words that are not in the dictionary.
        user.forEachPrefix(typedKey) { key, entry ->
            if (entry.count < LEARNED_THRESHOLD && key != typedKey) return@forEachPrefix
            if (dictionary.indexOf(key) >= 0) return@forEachPrefix
            val exactBonus = if (key == typedKey) EXACT_BONUS else 0.0
            offer(key, entry.display, LEARNED_BASE + COMPLETION_BONUS + user.boost(key) + exactBonus + contextBoost(key))
        }

        // 3. Corrections for typos when completions are scarce or weak.
        val bestPrefixScore = candidates.values.maxOfOrNull { it.score } ?: 0.0
        if (typedKey.length >= 2 && (candidates.size < limit || bestPrefixScore < STRONG_SCORE)) {
            collectCorrections(typedKey) { key, display, frequency, kind ->
                offer(key, display, frequency - kind.penalty + user.boost(key) + contextBoost(key))
            }
        }

        val ordered = candidates.entries
            .sortedByDescending { it.value.score }
            .take(limit + 1)
            .map { TextRules.matchCase(it.value.display, typed) }
            .distinct()
        val known = isKnown(typed)
        val withoutTyped = ordered.filter { it != typed || known }
        return Suggestions(typed, withoutTyped.take(limit), known)
    }

    /** A replacement for [typed] confident enough to apply automatically, if any. */
    fun autocorrection(typed: String, previousWord: String?): String? {
        if (typed.length < 3 || isKnown(typed) || typed.any { !it.isLetter() && !TextRules.isApostrophe(it) }) return null
        val typedKey = normalizer.normalize(typed)
        // Never "correct" the beginning of a real word.
        if (!dictionary.prefixRange(typedKey).isEmpty()) return null
        val previousKey = previousWord?.let { normalizer.normalize(it) }
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        collectCorrections(typedKey) { key, display, frequency, kind ->
            if (kind != Correction.FULL) return@collectCorrections
            val context = if (previousKey != null) 30.0 * ln(1.0 + user.bigramCount(previousKey, key)) else 0.0
            val score = frequency + user.boost(key) + context
            if (score > bestScore) {
                bestScore = score
                best = display
            }
        }
        val candidate = best ?: return null
        return if (bestScore >= AUTOCORRECT_MIN_SCORE) TextRules.matchCase(candidate, typed) else null
    }

    fun predictNext(previousWord: String?, limit: Int = 3): List<String> {
        if (previousWord.isNullOrEmpty()) return emptyList()
        return user.nextWords(previousWord, limit)
    }

    private enum class Correction(val penalty: Double) { FULL(55.0), PREFIX(72.0), DOUBLE(105.0) }

    private val rowA = IntArray(MAX_KEY_LENGTH + 1)
    private val rowB = IntArray(MAX_KEY_LENGTH + 1)
    private val rowC = IntArray(MAX_KEY_LENGTH + 1)

    private fun collectCorrections(typedKey: String, sink: (key: String, display: String, frequency: Int, kind: Correction) -> Unit) {
        val typedLength = typedKey.length
        if (typedLength > MAX_KEY_LENGTH) return
        val maxDistance = if (typedLength >= 7) 2 else 1
        val allowShorter = typedLength >= 5
        val first = typedKey[0]
        val second = if (typedLength > 1) typedKey[1] else first
        val typedMask = WordDictionary.maskOf(typedKey)
        val maxMaskDifference = 2 * maxDistance
        for (index in 0 until dictionary.size) {
            val keyLength = dictionary.keyLength(index)
            if (keyLength > MAX_KEY_LENGTH || keyLength < 2) continue
            if (keyLength < typedLength && (!allowShorter || typedLength - keyLength > maxDistance)) continue
            // Typos rarely hit the first letter; also accept a swap of the first two letters.
            val keyFirst = dictionary.firstChar(index)
            val firstMatches = keyFirst == first || (keyFirst == second && dictionary.secondChar(index) == first)
            if (!firstMatches && !(allowShorter && abs(keyLength - typedLength) <= 1)) continue
            val key = dictionary.key(index)
            var kind: Correction? = null
            if (abs(keyLength - typedLength) <= maxDistance &&
                java.lang.Long.bitCount(typedMask xor dictionary.mask(index)) <= maxMaskDifference
            ) {
                when (distance(typedKey, key, keyLength, maxDistance)) {
                    0 -> continue
                    1 -> kind = Correction.FULL
                    2 -> if (maxDistance >= 2) kind = Correction.DOUBLE
                }
            }
            if (kind == null && keyLength > typedLength && typedLength >= 3 && firstMatches && distance(typedKey, key, typedLength, 1) == 1) {
                // A typo inside an unfinished word: correct the prefix and complete it.
                kind = Correction.PREFIX
            }
            if (kind != null) sink(key, dictionary.word(index), dictionary.frequency(index), kind)
        }
        user.forEachWord { key, entry ->
            if (entry.count < LEARNED_THRESHOLD || key.length > MAX_KEY_LENGTH) return@forEachWord
            if (abs(key.length - typedLength) <= 1 && distance(typedKey, key, key.length, 1) == 1) {
                sink(key, entry.display, LEARNED_BASE.toInt(), Correction.FULL)
            }
        }
    }

    /** [boundedDistance] over reusable buffers, comparing [a] with the first [bLength] chars of [b]. */
    private fun distance(a: String, b: String, bLength: Int, maxDistance: Int): Int =
        if (maxDistance == 1) distanceUpToOne(a, b, bLength) else boundedDistance(a, b, maxDistance, bLength, rowA, rowB, rowC)

    companion object {
        private const val EXACT_BONUS = 40.0
        private const val LEARNED_BASE = 150.0
        private const val LEARNED_THRESHOLD = 2
        private const val STRONG_SCORE = 110.0
        private const val COMPLETION_BONUS = 30.0
        private const val MAX_KEY_LENGTH = 48
        private const val AUTOCORRECT_MIN_SCORE = 120.0

        /** Optimal-string-alignment distance, returning maxDistance + 1 as soon as it is exceeded. */
        /** Linear-time check: 0 if equal, 1 for one edit or adjacent swap, 2 otherwise. */
        fun distanceUpToOne(a: String, b: String, bLength: Int = b.length): Int {
            val n = a.length
            val m = bLength
            if (abs(n - m) > 1) return 2
            var i = 0
            while (i < n && i < m && a[i] == b[i]) i++
            if (i == n && i == m) return 0
            fun tailEquals(aFrom: Int, bFrom: Int): Boolean {
                if (n - aFrom != m - bFrom) return false
                for (k in 0 until n - aFrom) if (a[aFrom + k] != b[bFrom + k]) return false
                return true
            }
            return when {
                n == m -> when {
                    tailEquals(i + 1, i + 1) -> 1
                    i + 1 < n && a[i] == b[i + 1] && a[i + 1] == b[i] && tailEquals(i + 2, i + 2) -> 1
                    else -> 2
                }
                n == m + 1 -> if (tailEquals(i + 1, i)) 1 else 2
                else -> if (tailEquals(i, i + 1)) 1 else 2
            }
        }

        fun boundedDistance(
            a: String,
            b: String,
            maxDistance: Int,
            bLength: Int = b.length,
            rowA: IntArray = IntArray(bLength + 1),
            rowB: IntArray = IntArray(bLength + 1),
            rowC: IntArray = IntArray(bLength + 1),
        ): Int {
            if (abs(a.length - bLength) > maxDistance) return maxDistance + 1
            val n = a.length
            val m = bLength
            var previousPrevious = rowA
            var previous = rowB
            var current = rowC
            for (j in 0..m) previous[j] = j
            for (i in 1..n) {
                current[0] = i
                var rowMin = i
                val ca = a[i - 1]
                for (j in 1..m) {
                    val cb = b[j - 1]
                    var value = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + if (ca == cb) 0 else 1)
                    if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                        value = min(value, previousPrevious[j - 2] + 1)
                    }
                    current[j] = value
                    if (value < rowMin) rowMin = value
                }
                if (rowMin > maxDistance) return maxDistance + 1
                val recycled = previousPrevious
                previousPrevious = previous
                previous = current
                current = recycled
            }
            return previous[m].coerceAtMost(maxDistance + 1)
        }
    }
}
