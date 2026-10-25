package uz.uzkey.keyboard.core

import org.json.JSONObject

data class EmojiCategory(val id: String, val icon: String, val emojis: List<String>)

object EmojiData {
    const val RECENT_ID = "recent"
    const val RECENT_ICON = "🕘"

    fun parse(json: String): List<EmojiCategory> {
        val categories = JSONObject(json).getJSONArray("categories")
        return (0 until categories.length()).map { index ->
            val item = categories.getJSONObject(index)
            val emojis = item.getJSONArray("emojis")
            EmojiCategory(item.getString("id"), item.getString("icon"), (0 until emojis.length()).map { emojis.getString(it) })
        }
    }
}
