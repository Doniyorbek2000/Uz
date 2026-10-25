package uz.uzkey.keyboard.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/** Words and word pairs learned from what the user actually types. Kept per language. */
class UserDictionary(private val normalizer: KeyNormalizer = KeyNormalizer.DEFAULT) {

    class Entry(var display: String, var count: Int, var lastUsed: Long)

    private val words = HashMap<String, Entry>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private var bigramCount = 0

    var isDirty: Boolean = false
        private set

    val wordCount: Int get() = words.size

    fun count(key: String): Int = words[key]?.count ?: 0

    fun bigramCount(previousKey: String, key: String): Int = bigrams[previousKey]?.get(key) ?: 0

    fun learn(previousWord: String?, word: String, now: Long = System.currentTimeMillis()) {
        if (word.length < 2 || word.length > MAX_WORD_LENGTH || word.any { it.isDigit() }) return
        val key = normalizer.normalize(word)
        val entry = words[key]
        // Remember the lower-case spelling unless the word is always capitalised (names, places).
        val display = if (entry != null && entry.display != word && entry.display[0].isUpperCase() != word[0].isUpperCase()) {
            word.lowercase()
        } else {
            word
        }
        if (entry == null) {
            words[key] = Entry(display, 1, now)
        } else {
            entry.display = display
            entry.count = (entry.count + 1).coerceAtMost(MAX_COUNT)
            entry.lastUsed = now
        }
        if (previousWord != null) {
            val previousKey = normalizer.normalize(previousWord)
            val next = bigrams.getOrPut(previousKey) { HashMap() }
            val before = next[key]
            next[key] = ((before ?: 0) + 1).coerceAtMost(MAX_COUNT)
            if (before == null) bigramCount++
        }
        isDirty = true
        prune()
    }

    fun forget(word: String) {
        val key = normalizer.normalize(word)
        if (words.remove(key) != null) isDirty = true
        bigrams.values.forEach { if (it.remove(key) != null) bigramCount-- }
    }

    fun clear() {
        words.clear()
        bigrams.clear()
        bigramCount = 0
        isDirty = true
    }

    fun forEachPrefix(prefix: String, action: (key: String, entry: Entry) -> Unit) {
        for ((key, entry) in words) if (key.startsWith(prefix)) action(key, entry)
    }

    fun forEachWord(action: (key: String, entry: Entry) -> Unit) {
        for ((key, entry) in words) action(key, entry)
    }

    fun displayFor(key: String): String? = words[key]?.display

    /** Most frequent followers of [previousWord], best first. */
    fun nextWords(previousWord: String, limit: Int): List<String> {
        val followers = bigrams[normalizer.normalize(previousWord)] ?: return emptyList()
        return followers.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { words[it.key]?.lastUsed ?: 0L })
            .take(limit)
            .mapNotNull { words[it.key]?.display }
    }

    fun boost(key: String): Double {
        val count = count(key)
        return if (count == 0) 0.0 else 18.0 * ln(1.0 + count)
    }

    fun serialize(): String {
        val wordsJson = JSONArray()
        for ((_, entry) in words) {
            wordsJson.put(JSONArray().put(entry.display).put(entry.count).put(entry.lastUsed))
        }
        val bigramsJson = JSONObject()
        for ((previous, followers) in bigrams) {
            val followerJson = JSONObject()
            for ((key, count) in followers) followerJson.put(key, count)
            bigramsJson.put(previous, followerJson)
        }
        isDirty = false
        return JSONObject().put("version", 1).put("words", wordsJson).put("bigrams", bigramsJson).toString()
    }

    fun restore(json: String) {
        clear()
        val root = JSONObject(json)
        val wordsJson = root.optJSONArray("words") ?: JSONArray()
        for (i in 0 until wordsJson.length()) {
            val item = wordsJson.getJSONArray(i)
            val display = item.getString(0)
            words[normalizer.normalize(display)] = Entry(display, item.getInt(1), item.getLong(2))
        }
        val bigramsJson = root.optJSONObject("bigrams") ?: JSONObject()
        val previousKeys = bigramsJson.keys()
        while (previousKeys.hasNext()) {
            val previous = previousKeys.next()
            val followerJson = bigramsJson.getJSONObject(previous)
            val followers = HashMap<String, Int>()
            val keys = followerJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                followers[key] = followerJson.getInt(key)
            }
            bigramCount += followers.size
            bigrams[previous] = followers
        }
        isDirty = false
    }

    private fun prune() {
        if (words.size > MAX_WORDS) {
            val victims = words.entries
                .sortedWith(compareBy<Map.Entry<String, Entry>> { it.value.count }.thenBy { it.value.lastUsed })
                .take(words.size - MAX_WORDS * 9 / 10)
                .map { it.key }
            victims.forEach { words.remove(it) }
        }
        if (bigramCount > MAX_BIGRAMS) {
            val all = bigrams.flatMap { (previous, followers) -> followers.map { Triple(previous, it.key, it.value) } }
                .sortedBy { it.third }
            for ((previous, key, _) in all.take(bigramCount - MAX_BIGRAMS * 9 / 10)) {
                val followers = bigrams[previous] ?: continue
                followers.remove(key)
                if (followers.isEmpty()) bigrams.remove(previous)
            }
            bigramCount = bigrams.values.sumOf { it.size }
        }
    }

    companion object {
        private const val MAX_WORDS = 6_000
        private const val MAX_BIGRAMS = 20_000
        private const val MAX_COUNT = 10_000
        private const val MAX_WORD_LENGTH = 40
    }
}
