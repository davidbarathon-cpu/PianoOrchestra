package com.pianoorchestra.band

import com.pianoorchestra.audio.Sequencer
import com.pianoorchestra.audio.Synth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Le groupe qui suit : Bermet joue des accords (main gauche), la batterie, la basse, l'accompagnement
 * et la nappe suivent, dans le style choisi. Comme un clavier arrangeur : démarrage au premier accord,
 * variation A/B avec un break, fin propre.
 */
class Band : Sequencer {

    enum class PartKind { DRUMS, BASS, COMP, PAD }

    data class State(
        val running: Boolean = false,
        val armed: Boolean = true,
        val chord: String? = null,
        val style: String = "pop",
        val variation: Int = 0,
        val bpm: Int = 104,
        val beat: Int = 0,
        val bar: Int = 0,
        val parts: Set<PartKind> = PartKind.entries.toSet(),
        val leftHandOnly: Boolean = true,
        val ending: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val commands = ConcurrentLinkedQueue<(Synth) -> Unit>()
    private val live = ConcurrentLinkedQueue<IntArray>()

    // --- fil audio
    private var style = Style.byId("pop")
    private var bpm = style.bpm
    private var running = false
    private var armed = true
    private var variation = 0
    private var pendingVariation = -1
    private var fillFrom = -1
    private var endingRequested = false
    private var endingBar = -1
    private var crashNext = true
    private var parts = PartKind.entries.toMutableSet()
    private var leftHandOnly = true
    private var split = 60
    private val held = BooleanArray(128)
    private var chord: Chord? = null
    private var chordChanged = false

    private var clock = 0.0
    private var step = 0
    private var stepStart = 0.0
    private class Off(val time: Double, val channel: Int, val note: Int)
    private val offs = ArrayList<Off>()
    private val padNotes = ArrayList<Int>()
    private var arpIndex = 0
    private var lastPublishStep = -1

    /** Appelé quand le groupe démarre (pour arrêter le lecteur de morceaux). */
    @Volatile var onStart: (() -> Unit)? = null

    fun setStyle(id: String) = post { synth ->
        style = Style.byId(id)
        bpm = style.bpm
        variation = 0
        if (running) setupPrograms(synth)
        publish()
    }
    fun setTempo(value: Int) = post { bpm = value.coerceIn(40, 240); publish() }
    fun setArmed(on: Boolean) = post { armed = on; publish() }
    fun setLeftHandOnly(on: Boolean) = post { leftHandOnly = on; publish() }
    fun setPart(kind: PartKind, on: Boolean) = post { synth ->
        if (on) parts.add(kind) else { parts.remove(kind); if (kind == PartKind.PAD) releasePad(synth) }
        publish()
    }
    fun startStop() = post { synth -> if (running) stopNow(synth) else startNow(synth) }
    fun stop() = post { synth -> stopNow(synth) }
    fun variation() = post { if (running) { pendingVariation = 1 - variation; fillFrom = maxOf(step % style.steps, style.steps / 2) } else variation = 1 - variation; publish() }
    fun ending() = post { if (running) endingRequested = true; publish() }

    /** Les notes jouées (piano ou clavier à l'écran). */
    fun onLive(status: Int, d1: Int, d2: Int) {
        if ((status and 0x0F) == 9) return
        val kind = status and 0xF0
        if (kind == 0x90 || kind == 0x80) live.add(intArrayOf(status, d1, d2))
    }

    private fun post(cmd: (Synth) -> Unit) { commands.add(cmd) }

    override fun advance(synth: Synth, frames: Int, sampleRate: Int) {
        while (true) {
            val c = commands.poll() ?: break
            c(synth)
        }
        var noteOn = false
        while (true) {
            val m = live.poll() ?: break
            val on = (m[0] and 0xF0) == 0x90 && m[2] > 0
            held[m[1]] = on
            if (on && (!leftHandOnly || m[1] < split)) noteOn = true
        }
        if (noteOn) {
            val notes = (0 until 128).filter { held[it] && (!leftHandOnly || it < split) }
            val c = ChordDetector.detect(notes)
            if (c != null && c != chord) {
                chord = c
                chordChanged = true
                publish()
            }
            if (!running && armed && c != null) startNow(synth)
        }
        if (!running) return

        if (chordChanged) {
            chordChanged = false
            if (PartKind.PAD in parts && style.padProgram >= 0) playPad(synth)
        }
        val tEnd = clock + frames.toDouble() / sampleRate
        val stepDur = 60.0 / bpm / 4
        while (true) {
            val swingOff = if (style.swing && step % 4 == 2) stepDur * 0.33 else 0.0
            if (stepStart + swingOff >= tEnd) break
            doStep(synth, stepStart + swingOff)
            if (!running) break
            step++
            stepStart += stepDur
        }
        // fins de notes
        if (offs.isNotEmpty()) {
            val it = offs.iterator()
            while (it.hasNext()) {
                val o = it.next()
                if (o.time < tEnd) { synth.handle(0x80 or o.channel, o.note, 0); it.remove() }
            }
        }
        clock = tEnd
        if (step != lastPublishStep && step % 4 == 0) { lastPublishStep = step; publish() }
    }

    private fun doStep(synth: Synth, time: Double) {
        val c = chord ?: Chord(0, intArrayOf(0, 4, 7), "")
        val n = style.steps
        val s = step % n
        val bar = step / n
        val stepDur = 60.0 / bpm / 4

        if (s == 0) {
            if (pendingVariation >= 0) { variation = pendingVariation; pendingVariation = -1; fillFrom = -1; crashNext = true }
            if (endingBar >= 0 && bar > endingBar) { stopNow(synth); return }
            if (endingRequested) {
                endingRequested = false
                endingBar = bar
                // la fin : un dernier accord tenu, cymbale et grosse caisse
                if (PartKind.DRUMS in parts) { hit(synth, 9, Style.CRASH, 115, 8.0); hit(synth, 9, Style.KICK, 115, 0.5) }
                if (PartKind.BASS in parts) note(synth, BASS_CH, bassNote(c, 'R', bar), 105, time, stepDur * n)
                if (PartKind.COMP in parts) for (v in voicing(c, 55)) note(synth, COMP_CH, v, 85, time, stepDur * n)
                publish()
                return
            }
            if (crashNext && PartKind.DRUMS in parts) { hit(synth, 9, Style.CRASH, 100, 3.0); crashNext = false }
            publish()
        }
        if (endingBar >= 0) return

        // batterie (avec un break quand la variation change)
        if (PartKind.DRUMS in parts) {
            if (fillFrom >= 0 && s >= fillFrom && pendingVariation >= 0) {
                val toms = intArrayOf(50, 48, 47, 45, 43, 41)
                val k = s - fillFrom
                hit(synth, 9, if (k % 2 == 0) Style.SNARE else toms[(k / 2) % toms.size], 80 + k * 4, 0.3)
                if (s == 0 || k == 0) hit(synth, 9, Style.KICK, 100, 0.3)
            } else {
                for ((inst, pat) in style.drums[variation]) {
                    if (s >= pat.length) continue
                    val vel = when (pat[s]) { 'X' -> 120; 'x' -> 92; 'o' -> 55; else -> 0 }
                    if (vel > 0) hit(synth, 9, inst, vel, 0.3)
                }
            }
        }
        // basse
        if (PartKind.BASS in parts) {
            for ((st, deg, len) in style.bass[variation]) if (st == s) {
                note(synth, BASS_CH, bassNote(c, deg, bar), 100, time, stepDur * len * 0.92)
            }
        }
        // accompagnement
        if (PartKind.COMP in parts) {
            for ((st, len) in style.comp[variation]) if (st == s) {
                val dur = stepDur * len * 0.9
                when (style.compMode) {
                    Style.CompMode.CHORD -> for (v in voicing(c, 55)) note(synth, COMP_CH, v, 78, time, dur)
                    Style.CompMode.ARPEGGIO -> {
                        val v = voicing(c, 55)
                        val line = v + v.map { it + 12 }
                        val k = (s / 2) % (line.size)
                        note(synth, COMP_CH, line[if (k < line.size) k else 0], 72, time, stepDur * 4)
                    }
                    Style.CompMode.POWER -> {
                        val r = 40 + Math.floorMod(c.root - 4, 12)
                        for (v in intArrayOf(r, r + 7, r + 12)) note(synth, COMP_CH, v, 82, time, dur)
                    }
                }
            }
        }
        // nappe : tenue tant que l'accord ne change pas
        if (PartKind.PAD in parts && style.padProgram >= 0 && s == 0 && padNotes.isEmpty()) playPad(synth)
    }

    private fun bassNote(c: Chord, deg: Char, bar: Int): Int {
        val root = 36 + c.root
        val third = if (c.isMinor) 3 else 4
        val fifth = if (6 in c.intervals && 7 !in c.intervals) 6 else if (8 in c.intervals) 8 else 7
        val n = when (deg) {
            '3' -> root + third
            '5' -> root + fifth
            '8' -> root + 12
            'A' -> if (bar % 2 == 0) root else root + fifth - 12
            'C' -> root - 1
            else -> root
        }
        return if (n > 50) n - 12 else if (n < 28) n + 12 else n
    }

    /** Les notes de l'accord rangées dans une octave à partir de [low] (renversement le plus proche). */
    private fun voicing(c: Chord, low: Int): List<Int> =
        c.intervals.map { iv -> low + Math.floorMod(c.root + iv - low, 12) }.distinct().sorted()

    private fun playPad(synth: Synth) {
        releasePad(synth)
        val c = chord ?: return
        for (v in voicing(c, 60)) {
            synth.handle(0x90 or PAD_CH, v, 62)
            padNotes.add(v)
        }
    }

    private fun releasePad(synth: Synth) {
        for (v in padNotes) synth.handle(0x80 or PAD_CH, v, 0)
        padNotes.clear()
    }

    private fun hit(synth: Synth, ch: Int, n: Int, vel: Int, len: Double) {
        synth.handle(0x90 or ch, n, vel.coerceIn(1, 127))
        offs.add(Off(clock + len, ch, n))
    }

    private fun note(synth: Synth, ch: Int, n: Int, vel: Int, time: Double, len: Double) {
        synth.handle(0x90 or ch, n, vel)
        offs.add(Off(time + len, ch, n))
    }

    private fun setupPrograms(synth: Synth) {
        synth.handle(0xC0 or BASS_CH, style.bassProgram, 0)
        synth.handle(0xC0 or COMP_CH, style.compProgram, 0)
        if (style.padProgram >= 0) synth.handle(0xC0 or PAD_CH, style.padProgram, 0)
        synth.handle(0xB0 or 9, 7, 100)
        synth.handle(0xB0 or BASS_CH, 7, 108)
        synth.handle(0xB0 or COMP_CH, 7, if (style.compMode == Style.CompMode.POWER) 80 else 92)
        synth.handle(0xB0 or PAD_CH, 7, 66)
        for (ch in intArrayOf(9, BASS_CH, COMP_CH, PAD_CH)) {
            synth.handle(0xB0 or ch, 11, 127)
            synth.handle(0xB0 or ch, 10, 64)
            synth.channelMix[ch] = 1f
        }
    }

    private fun startNow(synth: Synth) {
        onStart?.let { cb -> Thread { cb() }.start() }
        setupPrograms(synth)
        running = true
        step = 0
        stepStart = clock + 0.02
        crashNext = true
        endingBar = -1
        endingRequested = false
        pendingVariation = -1
        fillFrom = -1
        arpIndex = 0
        if (chord == null) chord = Chord(0, intArrayOf(0, 4, 7), "")
        chordChanged = true
        publish()
    }

    private fun stopNow(synth: Synth) {
        running = false
        releasePad(synth)
        for (o in offs) synth.handle(0x80 or o.channel, o.note, 0)
        offs.clear()
        for (ch in intArrayOf(BASS_CH, COMP_CH, PAD_CH)) synth.handle(0xB0 or ch, 123, 0)
        endingBar = -1
        publish()
    }

    private fun publish() {
        val n = style.steps
        _state.value = State(
            running = running, armed = armed, chord = chord?.name, style = style.id,
            variation = if (pendingVariation >= 0) pendingVariation else variation, bpm = bpm,
            beat = (step % n) / 4, bar = step / n, parts = parts.toSet(), leftHandOnly = leftHandOnly,
            ending = endingRequested || endingBar >= 0,
        )
    }

    companion object {
        const val BASS_CH = 10
        const val COMP_CH = 11
        const val PAD_CH = 12
    }
}
