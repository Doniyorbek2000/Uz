package uz.uzkey.keyboard.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import java.io.IOException
import kotlin.random.Random
import uz.uzkey.keyboard.core.KeyType
import uz.uzkey.keyboard.core.SoundPack
import uz.uzkey.keyboard.core.SoundPacks

/**
 * Plays key-click sounds: the system click, or one of the synthesised packs in assets/sounds/.
 * Uses a SoundPool for low latency; sounds are silent while the phone is in silent or vibrate mode.
 */
class KeySoundPlayer(private val context: Context) {

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val packs: List<SoundPack> by lazy { loadPacks() }
    private var pool: SoundPool? = null
    private var pack: SoundPack? = null
    private var keySounds = IntArray(0)
    private var deleteSound = 0
    private var spaceSound = 0
    private var enterSound = 0

    /** Pack ids in display order, the system click first. */
    val packIds: List<String> get() = listOf(SoundPacks.SYSTEM) + packs.map { it.id }

    /**
     * Plays the sound for a key of [type] at [volume] (0–1). [label] lets musical packs give every key its own note.
     * [ignoreRingerMode] is for previews in the settings screen, which the user starts on purpose.
     */
    fun play(packId: String, type: KeyType, label: String?, volume: Float, ignoreRingerMode: Boolean = false) {
        val audio = audioManager ?: return
        if (!ignoreRingerMode && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val level = volume.coerceIn(0f, 1f)
        if (level == 0f) return
        if (packId == SoundPacks.SYSTEM || packs.none { it.id == packId }) {
            val effect = when (type) {
                KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(effect, level)
            return
        }
        val current = prepare(packId) ?: return
        val sound = when (type) {
            KeyType.BACKSPACE -> deleteSound
            KeyType.SPACE -> spaceSound
            KeyType.ENTER -> enterSound
            else -> keySounds.getOrElse(SoundPacks.keyVariant(current, label) { Random.nextInt() }) { 0 }
        }
        // A sound that is still loading returns 0 and is skipped; the next press will play.
        if (sound != 0) pool?.play(sound, level, level, 1, 0, 1f)
    }

    /** Loads a pack ahead of the first key press (SoundPool decodes asynchronously, so a cold press is silent). */
    fun preload(packId: String) {
        if (packId != SoundPacks.SYSTEM) prepare(packId)
    }

    /** Frees the loaded sounds, e.g. when key sounds are turned off. */
    fun release() {
        pool?.release()
        pool = null
        pack = null
        keySounds = IntArray(0)
        deleteSound = 0
        spaceSound = 0
        enterSound = 0
    }

    private fun prepare(packId: String): SoundPack? {
        pack?.let { if (it.id == packId) return it }
        release()
        val selected = packs.firstOrNull { it.id == packId } ?: return null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val newPool = SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(attributes).build()
        fun load(name: String): Int = try {
            context.assets.openFd("sounds/${selected.id}/$name.wav").use { newPool.load(it, 1) }
        } catch (error: IOException) {
            Log.e(TAG, "Missing sound ${selected.id}/$name", error)
            0
        }
        keySounds = IntArray(selected.keyVariants) { load("key${it + 1}") }
        deleteSound = load("delete")
        spaceSound = load("space")
        enterSound = load("enter")
        pool = newPool
        pack = selected
        return selected
    }

    private fun loadPacks(): List<SoundPack> = try {
        SoundPacks.parse(context.assets.open("sounds/packs.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (error: IOException) {
        Log.e(TAG, "Sound packs unavailable", error)
        emptyList()
    }

    private companion object {
        const val TAG = "UzKeySounds"
        const val MAX_STREAMS = 6
    }
}
