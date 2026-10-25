package uz.uzkey.keyboard.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import uz.uzkey.keyboard.core.EmojiCategory
import uz.uzkey.keyboard.core.EmojiData
import uz.uzkey.keyboard.core.KeyNormalizer
import uz.uzkey.keyboard.core.LayoutParser
import uz.uzkey.keyboard.core.LayoutSpec
import uz.uzkey.keyboard.core.SuggestionEngine
import uz.uzkey.keyboard.core.UserDictionary
import uz.uzkey.keyboard.core.WordDictionary
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Loads layouts, bundled dictionaries and learned words; dictionaries load off the main thread. */
class DictionaryStore(private val context: Context) {

    private val layouts = HashMap<String, LayoutSpec>()
    private val dictionaries = ConcurrentHashMap<String, WordDictionary>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val users = HashMap<String, UserDictionary>()
    private val engines = HashMap<String, SuggestionEngine>()
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "uzkey-dictionaries").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveAll() }
    private var emoji: List<EmojiCategory>? = null

    /** Called on the main thread when a dictionary finishes loading. */
    var onDictionaryLoaded: (() -> Unit)? = null

    fun layout(id: String): LayoutSpec = layouts.getOrPut(id) {
        LayoutParser.parse(readAsset("layouts/$id.json"))
    }

    fun emojiCategories(): List<EmojiCategory> = emoji ?: EmojiData.parse(readAsset("emoji.json")).also { emoji = it }

    fun engine(dictionaryId: String): SuggestionEngine? {
        if (dictionaryId.isEmpty()) return null
        val dictionary = dictionaries[dictionaryId]
        if (dictionary == null) {
            preload(dictionaryId)
            return null
        }
        return engines.getOrPut(dictionaryId) { SuggestionEngine(dictionary, user(dictionaryId), KeyNormalizer.forDictionary(dictionaryId)) }
    }

    /** The bundled dictionary if it has finished loading (starts loading it otherwise). */
    fun dictionary(dictionaryId: String): WordDictionary? {
        if (dictionaryId.isEmpty()) return null
        return dictionaries[dictionaryId] ?: run {
            preload(dictionaryId)
            null
        }
    }

    fun preload(dictionaryId: String) {
        if (dictionaryId.isEmpty() || dictionaries.containsKey(dictionaryId) || !loading.add(dictionaryId)) return
        executor.execute {
            try {
                val dictionary = context.assets.open("dictionaries/$dictionaryId.txt").use { WordDictionary.load(it, KeyNormalizer.forDictionary(dictionaryId)) }
                dictionaries[dictionaryId] = dictionary
            } catch (error: IOException) {
                Log.e(TAG, "Failed to load dictionary $dictionaryId", error)
                dictionaries[dictionaryId] = WordDictionary.EMPTY
            } finally {
                loading.remove(dictionaryId)
            }
            mainHandler.post { onDictionaryLoaded?.invoke() }
        }
    }

    fun user(dictionaryId: String): UserDictionary = users.getOrPut(dictionaryId) {
        UserDictionary(KeyNormalizer.forDictionary(dictionaryId)).also { dictionary ->
            val file = userFile(dictionaryId) ?: return@also
            if (!file.exists()) return@also
            try {
                dictionary.restore(file.readText())
            } catch (error: Exception) {
                Log.w(TAG, "Discarding unreadable learned words for $dictionaryId", error)
            }
        }
    }

    fun scheduleSave() {
        mainHandler.removeCallbacks(saveRunnable)
        mainHandler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    fun saveAll() {
        mainHandler.removeCallbacks(saveRunnable)
        for ((id, dictionary) in users) {
            if (!dictionary.isDirty) continue
            val file = userFile(id) ?: continue
            val json = dictionary.serialize()
            try {
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(json)
                if (!temp.renameTo(file)) {
                    file.writeText(json)
                    temp.delete()
                }
            } catch (error: IOException) {
                Log.e(TAG, "Failed to save learned words for $id", error)
            }
        }
    }

    fun clearLearnedWords() {
        users.values.forEach { it.clear() }
        context.filesDir.listFiles { file -> file.name.startsWith(USER_FILE_PREFIX) }?.forEach { it.delete() }
    }

    fun shutdown() {
        saveAll()
        executor.shutdown()
    }

    /** Learned words live in credential-encrypted storage, so they are unavailable before first unlock. */
    private fun userFile(dictionaryId: String): File? {
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager != null && !userManager.isUserUnlocked) return null
        return File(context.filesDir, "$USER_FILE_PREFIX$dictionaryId.json")
    }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        private const val TAG = "UzKeyDictionaries"
        private const val USER_FILE_PREFIX = "user_words_"
        private const val SAVE_DELAY_MS = 4_000L
    }
}
