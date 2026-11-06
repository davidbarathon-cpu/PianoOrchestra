package com.pianoorchestra

import com.pianoorchestra.library.Carnet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Le carnet de Bermet, tel que Riffmaker l'écrit : lecture, et le suivi du son du groupe mesure par mesure. */
class CarnetTest {
    @Test
    fun readsRiffmakerCarnet() {
        val c = Carnet.parse(File("src/test/resources/carnet_test.json").readText())
        println("carnet « ${c.title} » : ${c.sections.size} parties, ${c.totalBars} mesures, ${c.tempo} BPM")
        assertTrue(c.sections.size >= 4)
        assertEquals("Intro", c.sections[0].name)
        assertTrue(c.sections[0].notes.first().startsWith("Seule au piano"))
        assertEquals(c.sections[0].bars, c.sections[0].chords.size)
        assertEquals(c.sections[1].start, c.sections[0].end)
        // 96 BPM, 4 temps : une mesure = 2,5 s
        assertEquals(2.5, c.secondsAtBar(1), 1e-6)
        assertEquals(8.0, c.barAt(c.secondsAtBar(8)), 1e-6)
        assertEquals("Couplet 1", c.sectionAt(8)?.name)
        assertEquals(3.5, c.barAt(3.5 * 2.5), 1e-6)
        assertTrue(c.audioFile!!.endsWith(".wav") && c.scoreFile!!.endsWith(".gp5"))
    }

    @Test
    fun countInAndTempoChange() {
        val json = """{"format":"piano-orchestra-carnet","version":1,"titre":"T","tempo":120,"decompte":1,
            "carte":{"mesures":[[4,4,4],[4,4,4],[4,4,4],[3,3,4],[4,4,4]],"tempos":[[0,120],[8,60]]},
            "sections":[{"nom":"A","debut":0,"mesures":2,"accords":["C","G"]},{"nom":"B","debut":2,"mesures":2,"accords":["Am"]}]}"""
        val c = Carnet.parse(json)
        assertEquals(-1.0, c.barAt(0.0), 1e-9)                 // la mesure de baguettes
        assertEquals(0.0, c.barAt(2.0), 1e-9)                  // 4 noires à 120 = 2 s
        assertEquals(2.0, c.secondsAtBar(0), 1e-9)
        assertEquals(8.0, c.secondsAtBar(2), 1e-9)             // 8 noires à 120 (4 s) puis 4 à 60 (4 s)
        assertEquals(11.0, c.secondsAtBar(3), 1e-9)            // + une mesure de 3 noires à 60 = 3 s
        assertTrue(abs(c.barAt(9.5) - 2.5) < 1e-9)             // au milieu de la mesure de 3 temps
        assertEquals(listOf("Am", ""), c.sections[1].chords)   // complété jusqu'au nombre de mesures
    }
}
