package com.pianoorchestra

import com.pianoorchestra.audio.SoundFont
import com.pianoorchestra.audio.Synth
import com.pianoorchestra.midi.MidiStreamParser
import com.pianoorchestra.player.Song
import com.pianoorchestra.player.SongPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tests du moteur, sans téléphone : lecture de la SoundFont et des morceaux, rendu audio réel
 * (écrit en WAV dans app/build/test-wav pour pouvoir l'écouter), et mesure de la vitesse.
 */
class EngineTest {

    companion object {
        lateinit var sf: SoundFont
        val assets = File("src/main/assets")

        @BeforeClass @JvmStatic
        fun load() {
            val t0 = System.nanoTime()
            sf = SoundFont(File(assets, "sounds.sf2").readBytes())
            println("SoundFont chargée en ${(System.nanoTime() - t0) / 1_000_000} ms, ${sf.samples.size} échantillons")
        }
    }

    @Test
    fun soundfontHasGeneralMidiPresets() {
        assertTrue(sf.zones(0, 0).isNotEmpty())        // piano
        assertTrue(sf.zones(0, 33).isNotEmpty())       // basse
        assertTrue(sf.zones(0, 48).isNotEmpty())       // cordes
        assertTrue(sf.zones(128, 0).isNotEmpty())      // batterie
        println("Preset 0 : ${sf.presetName(0, 0)}, batterie : ${sf.presetName(128, 0)}")
    }

    @Test
    fun streamParserHandlesRunningStatus() {
        val out = ArrayList<Triple<Int, Int, Int>>()
        val p = MidiStreamParser { s, a, b -> out.add(Triple(s, a, b)) }
        val bytes = byteArrayOf(0x90.toByte(), 60, 100, 64, 90, 0xF8.toByte(), 60, 0, 0xB0.toByte(), 64, 127, 0xC0.toByte(), 5)
        p.feed(bytes, 0, bytes.size)
        assertEquals(listOf(Triple(0x90, 60, 100), Triple(0x90, 64, 90), Triple(0x90, 60, 0), Triple(0xB0, 64, 127), Triple(0xC0, 5, 0)), out)
    }

    @Test
    fun allBuiltInSongsParse() {
        val dir = File(assets, "songs")
        val files = dir.listFiles { f -> f.extension == "mid" }!!.sorted()
        assertEquals(6, files.size)
        for (f in files) {
            val s = Song.parse(f.readBytes(), f.name)
            val melodic = s.parts.filter { it.noteCount > 0 }
            println("${f.name} : ${s.bpm} BPM, ${"%.0f".format(s.duration)} s, ${s.beatsPerBar} temps, " +
                melodic.joinToString { "${it.name}[c${it.channel + 1} p${it.program} n${it.noteCount}]" })
            assertTrue(s.duration > 60)
            assertTrue(melodic.size >= 5)
            assertTrue(s.events.zipWithNext().all { (a, b) -> a.time <= b.time })
            assertTrue("les accents des noms de pistes", s.parts.any { it.name.startsWith("Mélodie") })
        }
    }

    @Test
    fun singleNotesSound() {
        val synth = Synth(sf, 32000)
        val l = FloatArray(32000)
        val r = FloatArray(32000)
        for ((prog, ch) in listOf(0 to 0, 33 to 1, 48 to 2, 73 to 3)) {
            synth.send(0xC0 or ch, prog, 0)
            synth.send(0x90 or ch, 60, 100)
            synth.render(l, r, l.size)
            val peak = l.maxOf { abs(it) }
            println("programme $prog : crête ${"%.3f".format(peak)}")
            assertTrue("le programme $prog doit sonner", peak > 0.01f)
            synth.send(0x80 or ch, 60, 0)
            synth.render(l, r, l.size)
        }
    }

    @Test
    fun renderSongs() {
        val out = File("build/test-wav").apply { mkdirs() }
        for (name in listOf("01_ballade", "02_pop_rock", "06_cinematique")) {
            val song = Song.parse(File(assets, "songs/$name.mid").readBytes(), name)
            val rate = 32000
            val synth = Synth(sf, rate)
            val player = SongPlayer()
            synth.sequencer = player
            player.countIn = true
            player.load(song)
            val melody = song.parts.firstOrNull { "lodie" in it.name }?.index ?: -1
            player.setUserPart(melody)
            player.play()
            val seconds = 45
            val total = rate * seconds
            val l = FloatArray(512)
            val r = FloatArray(512)
            val pcm = ShortArray(total * 2)
            var peak = 0f
            var sumSq = 0.0
            var clipped = 0
            var maxVoices = 0
            var bad = 0
            val t0 = System.nanoTime()
            var done = 0
            while (done < total) {
                val n = minOf(512, total - done)
                synth.render(l, r, n)
                for (i in 0 until n) {
                    val a = l[i]; val b = r[i]
                    if (a.isNaN() || b.isNaN()) bad++
                    peak = maxOf(peak, abs(a), abs(b))
                    sumSq += (a * a + b * b) / 2.0
                    if (abs(a) > 0.98f || abs(b) > 0.98f) clipped++
                    pcm[2 * (done + i)] = (a.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    pcm[2 * (done + i) + 1] = (b.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                }
                maxVoices = maxOf(maxVoices, synth.activeVoices())
                done += n
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val rms = sqrt(sumSq / total)
            println("$name : crête ${"%.3f".format(peak)}, RMS ${"%.3f".format(rms)}, saturé $clipped/$total, " +
                "voix max $maxVoices, rendu ${seconds}s en ${ms} ms (x${"%.1f".format(seconds * 1000.0 / ms)} temps réel)")
            writeWav(File(out, "$name.wav"), pcm, rate)
            assertEquals(0, bad)
            assertTrue("le morceau doit s'entendre", rms > 0.01)
            assertTrue("pas trop de saturation", clipped < total / 100)
        }
    }

    private fun writeWav(f: File, pcm: ShortArray, rate: Int) {
        DataOutputStream(FileOutputStream(f).buffered()).use { o ->
            fun le32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
            fun le16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            o.writeBytes("RIFF"); le32(36 + pcm.size * 2); o.writeBytes("WAVE")
            o.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(rate); le32(rate * 4); le16(4); le16(16)
            o.writeBytes("data"); le32(pcm.size * 2)
            for (s in pcm) le16(s.toInt())
        }
    }
}
