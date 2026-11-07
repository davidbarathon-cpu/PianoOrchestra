package com.pianoorchestra.compose

import com.pianoorchestra.band.Chord
import com.pianoorchestra.band.ChordName
import com.pianoorchestra.band.Style
import com.pianoorchestra.player.MidiWriter
import kotlin.random.Random

/**
 * L'arrangeur : joue le plan avec le groupe de la tablette (les mêmes styles que le jeu libre) et écrit
 * un fichier MIDI complet — mélodie, piano, basse, batterie, nappe — avec les parties en marqueurs.
 * La mélodie, si Claude ne l'a pas écrite, est inventée ici : des motifs de deux mesures, les notes de
 * l'accord sur les temps forts, des pas de gamme entre les deux ; un couplet revient avec la même mélodie.
 */
object Arranger {

    class Result(val midi: ByteArray, val planJson: String, val seconds: Double)

    private const val MEL = 0; private const val PIANO = 1; private const val BASS = 2; private const val PAD = 3
    private const val GTR = 4; private const val DRUMS = 9

    fun render(plan: Plan): Result {
        val style = Style.byId(plan.style)
        val beats = style.beats
        val beat = 60.0 / plan.tempo
        val stepDur = beat / 4
        val ev = HashMap<Int, ArrayList<MidiWriter.Event>>()
        fun on(ch: Int, t: Double, n: Int, v: Int, len: Double) {
            if (n !in 0..127) return
            val l = ev.getOrPut(ch) { ArrayList() }
            l.add(MidiWriter.Event(t, 0x90, n, v.coerceIn(1, 127)))
            l.add(MidiWriter.Event(t + len.coerceAtLeast(0.03), 0x80, n, 0))
        }
        val pianoIsComp = style.compProgram in 0..7
        val markers = ArrayList<Pair<Double, String>>()
        var bar0 = 0
        val scale = if (plan.minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val melodies = HashMap<String, List<Plan.Note>>()
        var lastPitch = 67 + plan.key % 5

        plan.parts.forEachIndexed { pi, part ->
            val t0 = bar0 * beats * beat
            markers.add(t0 to part.name)
            val next = plan.parts.getOrNull(pi + 1)
            val variation = if (part.energy >= 3) 1 else 0
            for (b in 0 until part.bars) {
                val barStart = t0 + b * beats * beat
                val names = (part.chords.getOrNull(b) ?: "").split(' ').filter { it.isNotBlank() }
                val chords = names.mapNotNull { ChordName.parse(it) }.ifEmpty { listOf(Chord(plan.key, if (plan.minor) intArrayOf(0, 3, 7) else intArrayOf(0, 4, 7), "") to null) }
                fun chordAtStep(s: Int) = chords[(s * chords.size) / style.steps]
                val lastBar = b == part.bars - 1
                val fill = lastBar && next != null && next.energy >= part.energy && part.drums && part.energy >= 2
                for (s in 0 until style.steps) {
                    val swing = if (style.swing && s % 4 == 2) stepDur * 0.33 else 0.0
                    val t = barStart + s * stepDur + swing
                    val (c, slashBass) = chordAtStep(s)
                    // batterie
                    if (part.drums) {
                        if (fill && s >= style.steps - 4) {
                            val toms = intArrayOf(50, 48, 45, 43)
                            on(DRUMS, t, if (s % 2 == 0) Style.SNARE else toms[(s / 2) % toms.size], 80 + (s % 4) * 8, 0.2)
                        } else if (part.energy == 1) {
                            if (s == 0) on(DRUMS, t, Style.KICK, 70, 0.2)
                            if (s % 4 == 0) on(DRUMS, t, if (plan.style == "jazz") Style.RIDE else Style.HAT, 45, 0.2)
                        } else {
                            for ((inst, pat) in style.drums[variation]) {
                                if (s >= pat.length) continue
                                val vel = when (pat[s]) { 'X' -> 118; 'x' -> 90; 'o' -> 55; else -> 0 }
                                if (vel > 0) on(DRUMS, t, inst, vel - (3 - part.energy) * 8, 0.2)
                            }
                        }
                        if (s == 0 && b == 0 && part.energy >= 3) on(DRUMS, t, Style.CRASH, 105, 2.0)
                    }
                    // basse
                    if (part.bass) {
                        if (part.energy == 1) {
                            if (s == 0 || (chords.size > 1 && s == style.steps / 2)) on(BASS, t, bassNote(c, slashBass, 'R', b), 85, beat * beats / chords.size * 0.95)
                        } else for ((st, deg, len) in style.bass[variation]) if (st == s) {
                            on(BASS, t, bassNote(c, slashBass, deg, b), 92 + part.energy * 4, stepDur * len * 0.92)
                        }
                    }
                    // accompagnement (piano, ou guitare pour le rock et la bossa)
                    if (part.comp) {
                        val ch = if (pianoIsComp) PIANO else GTR
                        if (part.energy == 1 && style.compMode != Style.CompMode.ARPEGGIO) {
                            if (s == 0 || (chords.size > 1 && s == style.steps / 2))
                                for (v in voicing(c, 55)) on(ch, t, v, 64, beat * beats / chords.size * 0.98)
                        } else for ((st, len) in style.comp[variation]) if (st == s) {
                            val dur = stepDur * len * 0.9
                            when (style.compMode) {
                                Style.CompMode.CHORD -> for (v in voicing(c, 55)) on(ch, t, v, 70 + part.energy * 5, dur)
                                Style.CompMode.ARPEGGIO -> {
                                    val v = voicing(c, if (part.energy == 1) 52 else 55)
                                    val line = v + v.map { it + 12 }
                                    on(ch, t, line[(s / 2) % line.size], 62 + part.energy * 6, stepDur * 4)
                                }
                                Style.CompMode.POWER -> {
                                    val r = 40 + Math.floorMod(c.root - 4, 12)
                                    for (v in intArrayOf(r, r + 7, r + 12)) on(ch, t, v, 84, dur)
                                }
                            }
                        }
                        // quand la guitare accompagne, le piano de Bermet joue les accords (blanches)
                        if (!pianoIsComp && (s == 0 || s == style.steps / 2)) for (v in voicing(c, 57)) on(PIANO, t, v, 66, beat * beats / 2 * 0.95)
                    }
                    // nappe
                    if (part.pad && (s == 0 || (chords.size > 1 && s == style.steps / 2))) {
                        for (v in voicing(c, 60)) on(PAD, t, v, 50, beat * beats / chords.size * 0.98)
                    }
                }
            }
            // la mélodie
            if (part.melody) {
                // une mélodie écrite (Claude) sert aussi aux parties du même type qui reviennent
                val notes = part.notes?.also { melodies[part.type] = it } ?: melodies.getOrPut(part.type) {
                    melody(plan, part, beats, scale, lastPitch, Random(plan.seed * 31 + part.type.hashCode()))
                }
                for (n in notes) {
                    if (n.bar >= part.bars) continue
                    val t = t0 + (n.bar * beats + n.beat) * beat
                    on(MEL, t, n.pitch, 88 + part.energy * 6 + (if (n.beat % 1.0 == 0.0) 4 else 0), n.length * beat * 0.95)
                }
                notes.lastOrNull()?.let { lastPitch = it.pitch }
            }
            bar0 += part.bars
        }
        // la fin : un dernier accord tenu, une cymbale
        val end = bar0 * beats * beat
        plan.parts.lastOrNull()?.let { last ->
            val (c, _) = ChordName.parse(last.chords.lastOrNull() ?: "") ?: (Chord(plan.key, intArrayOf(0, 4, 7), "") to null)
            for (v in voicing(c, 55)) on(PIANO, end, v, 70, beat * beats * 1.5)
            on(BASS, end, bassNote(c, null, 'R', 0), 90, beat * beats * 1.5)
            if (last.drums || plan.parts.any { it.drums }) { on(DRUMS, end, Style.CRASH, 100, 2.0); on(DRUMS, end, Style.KICK, 100, 0.3) }
        }
        val programs = mapOf(MEL to plan.melodyProgram, PIANO to 0, BASS to style.bassProgram, PAD to (if (style.padProgram >= 0) style.padProgram else 48),
            GTR to style.compProgram, DRUMS to 0)
        val names = mapOf(MEL to "Mélodie", PIANO to "Piano", BASS to "Basse", PAD to "Nappe", GTR to "Guitare", DRUMS to "Batterie")
        val tracks = listOf(MEL, PIANO, BASS, GTR, PAD, DRUMS).mapNotNull { ch ->
            val e = ev[ch] ?: return@mapNotNull null
            MidiWriter.Track(names.getValue(ch), ch, programs.getValue(ch), e)
        }
        val midi = MidiWriter.write(tracks, plan.title, plan.tempo.toDouble(), beats, markers)
        return Result(midi, plan.toJson(), end + beat * beats * 1.5)
    }

    private fun bassNote(c: Chord, slash: Int?, deg: Char, bar: Int): Int {
        val rootPc = if (deg == 'R' && slash != null) slash else c.root
        val root = 36 + rootPc
        val third = if (c.isMinor) 3 else 4
        val fifth = if (6 in c.intervals && 7 !in c.intervals) 6 else if (8 in c.intervals) 8 else 7
        val n = when (deg) {
            '3' -> 36 + c.root + third
            '5' -> 36 + c.root + fifth
            '8' -> 36 + c.root + 12
            'A' -> if (bar % 2 == 0) root else 36 + c.root + fifth - 12
            'C' -> 36 + c.root - 1
            else -> root
        }
        return if (n > 50) n - 12 else if (n < 28) n + 12 else n
    }

    private fun voicing(c: Chord, low: Int): List<Int> =
        c.intervals.map { iv -> low + Math.floorMod(c.root + iv - low, 12) }.distinct().sorted()

    /** Des rythmes d'une mesure (temps, durée), du plus calme au plus vif. */
    private val RHYTHMS4 = listOf(
        listOf(listOf(0.0 to 2.0, 2.0 to 2.0), listOf(0.0 to 3.0, 3.0 to 1.0), listOf(0.0 to 1.0, 1.0 to 1.0, 2.0 to 2.0)),
        listOf(listOf(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.5, 3.5 to 0.5), listOf(0.0 to 1.5, 1.5 to 0.5, 2.0 to 2.0),
            listOf(0.0 to 1.0, 1.0 to 0.5, 1.5 to 0.5, 2.0 to 1.0, 3.0 to 1.0), listOf(0.5 to 0.5, 1.0 to 1.0, 2.0 to 1.0, 3.0 to 1.0)),
        listOf(listOf(0.0 to 0.5, 0.5 to 0.5, 1.0 to 1.0, 2.0 to 0.5, 2.5 to 0.5, 3.0 to 1.0), listOf(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0, 3.0 to 1.0),
            listOf(0.5 to 0.5, 1.0 to 1.0, 2.0 to 1.5, 3.5 to 0.5), listOf(0.0 to 0.75, 0.75 to 0.75, 1.5 to 0.5, 2.0 to 2.0)),
    )
    private val RHYTHMS3 = listOf(
        listOf(listOf(0.0 to 2.0, 2.0 to 1.0), listOf(0.0 to 3.0)),
        listOf(listOf(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0), listOf(0.0 to 2.0, 2.0 to 1.0), listOf(0.0 to 1.5, 1.5 to 0.5, 2.0 to 1.0)),
        listOf(listOf(0.0 to 1.0, 1.0 to 0.5, 1.5 to 0.5, 2.0 to 1.0), listOf(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0)),
    )

    /** Une mélodie pour une partie : phrases de 4 mesures (A B A' cadence), notes de l'accord aux temps forts. */
    private fun melody(plan: Plan, part: Plan.Part, beats: Int, scale: IntArray, start: Int, rnd: Random): List<Plan.Note> {
        val high = part.type == "refrain" || part.type == "solo"
        val lo = if (high) 64 else 60
        val hi = if (high) 86 else 79
        val pool = (if (beats == 3) RHYTHMS3 else RHYTHMS4)[(part.energy - 1).coerceIn(0, 2)]
        val ra = pool.random(rnd)
        val rb = pool.random(rnd)
        val rc = pool.random(rnd)
        val scaleNotes = (lo..hi).filter { Math.floorMod(it - plan.key, 12) in scale }
        var prev = (start.coerceIn(lo + 3, hi - 3)).let { p -> scaleNotes.minByOrNull { kotlin.math.abs(it - p - (if (high) 3 else 0)) } ?: p }
        val out = ArrayList<Plan.Note>()
        val motif = ArrayList<Int>()        // les écarts de la mesure A, pour la répéter
        for (b in 0 until part.bars) {
            val inPhrase = b % 4
            val lastOfPart = b == part.bars - 1
            val rhythm = when {
                inPhrase == 3 || lastOfPart -> if (beats == 3) listOf(0.0 to 3.0) else listOf(0.0 to 1.0, 1.0 to 3.0).takeIf { rnd.nextBoolean() } ?: listOf(0.0 to 4.0)
                inPhrase == 0 || inPhrase == 2 -> ra
                else -> if (b % 8 == 5) rc else rb
            }
            val names = (part.chords.getOrNull(b) ?: "").split(' ').filter { it.isNotBlank() }
            val chords = names.mapNotNull { ChordName.parse(it)?.first }
            rhythm.forEachIndexed { k, (bt, len) ->
                val c = chords.getOrNull(((bt / beats) * chords.size).toInt().coerceAtMost(chords.size - 1)) ?: chords.firstOrNull()
                val strong = bt % 1.0 == 0.0 && (bt == 0.0 || (beats == 4 && bt == 2.0))
                var p: Int
                if (inPhrase == 2 && motif.size > k && rnd.nextInt(3) != 0) {
                    // A' : le même dessin que la mesure A, recalé sur l'accord
                    p = nearest(scaleNotes, prev + motif[k])
                    if (strong && c != null) p = nearestChordTone(c, p, lo, hi)
                } else if ((strong || lastOfPart && k == rhythm.size - 1) && c != null) {
                    val target = if (lastOfPart && k == rhythm.size - 1) {
                        // la fin de la partie : la tonique (ou la quinte avant un refrain)
                        nearest((lo..hi).filter { Math.floorMod(it - plan.key, 12) == 0 }, prev)
                    } else nearestChordTone(c, prev + listOf(-3, -2, 0, 2, 3, 4).random(rnd), lo, hi)
                    p = target
                } else {
                    val step = listOf(-2, -1, -1, 1, 1, 2, 0).random(rnd)
                    val i = scaleNotes.indexOf(nearest(scaleNotes, prev))
                    p = scaleNotes[(i + step).coerceIn(0, scaleNotes.size - 1)]
                }
                // pas de grands sauts : une sixte au plus
                if (kotlin.math.abs(p - prev) > 9) p = nearest(scaleNotes, prev + (if (p > prev) 4 else -4))
                if (inPhrase == 0) { if (k == 0) motif.clear(); motif.add(p - prev) }
                out.add(Plan.Note(b, bt, len, p))
                prev = p
            }
        }
        return out
    }

    private fun nearest(cands: List<Int>, p: Int) = cands.minByOrNull { kotlin.math.abs(it - p) } ?: p

    private fun nearestChordTone(c: Chord, p: Int, lo: Int, hi: Int): Int {
        val tones = (lo..hi).filter { Math.floorMod(it - c.root, 12) in c.intervals.map { iv -> iv % 12 } }
        return nearest(tones, p)
    }
}
