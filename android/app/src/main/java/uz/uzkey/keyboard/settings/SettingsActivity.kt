package uz.uzkey.keyboard.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.uzkey.keyboard.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uz.uzkey.keyboard.core.KeyType
import uz.uzkey.keyboard.core.LayoutIds
import uz.uzkey.keyboard.core.LayoutParser
import uz.uzkey.keyboard.ime.KeySoundPlayer
import uz.uzkey.keyboard.ime.KeyboardTheme

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: KeyboardSettings
    private var keyboardEnabled by mutableStateOf(false)
    private var keyboardSelected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)
        val layoutNames = LayoutIds.LANGUAGES.associateWith { id ->
            assets.open("layouts/$id.json").bufferedReader().use { LayoutParser.parse(it.readText()) }.let { it.name to it.shortLabel }
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        setContent {
            UzKeyTheme {
                SettingsScreen(
                    settings = settings,
                    layoutNames = layoutNames,
                    version = version,
                    keyboardEnabled = keyboardEnabled,
                    keyboardSelected = keyboardSelected,
                    onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onSelect = { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() },
                    onClearLearned = {
                        filesDir.listFiles { file -> file.name.startsWith("user_words_") }?.forEach { it.delete() }
                        settings.userDictionaryEpoch = System.currentTimeMillis()
                        Toast.makeText(this, R.string.clear_learned_done, Toast.LENGTH_SHORT).show()
                    },
                    onLastLanguage = { Toast.makeText(this, R.string.languages_min_one, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    private fun refreshStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        keyboardEnabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        keyboardSelected = current?.let { ComponentName.unflattenFromString(it)?.packageName } == packageName
    }
}

private val Brand = Color(0xFF0B8BC4)
private val BrandDark = Color(0xFF0B6FA4)
private val BrandGreen = Color(0xFF1EB53A)

@Composable
private fun UzKeyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors: ColorScheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF5CC3EE), onPrimary = Color(0xFF00283A), secondary = BrandGreen,
            background = Color(0xFF0F1114), surface = Color(0xFF181B20), surfaceVariant = Color(0xFF22262C),
            onBackground = Color(0xFFEEF0F3), onSurface = Color(0xFFEEF0F3), onSurfaceVariant = Color(0xFFA8B0BA),
        )
    } else {
        lightColorScheme(
            primary = Brand, onPrimary = Color.White, secondary = BrandGreen,
            background = Color(0xFFF4F6F9), surface = Color.White, surfaceVariant = Color(0xFFE9EDF2),
            onBackground = Color(0xFF14171B), onSurface = Color(0xFF14171B), onSurfaceVariant = Color(0xFF5D6672),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun SettingsScreen(
    settings: KeyboardSettings,
    layoutNames: Map<String, Pair<String, String>>,
    version: String,
    keyboardEnabled: Boolean,
    keyboardSelected: Boolean,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
    onClearLearned: () -> Unit,
    onLastLanguage: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Header() }
        item {
            SetupCard(keyboardEnabled, keyboardSelected, onEnable, onSelect)
        }
        item { TryField() }
        item { LanguagesCard(settings, layoutNames, onLastLanguage) }
        item { ThemeCard(settings) }
        item { TypingCard(settings) }
        item { KeysCard(settings) }
        item { SoundCard(settings) }
        item { DictionaryCard(onClearLearned) }
        item { TipsCard() }
        item { PrivacyCard() }
        item {
            Text(
                text = stringResource(R.string.version, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(BrandDark, Brand, Color(0xFF14A6C9)))),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Ö", color = BrandDark, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("UzKey", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.tagline), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Özbekça", "Oʻzbekcha", "Ўзбекча", "Русский", "English").forEach { Badge(it) }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

@Composable
private fun SetupCard(enabled: Boolean, selected: Boolean, onEnable: () -> Unit, onSelect: () -> Unit) {
    SectionCard(stringResource(R.string.setup_title)) {
        Column(Modifier.padding(16.dp)) {
            if (enabled && selected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepBadge(text = "✓", done = true)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            } else {
                SetupStep(
                    number = "1",
                    title = stringResource(R.string.step_enable_title),
                    description = stringResource(R.string.step_enable_desc),
                    button = stringResource(R.string.step_enable_button),
                    done = enabled,
                    active = !enabled,
                    onClick = onEnable,
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SetupStep(
                    number = "2",
                    title = stringResource(R.string.step_select_title),
                    description = stringResource(R.string.step_select_desc),
                    button = stringResource(R.string.step_select_button),
                    done = selected,
                    active = enabled && !selected,
                    onClick = onSelect,
                )
            }
        }
    }
}

@Composable
private fun StepBadge(text: String, done: Boolean) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (done) BrandGreen else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (done) Color.White else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SetupStep(number: String, title: String, description: String, button: String, done: Boolean, active: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepBadge(text = if (done) "✓" else number, done = done)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (done) {
            Text(stringResource(R.string.done_badge), color = BrandGreen, fontWeight = FontWeight.SemiBold)
        } else if (active) {
            Button(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text(button) }
        } else {
            OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text(button) }
        }
    }
}

@Composable
private fun TryField() {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.try_hint)) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
    }
}

