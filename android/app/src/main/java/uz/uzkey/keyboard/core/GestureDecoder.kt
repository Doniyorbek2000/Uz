package uz.uzkey.keyboard.core

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A point in keyboard coordinates. */
data class GesturePoint(val x: Float, val y: Float)

/**
 * Decodes a swipe across letter keys into dictionary words by comparing the finger path with the
 * ideal path through each candidate's key centres (location-sensitive shape matching).
 */
class GestureDecoder(
    private val dictionary: WordDictionary,
    private val normalizer: KeyNormalizer,
    /** Key output (lower-case, e.g. "a", "oʻ") → key centre. */
    keyCenters: Map<String, GesturePoint>,
    private val keyWidth: Float,
    private val user: UserDictionary? = null,
) {
    private val outputs: List<Pair<String, GesturePoint>> =
        keyCenters.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

    data class Candidate(val word: String, val score: Double)

    fun decode(path: List<GesturePoint>, limit: Int = 4): List<Candidate> {
        if (path.size < 2 || outputs.isEmpty()) return emptyList()
        val sampledPath = resample(path, SAMPLES)
        val pathLength = length(path)
        val startKeys = nearestKeys(path.first(), START_RADIUS)
        val endKeys = nearestKeys(path.last(), END_RADIUS).toSet()
        if (startKeys.isEmpty() || endKeys.isEmpty()) return emptyList()

        val best = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (startKey in startKeys) {
            val range = dictionary.prefixRange(normalizer.normalize(startKey))
            for (index in range) {
                val display = dictionary.word(index)
                val lower = display.lowercase()
                if (!seen.add(lower)) continue
                val keys = keySequence(lower) ?: continue
                if (keys.size < 2 || keys.first() != startKey || keys.last() !in endKeys) continue
                val template = templateFor(keys)
                val templateLength = length(template)
                // Paths much shorter or longer than the template cannot match.
                if (templateLength > 0f && (pathLength < templateLength * 0.45f || pathLength > templateLength * 3.2f + keyWidth)) continue
                val distance = meanDistance(sampledPath, resample(template, SAMPLES)) / keyWidth
                if (distance > MAX_DISTANCE) continue
                val key = dictionary.key(index)
                val learned = user?.count(key) ?: 0
                val score = -distance * DISTANCE_WEIGHT + dictionary.frequency(index) / FREQUENCY_DIVISOR + if (learned > 0) ln(1.0 + learned) else 0.0
                insert(best, Candidate(display, score), limit)
            }
        }
        return best
    }

    private fun insert(best: ArrayList<Candidate>, candidate: Candidate, limit: Int) {
        val position = best.indexOfFirst { it.score < candidate.score }.let { if (it < 0) best.size else it }
        if (position >= limit) return
        best.add(position, candidate)
        if (best.size > limit) best.removeAt(best.lastIndex)
    }

    /** Splits a word into key outputs (longest match first); null when a letter has no key. */
    fun keySequence(word: String): List<String>? {
        val keys = ArrayList<String>(word.length)
        var i = 0
        while (i < word.length) {
            val match = outputs.firstOrNull { (output, _) -> word.startsWith(output, i) } ?: return null
            // Double letters are a single stop on the path.
            if (keys.lastOrNull() != match.first) keys += match.first
            i += match.first.length
        }
        return keys
    }

    private fun center(key: String): GesturePoint = outputs.first { it.first == key }.second

    private fun templateFor(keys: List<String>): List<GesturePoint> = keys.map(::center)

    private fun nearestKeys(point: GesturePoint, radius: Float): List<String> =
        outputs.map { (output, center) -> output to hypot(center.x - point.x, center.y - point.y) }
            .filter { it.second <= keyWidth * radius }
            .sortedBy { it.second }
            .take(3)
            .map { it.first }

    companion object {
        private const val SAMPLES = 32
        private const val START_RADIUS = 1.05f
        private const val END_RADIUS = 1.15f
        private const val MAX_DISTANCE = 1.1
        private const val DISTANCE_WEIGHT = 10.0
        private const val FREQUENCY_DIVISOR = 55.0

        fun length(points: List<GesturePoint>): Float {
            var total = 0f
            for (i in 1 until points.size) total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            return total
        }

        /** Resamples a polyline into [count] points evenly spaced along its length. */
        fun resample(points: List<GesturePoint>, count: Int): List<GesturePoint> {
            if (points.isEmpty()) return emptyList()
            val total = length(points)
            if (points.size == 1 || total == 0f) return List(count) { points[0] }
            val step = total / (count - 1)
            val result = ArrayList<GesturePoint>(count)
            result += points[0]
            var accumulated = 0f
            var previous = points[0]
            var i = 1
            while (i < points.size && result.size < count) {
                val current = points[i]
                val segment = hypot(current.x - previous.x, current.y - previous.y)
                if (segment > 0f && accumulated + segment >= step) {
                    val t = (step - accumulated) / segment
                    val point = GesturePoint(previous.x + t * (current.x - previous.x), previous.y + t * (current.y - previous.y))
                    result += point
                    previous = point
                    accumulated = 0f
                } else {
                    accumulated += segment
                    previous = current
                    i++
                }
            }
            while (result.size < count) result += points.last()
            return result
        }

        fun meanDistance(a: List<GesturePoint>, b: List<GesturePoint>): Double {
            val n = min(a.size, b.size)
            if (n == 0) return Double.MAX_VALUE
            var total = 0.0
            for (i in 0 until n) total += hypot((a[i].x - b[i].x).toDouble(), (a[i].y - b[i].y).toDouble())
            return total / max(1, n)
        }
    }
}
