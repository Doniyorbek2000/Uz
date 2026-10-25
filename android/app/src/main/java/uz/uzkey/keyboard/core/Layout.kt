package uz.uzkey.keyboard.core

import org.json.JSONArray
import org.json.JSONObject

enum class KeyType { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, SYMBOLS, SYMBOLS_MORE, LETTERS, LANGUAGE, EMOJI }

data class KeySpec(
    val type: KeyType,
    val label: String = "",
    val output: String = label,
    val alternates: List<String> = emptyList(),
    val weight: Float = 1f,
    val flex: Boolean = false,
    val hint: String? = null,
)

data class LayoutSpec(
    val id: String,
    val name: String,
    val spaceLabel: String,
    val shortLabel: String,
    val locale: String,
    val dictionary: String,
    val legacyConversion: Boolean,
    val ownBottomRow: Boolean,
    val rows: List<List<KeySpec>>,
) {
    val isUzbek: Boolean get() = id.startsWith("uz")
}

object LayoutIds {
    const val UZ_LATN_2027 = "uz_latn_2027"
    const val UZ_LATN = "uz_latn"
    const val UZ_CYRL = "uz_cyrl"
    const val RU = "ru"
    const val EN = "en"
    const val SYMBOLS = "symbols"
    const val SYMBOLS_MORE = "symbols2"
    const val NUMPAD = "numpad"

    val LANGUAGES = listOf(UZ_LATN_2027, UZ_LATN, UZ_CYRL, RU, EN)
    val DEFAULT_ENABLED = listOf(UZ_LATN_2027, RU, EN)
}

object LayoutParser {

    fun parse(json: String): LayoutSpec {
        val root = JSONObject(json)
        val alternates = parseAlternates(root.optJSONObject("alternates"))
        val rowsJson = root.getJSONArray("rows")
        val rows = (0 until rowsJson.length()).map { rowIndex ->
            val rowJson = rowsJson.getJSONArray(rowIndex)
            (0 until rowJson.length()).map { parseKey(rowJson.get(it), alternates) }
        }
        return LayoutSpec(
            id = root.getString("id"),
            name = root.getString("name"),
            spaceLabel = root.optString("spaceLabel", ""),
            shortLabel = root.optString("short", ""),
            locale = root.optString("locale", ""),
            dictionary = root.optString("dictionary", ""),
            legacyConversion = root.optBoolean("legacyConversion", false),
            ownBottomRow = root.optBoolean("ownBottomRow", false),
            rows = rows,
        )
    }

    private fun parseAlternates(json: JSONObject?): Map<String, List<String>> {
        if (json == null) return emptyMap()
        val result = HashMap<String, List<String>>()
        val names = json.keys()
        while (names.hasNext()) {
            val name = names.next()
            result[name] = json.getJSONArray(name).toStringList()
        }
        return result
    }

    private fun parseKey(item: Any, alternates: Map<String, List<String>>): KeySpec = when (item) {
        is String -> KeySpec(KeyType.CHAR, item, item, alternates[item].orEmpty())
        is JSONObject -> parseKeyObject(item, alternates)
        else -> throw IllegalArgumentException("Unsupported key definition: $item")
    }

    private fun parseKeyObject(item: JSONObject, alternates: Map<String, List<String>>): KeySpec {
        val explicitWeight = item.optDouble("w", Double.NaN)
        val weight = if (explicitWeight.isNaN()) 1f else explicitWeight.toFloat()
        if (item.has("k")) {
            val text = item.getString("k")
            val alt = item.optJSONArray("alt")?.toStringList() ?: alternates[text].orEmpty()
            return KeySpec(KeyType.CHAR, text, text, alt, weight)
        }
        val type = when (val token = item.getString("t")) {
            "shift" -> KeyType.SHIFT
            "bksp" -> KeyType.BACKSPACE
            "space" -> KeyType.SPACE
            "enter" -> KeyType.ENTER
            "sym1" -> KeyType.SYMBOLS
            "sym2" -> KeyType.SYMBOLS_MORE
            "letters" -> KeyType.LETTERS
            "lang" -> KeyType.LANGUAGE
            "emoji" -> KeyType.EMOJI
            else -> throw IllegalArgumentException("Unknown key type: $token")
        }
        val flex = explicitWeight.isNaN() && (type == KeyType.SHIFT || type == KeyType.BACKSPACE)
        return KeySpec(type, item.optString("l", ""), "", emptyList(), weight, flex)
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
}