@Composable
private fun LanguagesCard(settings: KeyboardSettings, names: Map<String, Pair<String, String>>, onLastLanguage: () -> Unit) {
    var enabled by remember { mutableStateOf(settings.enabledLayouts.toSet()) }
    SectionCard(stringResource(R.string.languages_title)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.languages_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LayoutIds.LANGUAGES.forEach { id ->
                val (name, short) = names[id] ?: (id to id)
                val checked = id in enabled
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = if (checked) enabled - id else enabled + id
                            if (next.isEmpty()) {
                                onLastLanguage()
                            } else {
                                enabled = next
                                settings.enabledLayouts = next.toList()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        short,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(settings: KeyboardSettings) {
    var selected by remember { mutableStateOf(settings.theme) }
    val options = listOf(
        ThemeChoice.SYSTEM to R.string.theme_system,
        ThemeChoice.LIGHT to R.string.theme_light,
        ThemeChoice.DARK to R.string.theme_dark,
        ThemeChoice.BLACK to R.string.theme_black,
        ThemeChoice.BLUE to R.string.theme_blue,
    )
    val systemDark = isSystemInDarkTheme()
    SectionCard(stringResource(R.string.theme_title)) {
        LazyRow(
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(options) { (choice, label) ->
                val palette = when (choice) {
                    ThemeChoice.SYSTEM -> if (systemDark) KeyboardTheme.DARK else KeyboardTheme.LIGHT
                    ThemeChoice.LIGHT -> KeyboardTheme.LIGHT
                    ThemeChoice.DARK -> KeyboardTheme.DARK
                    ThemeChoice.BLACK -> KeyboardTheme.BLACK
                    ThemeChoice.BLUE -> KeyboardTheme.BLUE
                }
                ThemePreview(palette, stringResource(label), choice == selected) {
                    selected = choice
                    settings.theme = choice
                }
            }
        }
    }
}

@Composable
private fun ThemePreview(palette: KeyboardTheme, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    RoundedCornerShape(14.dp),
                )
                .background(Color(palette.background))
                .clickable(onClick = onClick)
                .padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                    val count = if (row == 2) 3 else 5
                    repeat(count) { index ->
                        val color = when {
                            row == 2 && index == 2 -> palette.accent
                            row == 2 && index == 0 -> palette.special
                            else -> palette.key
                        }
                        Box(
                            Modifier
                                .weight(if (row == 2 && index == 1) 3f else 1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(color)),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                checked = !checked
                onChange(checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = {
            checked = it
            onChange(it)
        })
    }
}

@Composable
private fun TypingCard(settings: KeyboardSettings) {
    SectionCard(stringResource(R.string.typing_title)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SwitchRow(stringResource(R.string.pref_suggestions), stringResource(R.string.pref_suggestions_desc), settings.showSuggestions) { settings.showSuggestions = it }
            SwitchRow(stringResource(R.string.pref_gesture), stringResource(R.string.pref_gesture_desc), settings.gestureTyping) { settings.gestureTyping = it }
            SwitchRow(stringResource(R.string.pref_clipboard), stringResource(R.string.pref_clipboard_desc), settings.clipboardHistory) { settings.clipboardHistory = it }
            SwitchRow(stringResource(R.string.pref_autocorrect), stringResource(R.string.pref_autocorrect_desc), settings.autoCorrect) { settings.autoCorrect = it }
            SwitchRow(stringResource(R.string.pref_learn), stringResource(R.string.pref_learn_desc), settings.learnWords) { settings.learnWords = it }
            SwitchRow(stringResource(R.string.pref_autocap), null, settings.autoCapitalize) { settings.autoCapitalize = it }
            SwitchRow(stringResource(R.string.pref_double_space), stringResource(R.string.pref_double_space_desc), settings.doubleSpacePeriod) { settings.doubleSpacePeriod = it }
            SwitchRow(stringResource(R.string.pref_apostrophe), stringResource(R.string.pref_apostrophe_desc), settings.apostropheFix) { settings.apostropheFix = it }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeysCard(settings: KeyboardSettings) {
    var height by remember { mutableStateOf(settings.keyHeight) }
    SectionCard(stringResource(R.string.keys_title)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SwitchRow(stringResource(R.string.pref_number_row), null, settings.numberRow) { settings.numberRow = it }
            SwitchRow(stringResource(R.string.pref_number_hints), stringResource(R.string.pref_number_hints_desc), settings.numberHints) { settings.numberHints = it }
            SwitchRow(stringResource(R.string.pref_popup), null, settings.keyPopup) { settings.keyPopup = it }
            SwitchRow(stringResource(R.string.pref_vibration), null, settings.vibration) { settings.vibration = it }
            Text(
                stringResource(R.string.key_height_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            val options = listOf(
                KeyHeight.SMALL to R.string.key_height_small,
                KeyHeight.NORMAL to R.string.key_height_normal,
                KeyHeight.LARGE to R.string.key_height_large,
            )
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = height == value,
                        onClick = {
                            height = value
                            settings.keyHeight = value
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) {
                        Text(stringResource(label))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SoundCard(settings: KeyboardSettings) {
    val context = LocalContext.current
    val player = remember { KeySoundPlayer(context.applicationContext) }
    DisposableEffect(player) {
        player.preload(settings.soundPack)
        onDispose { player.release() }
    }
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var enabled by remember { mutableStateOf(settings.keySound) }
    var pack by remember { mutableStateOf(settings.soundPack) }
    var volume by remember { mutableFloatStateOf(settings.soundVolume.toFloat()) }

    // A short typed phrase lets the character of a pack come through (and plays a tune with musical ones).
    fun preview(id: String) {
        previewJob?.cancel()
        previewJob = scope.launch {
            for ((type, label) in PREVIEW_STROKES) {
                player.play(id, type, label, volume / 100f, ignoreRingerMode = true)
                delay(if (type == KeyType.SPACE) 170 else 120)
            }
        }
    }

    SectionCard(stringResource(R.string.sound_pack_title)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SwitchRow(stringResource(R.string.pref_sound), stringResource(R.string.pref_sound_desc), enabled) {
                enabled = it
                settings.keySound = it
                if (it) preview(pack)
            }
            if (enabled) {
                Text(
                    stringResource(R.string.sound_preview),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    player.packIds.forEach { id ->
                        val (emoji, name) = soundPackLabel(id)
                        FilterChip(
                            selected = pack == id,
                            onClick = {
                                pack = id
                                settings.soundPack = id
                                player.preload(id)
                                preview(id)
                            },
                            label = { Text("$emoji  ${stringResource(name)}") },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                ) {
                    Text(stringResource(R.string.sound_volume), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${volume.roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = {
                        settings.soundVolume = volume.roundToInt()
                        preview(pack)
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
    }
}

private val PREVIEW_STROKES = listOf(
    KeyType.CHAR to "s", KeyType.CHAR to "a", KeyType.CHAR to "l", KeyType.CHAR to "o", KeyType.CHAR to "m",
    KeyType.SPACE to null, KeyType.BACKSPACE to null, KeyType.ENTER to null,
)

private fun soundPackLabel(id: String): Pair<String, Int> = when (id) {
    "mechanical" -> "⌨️" to R.string.sound_mechanical
    "typewriter" -> "📜" to R.string.sound_typewriter
    "bubble" -> "🫧" to R.string.sound_bubble
    "drop" -> "💧" to R.string.sound_drop
    "soft" -> "☁️" to R.string.sound_soft
    "marimba" -> "🎵" to R.string.sound_marimba
    "retro" -> "👾" to R.string.sound_retro
    else -> "📱" to R.string.sound_system
}

@Composable
private fun DictionaryCard(onClear: () -> Unit) {
    SectionCard(stringResource(R.string.dictionary_title)) {
        Box(Modifier.padding(16.dp)) {
            OutlinedButton(onClick = onClear, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.clear_learned))
            }
        }
    }
}

@Composable
private fun TipsCard() {
    val tips = listOf(
        "〰" to R.string.tip_gesture,
        "⌨️" to R.string.tip_cursor,
        "ö" to R.string.tip_long_press,
        "⇧" to R.string.tip_caps,
        "🌐" to R.string.tip_switch,
        "⇄" to R.string.tip_convert,
    )
    SectionCard(stringResource(R.string.tips_title)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            tips.forEach { (symbol, text) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(symbol, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard() {
    SectionCard(stringResource(R.string.privacy_title)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔒", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
