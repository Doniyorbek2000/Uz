package uz.uzkey.keyboard.ime

import android.content.Context
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject

/** Recently copied texts. Unpinned items expire after a day; kept in credential-encrypted storage. */
class ClipboardHistory(private val context: Context) {

    data class Item(val text: String, val time: Long, val pinned: Boolean)

    private val items = ArrayList<Item>()
    private var loaded = false

    private val preferences
        get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val isUnlocked: Boolean
        get() = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    fun items(now: Long = System.currentTimeMillis()): List<Item> {
        load()
        val before = items.size
        items.removeAll { !it.pinned && now - it.time > MAX_AGE_MS }
        if (items.size != before) save()
        return items.sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.time })
    }

    fun add(text: String, now: Long = System.currentTimeMillis()) {
        val trimmed = text.take(MAX_LENGTH)
        if (trimmed.isBlank()) return
        load()
        val existing = items.firstOrNull { it.text == trimmed }
        items.removeAll { it.text == trimmed }
        items += Item(trimmed, now, existing?.pinned == true)
        val unpinned = items.filter { !it.pinned }.sortedBy { it.time }
        if (unpinned.size > MAX_ITEMS) items.removeAll(unpinned.take(unpinned.size - MAX_ITEMS).toSet())
        save()
    }

    fun remove(text: String) {
        load()
        if (items.removeAll { it.text == text }) save()
    }

    fun togglePin(text: String) {
        load()
        val index = items.indexOfFirst { it.text == text }
        if (index < 0) return
        items[index] = items[index].copy(pinned = !items[index].pinned)
        save()
    }

    fun clearUnpinned() {
        load()
        items.removeAll { !it.pinned }
        save()
    }

    fun clearAll() {
        items.clear()
        if (isUnlocked) preferences.edit().clear().apply()
    }

    private fun load() {
        if (loaded || !isUnlocked) return
        loaded = true
        val json = preferences.getString(KEY_ITEMS, null) ?: return
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                items += Item(item.getString("t"), item.getLong("at"), item.optBoolean("p", false))
            }
        } catch (error: org.json.JSONException) {
            preferences.edit().remove(KEY_ITEMS).apply()
        }
    }

    private fun save() {
        if (!isUnlocked) return
        val array = JSONArray()
        for (item in items) array.put(JSONObject().put("t", item.text).put("at", item.time).put("p", item.pinned))
        preferences.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        private const val FILE_NAME = "uzkey_clipboard"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 25
        private const val MAX_LENGTH = 5_000
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
