package uz.uzkey.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDecoderTest {

    private val keyWidth = 36f
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val centers: Map<String, GesturePoint> = buildMap {
        rows.forEachIndexed { row, letters ->
            val offset = row * keyWidth / 2
            letters.forEachIndexed { i, c -> put(c.toString(), GesturePoint(offset + i * keyWidth + keyWidth / 2, row * 54f + 27f)) }
        }
    }

    private val dictionary = WordDictionary.load(
        "hello\t200\nhell\t150\nhelp\t190\nhole\t140\ntest\t180\ntext\t170\nthe\t220\nquick\t150\n".byteInputStream(),
    )

    private fun pathThrough(word: String, jitter: Float = 0f): List<GesturePoint> {
        val stops = word.map { centers.getValue(it.toString()) }
        val path = ArrayList<GesturePoint>()
        for (i in 1 until stops.size) {
            val a = stops[i - 1]
            val b = stops[i]
            for (step in 0 until 8) {
                val t = step / 8f
                val wobble = if (step % 2 == 0) jitter else -jitter
                path += GesturePoint(a.x + (b.x - a.x) * t + wobble, a.y + (b.y - a.y) * t - wobble)
            }
        }
        path += stops.last()
        return path
    }

    @Test
    fun decodesCleanAndNoisySwipes() {
        val decoder = GestureDecoder(dictionary, KeyNormalizer.DEFAULT, centers, keyWidth)
        assertEquals("hello", decoder.decode(pathThrough("helo")).first().word)
        assertEquals("test", decoder.decode(pathThrough("test", jitter = 6f)).first().word)
        assertEquals("the", decoder.decode(pathThrough("the", jitter = 5f)).first().word)
        assertTrue(decoder.decode(pathThrough("quick")).map { it.word }.contains("quick"))
    }

    @Test
    fun keySequenceUsesMultiCharacterKeys() {
        val uzCenters = centers + mapOf("oʻ" to GesturePoint(400f, 81f))
        val decoder = GestureDecoder(dictionary, KeyNormalizer.DEFAULT, uzCenters, keyWidth)
        assertEquals(listOf("k", "oʻ", "p"), decoder.keySequence("koʻp"))
        assertEquals(listOf("h", "e", "l", "o"), decoder.keySequence("hello"))
        assertNull(decoder.keySequence("café"))
    }

    @Test
    fun resampleKeepsEndpoints() {
        val points = GestureDecoder.resample(listOf(GesturePoint(0f, 0f), GesturePoint(10f, 0f), GesturePoint(10f, 10f)), 5)
        assertEquals(5, points.size)
        assertEquals(GesturePoint(0f, 0f), points.first())
        assertEquals(10f, points.last().y, 0.01f)
    }
}
