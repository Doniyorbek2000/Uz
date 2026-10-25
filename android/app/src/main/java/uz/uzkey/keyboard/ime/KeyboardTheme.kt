package uz.uzkey.keyboard.ime

import android.content.Context
import android.content.res.Configuration
import uz.uzkey.keyboard.settings.ThemeChoice

data class KeyboardTheme(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val special: Int,
    val specialPressed: Int,
    val text: Int,
    val subText: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val shadow: Int,
    val popup: Int,
    val divider: Int,
    val isDark: Boolean,
) {
    companion object {
        val LIGHT = KeyboardTheme(
            background = 0xFFE4E7EC.toInt(), key = 0xFFFFFFFF.toInt(), keyPressed = 0xFFD5DAE1.toInt(),
            special = 0xFFC6CCD5.toInt(), specialPressed = 0xFFB0B7C2.toInt(), text = 0xFF1A1D22.toInt(),
            subText = 0xFF6A717C.toInt(), accent = 0xFF0B8BC4.toInt(), accentPressed = 0xFF0975A6.toInt(),
            onAccent = 0xFFFFFFFF.toInt(), shadow = 0x33303A48, popup = 0xFFFFFFFF.toInt(),
            divider = 0xFFC9CED6.toInt(), isDark = false,
        )
        val DARK = KeyboardTheme(
            background = 0xFF16181C.toInt(), key = 0xFF2E3137.toInt(), keyPressed = 0xFF41454D.toInt(),
            special = 0xFF22252A.toInt(), specialPressed = 0xFF3A3E45.toInt(), text = 0xFFEEF0F3.toInt(),
            subText = 0xFF9CA3AD.toInt(), accent = 0xFF34A7DB.toInt(), accentPressed = 0xFF2B8DBA.toInt(),
            onAccent = 0xFF06131A.toInt(), shadow = 0x66000000, popup = 0xFF3A3D44.toInt(),
            divider = 0xFF33363C.toInt(), isDark = true,
        )
        val BLACK = KeyboardTheme(
            background = 0xFF000000.toInt(), key = 0xFF1B1B1D.toInt(), keyPressed = 0xFF333336.toInt(),
            special = 0xFF0E0E10.toInt(), specialPressed = 0xFF2A2A2D.toInt(), text = 0xFFFFFFFF.toInt(),
            subText = 0xFF8E8E93.toInt(), accent = 0xFF34A7DB.toInt(), accentPressed = 0xFF2B8DBA.toInt(),
            onAccent = 0xFF000000.toInt(), shadow = 0x00000000, popup = 0xFF2C2C2F.toInt(),
            divider = 0xFF222224.toInt(), isDark = true,
        )
        val BLUE = KeyboardTheme(
            background = 0xFF0D2742.toInt(), key = 0xFF1A3B5E.toInt(), keyPressed = 0xFF2A5380.toInt(),
            special = 0xFF13304F.toInt(), specialPressed = 0xFF234870.toInt(), text = 0xFFFFFFFF.toInt(),
            subText = 0xFFA6C0DD.toInt(), accent = 0xFF1EB53A.toInt(), accentPressed = 0xFF189A31.toInt(),
            onAccent = 0xFFFFFFFF.toInt(), shadow = 0x55000000, popup = 0xFF24496F.toInt(),
            divider = 0xFF1D3A5A.toInt(), isDark = true,
        )

        fun resolve(context: Context, choice: ThemeChoice): KeyboardTheme = when (choice) {
            ThemeChoice.LIGHT -> LIGHT
            ThemeChoice.DARK -> DARK
            ThemeChoice.BLACK -> BLACK
            ThemeChoice.BLUE -> BLUE
            ThemeChoice.SYSTEM -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            }
        }
    }
}
