package uz.uzkey.keyboard.core

enum class FieldKind { TEXT, EMAIL, URL, NUMBER, PHONE }

data class BuildOptions(
    val numberRow: Boolean,
    val numberHints: Boolean,
    val fieldKind: FieldKind,
    val showLanguageKey: Boolean,
)

object KeyboardBuilder {

    private val DIGITS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val DIGIT_ROW_ALTERNATES = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val PERIOD_ALTERNATES = listOf(",", "?", "!", "…", "-", ":", ";", "'", "\"", "@", "/")
    private val WEB_ALTERNATES = listOf(".uz", ".com", ".ru", ".org", ".net", "/", "-", "_")

    fun build(layout: LayoutSpec, isLetterLayer: Boolean, options: BuildOptions): List<List<KeySpec>> {
        if (layout.ownBottomRow) return layout.rows
        val rows = ArrayList<List<KeySpec>>(layout.rows.size + 2)
        if (isLetterLayer && options.numberRow) {
            rows += DIGITS.mapIndexed { i, digit -> KeySpec(KeyType.CHAR, digit, digit, listOf(DIGIT_ROW_ALTERNATES[i])) }
            rows += layout.rows
        } else if (isLetterLayer && options.numberHints) {
            rows += layout.rows.first().mapIndexed { index, key ->
                if (key.type == KeyType.CHAR && index < DIGITS.size) {
                    key.copy(hint = DIGITS[index], alternates = listOf(DIGITS[index]) + key.alternates)
                } else {
                    key
                }
            }
            rows += layout.rows.drop(1)
        } else {
            rows += layout.rows
        }
        rows += bottomRow(isLetterLayer, options)
        return rows
    }

    private fun bottomRow(isLetterLayer: Boolean, options: BuildOptions): List<KeySpec> {
        val modeKey = if (isLetterLayer) {
            KeySpec(KeyType.SYMBOLS, "?123", weight = 1.4f)
        } else {
            KeySpec(KeyType.LETTERS, "ABC", weight = 1.4f)
        }
        val secondary = when (options.fieldKind) {
            FieldKind.EMAIL -> KeySpec(KeyType.CHAR, "@", "@", listOf(",", "_", "-"))
            FieldKind.URL -> KeySpec(KeyType.CHAR, "/", "/", listOf(",", ":", "-", "_"))
            else -> KeySpec(KeyType.CHAR, ",", ",", listOf(";", ":", "-"))
        }
        val switchKey = if (options.showLanguageKey) {
            KeySpec(KeyType.LANGUAGE)
        } else {
            KeySpec(KeyType.EMOJI)
        }
        val periodAlternates = when (options.fieldKind) {
            FieldKind.EMAIL, FieldKind.URL -> WEB_ALTERNATES
            else -> PERIOD_ALTERNATES
        }
        return listOf(
            modeKey,
            secondary,
            switchKey,
            KeySpec(KeyType.SPACE, flex = true),
            KeySpec(KeyType.CHAR, ".", ".", periodAlternates),
            KeySpec(KeyType.ENTER, weight = 1.6f),
        )
    }
}
