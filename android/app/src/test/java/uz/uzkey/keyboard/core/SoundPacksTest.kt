package uz.uzkey.keyboard.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPacksTest {

    private val soundDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/sounds") }
        .first { it.isDirectory }

    private val packs = SoundPacks.parse(File(soundDir, "packs.json").readText())

    @Test
    fun manifestListsThePacks() {
        assertTrue(packs.size >= 5)
        assertTrue(packs.any { it.id == SoundPacks.DEFAULT })
        assertTrue(packs.none { it.id == SoundPacks.SYSTEM })
    }

    @Test
    fun everyPackHasValidShortPcmFiles() {
        for (pack in packs) {
            val names = (1..pack.keyVariants).map { "key$it" } + listOf("delete", "space", "enter")
            for (name in names) {
                val file = File(soundDir, "${pack.id}/$name.wav")
                assertTrue("missing ${pack.id}/$name.wav", file.isFile)
                val header = ByteBuffer.wrap(file.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
                val bytes = file.readBytes()
                assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
                assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
                assertEquals("${pack.id}/$name: PCM", 1, header.getShort(20).toInt())
                assertEquals("${pack.id}/$name: mono", 1, header.getShort(22).toInt())
                assertEquals("${pack.id}/$name: 16-bit", 16, header.getShort(34).toInt())
                val sampleRate = header.getInt(24)
                val seconds = header.getInt(40).toDouble() / (sampleRate * 2)
                // Long sounds overlap into a mess when typing fast.
                assertTrue("${pack.id}/$name lasts $seconds s", seconds in 0.02..0.8)
            }
        }
    }

    @Test
    fun musicalPacksGiveEachKeyAFixedNote() {
        val marimba = packs.first { it.id == "marimba" }
        assertTrue(marimba.variantByKey)
        val first = SoundPacks.keyVariant(marimba, "a") { 0 }
        assertEquals(first, SoundPacks.keyVariant(marimba, "A") { 5 })
        assertTrue((0 until marimba.keyVariants).contains(SoundPacks.keyVariant(marimba, "ş") { 0 }))
        val letters = "salom".map { SoundPacks.keyVariant(marimba, it.toString()) { 0 } }.toSet()
        assertTrue("a word should play more than one note", letters.size > 1)
    }

    @Test
    fun randomPacksStayInRange() {
        val mechanical = packs.first { it.id == "mechanical" }
        for (value in listOf(Int.MIN_VALUE, -7, 0, 1, Int.MAX_VALUE)) {
            assertTrue(SoundPacks.keyVariant(mechanical, "a") { value } in 0 until mechanical.keyVariants)
        }
    }
}
