package com.pianoorchestra.player

import com.pianoorchestra.audio.Sequencer
import com.pianoorchestra.audio.Synth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Le lecteur de morceaux : joue l'orchestre, coupe la partie de la musicienne et affiche ses notes
 * comme guide. Il tourne dans le fil audio (au bloc près), les commandes de l'écran passent par une file.
 *
 * Pour travailler : « attends-moi » (l'orchestre attend la bonne note), boucle A-B ou sur une section,
 * tempo progressif (+5 % à chaque tour), et enregistrement de ce qu'elle joue avec l'orchestre.
 */
class SongPlayer : Sequencer {

    data class State(
        val song: Song? = null,
        val playing: Boolean = false,
        val position: Double = 0.0,
        val countingIn: Boolean = false,
        val waiting: Boolean = false,
        val tempo: Double = 1.0,
        val loopStart: Double = -1.0,
        val loopEnd: Double = -1.0,
        val recording: Boolean = false,
        val loops: Int = 0,
        val following: Boolean = false,
    ) {
        val looping: Boolean get() = loopEnd > loopStart && loopStart >= 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _guide = MutableStateFlow<Set<Int>>(emptySet())
    /** Les notes de « ma partie » qui devraient sonner maintenant (ou celles qu'on attend). */
    val guide: StateFlow<Set<Int>> = _guide

    private val commands = ConcurrentLinkedQueue<(Synth) -> Unit>()
    private val live = ConcurrentLinkedQueue<IntArray>()

    // --- état du fil audio
    private var song: Song? = null
    private var playing = false
    private var t = 0.0
    private var startAt = 0.0
    private var idx = 0
    private var clicks = DoubleArray(0)
    private var clickIdx = 0
    private var lastPublished = -1.0
    private var muted = BooleanArray(0)
    private var soloed = BooleanArray(0)
    private var volume = FloatArray(0)
    private var userPart = -1
    private var sounding = Array(0) { BooleanArray(128) }
    private val guideNotes = BooleanArray(128)
    private var tempo = 1.0
    private var loopA = -1.0
    private var loopB = -1.0
    private var loops = 0

    // attente et suivi du tempo
    private var waitMode = false
    private var follow = false
    private var waitingFor: IntArray? = null
    private var checkedUntil = -1          // dernier événement de « ma partie » déjà vérifié
    private var clockFrames = 0L           // horloge réelle (échantillons), même pendant l'attente
    private var sampleRate = 48000
    private val playedAt = LongArray(128) { Long.MIN_VALUE / 2 }

    // enregistrement
    private var recording = false
    private var recStart = 0.0
    private val recEvents = ArrayList<MidiWriter.Event>()

    @Volatile var countIn = true
    @Volatile var progressive = false
    /** Appelé (hors fil audio) quand un enregistrement se termine : le fichier MIDI prêt à garder. */
    @Volatile var onRecorded: ((ByteArray, String) -> Unit)? = null

    val tempoFactor: Double get() = _state.value.tempo

    fun load(newSong: Song) = post { synth ->
        if (recording) finishRecording()
        stopNow(synth)
        song = newSong
        val n = newSong.parts.size
        muted = BooleanArray(n)
        soloed = BooleanArray(n)
        volume = FloatArray(n) { 1f }
        sounding = Array(n) { BooleanArray(128) }
        userPart = -1
        t = 0.0
        idx = 0
        loopA = -1.0; loopB = -1.0; loops = 0
        for (c in 0 until 16) synth.channelMix[c] = 1f
        publish(force = true)
    }

    fun play() = post { synth -> startNow(synth, t.coerceAtLeast(0.0).let { if (song != null && it >= song!!.duration) 0.0 else it }) }
    fun stop() = post { synth -> stopNow(synth); publish(force = true) }
    fun restart() = post { synth -> stopNow(synth); t = if (loopA >= 0 && loopB > loopA) loopA else 0.0; startNow(synth, t) }

    /** Aller à [seconds] (le morceau continue s'il jouait). */
    fun seek(seconds: Double) = post { synth ->
        val wasPlaying = playing
        stopNow(synth)
        t = seconds.coerceIn(0.0, song?.duration ?: 0.0)
        if (wasPlaying) startNow(synth, t) else publish(force = true)
    }

    fun setTempo(value: Double) = post { tempo = value.coerceIn(0.3, 1.5); publish(force = true) }
    fun setWaitMode(on: Boolean) = post { waitMode = on; waitingFor = null; checkedUntil = idx - 1; publish(force = true) }

    /** « Suis-moi » : l'orchestre attend et accélère ou ralentit avec elle. */
    fun setFollow(on: Boolean) = post { follow = on; if (on) waitMode = true; waitingFor = null; checkedUntil = idx - 1; publish(force = true) }

    /** Boucle de [a] à [b] secondes ; a < 0 pour l'enlever. */
    fun setLoop(a: Double, b: Double) = post { synth ->
        loopA = a; loopB = b; loops = 0
        if (a >= 0 && b > a && (t < a || t > b)) {
            val wasPlaying = playing
            stopNow(synth)
            t = a
            if (wasPlaying) startNow(synth, a)
        }
        publish(force = true)
    }

    fun setUserPart(part: Int) = post { synth -> userPart = part; silence(synth, part); waitingFor = null; refreshGuide() }
    fun setMuted(part: Int, value: Boolean) = post { synth -> if (part in muted.indices) { muted[part] = value; applyMix(synth) } }
    fun setSolo(part: Int, value: Boolean) = post { synth -> if (part in soloed.indices) { soloed[part] = value; applyMix(synth) } }
    fun setVolume(part: Int, value: Float) = post { synth -> if (part in volume.indices) { volume[part] = value; applyMix(synth) } }

    fun startRecording() = post { recording = true; recStart = t.coerceAtLeast(0.0); recEvents.clear(); publish(force = true) }
    fun stopRecording() = post { if (recording) finishRecording(); publish(force = true) }

    /** Une note jouée sur le piano (depuis le fil MIDI). */
    fun onLive(status: Int, d1: Int, d2: Int) {
        if ((status and 0x0F) == 9) return
        val kind = status and 0xF0
        if (kind == 0x90 || kind == 0x80 || (kind == 0xB0 && d1 == 64)) live.add(intArrayOf(status, d1, d2))
    }

    private fun post(cmd: (Synth) -> Unit) { commands.add(cmd) }

    override fun advance(synth: Synth, frames: Int, sampleRate: Int) {
        this.sampleRate = sampleRate
        while (true) {
            val c = commands.poll() ?: break
            c(synth)
        }
        clockFrames += frames
        while (true) {
            val m = live.poll() ?: break
            val kind = m[0] and 0xF0
            if (kind == 0x90 && m[2] > 0) {
                playedAt[m[1]] = clockFrames
                if (follow && playing && userPart >= 0) followNote(m[1])
            }
            if (recording && playing && t >= recStart) recEvents.add(MidiWriter.Event(t - recStart, m[0], m[1], m[2]))
        }
        val s = song ?: return
        if (!playing) return

        // l'orchestre attend-il la bonne note ?
        val wf = waitingFor
        if (wf != null) {
            if (wf.all { clockFrames - playedAt[it] < sampleRate * 3L / 4 }) {
                waitingFor = null
                publish(force = true)
            } else {
                publish()
                return
            }
        }

        var tEnd = t + frames.toDouble() / sampleRate * tempo
        if (loopB > loopA && loopA >= 0 && tEnd > loopB) tEnd = loopB
        val events = s.events

        if (waitMode && userPart >= 0) {
            // en mode « suis-moi », on laisse un peu de marge avant de s'arrêter (un tiers de temps)
            val grace = if (follow) s.beatSecondsAt(t) * 0.35 else 0.0
            var k = checkedUntil + 1
            while (k < events.size && events[k].time < tEnd) {
                val e = events[k]
                if (e.part == userPart && (e.status and 0xF0) == 0x90 && e.d2 > 0 && e.time >= startAt) {
                    // l'accord attendu : toutes les notes de ma partie qui commencent ensemble
                    val group = ArrayList<Int>()
                    var j = k
                    while (j < events.size && events[j].time < e.time + 0.06) {
                        val g = events[j]
                        if (g.part == userPart && (g.status and 0xF0) == 0x90 && g.d2 > 0) group.add(g.d1)
                        j++
                    }
                    val played = group.all { clockFrames - playedAt[it] < sampleRate * 3L / 4 }
                    if (played) {
                        checkedUntil = j - 1
                    } else if (tEnd > e.time + grace) {
                        checkedUntil = j - 1
                        tEnd = maxOf(t, e.time + grace)
                        waitingFor = group.toIntArray()
                        _guide.value = group.toSet()
                    }
                    break
                }
                checkedUntil = k
                k++
            }
        }

        while (clickIdx < clicks.size && clicks[clickIdx] < tEnd) {
            synth.handle(0x99, if (clickIdx == 0) 76 else 77, if (clickIdx == 0) 110 else 90)  // wood-block
            clickIdx++
        }
        while (idx < events.size && events[idx].time < tEnd) {
            val e = events[idx++]
            if (e.time >= startAt) dispatch(synth, e)
        }
        t = tEnd

        if (loopB > loopA && loopA >= 0 && t >= loopB - 1e-6) {
            // fin de la boucle : on repart au début, un peu plus vite si le tempo progressif est activé
            loops++
            if (progressive && tempo < 1.0) tempo = minOf(1.0, tempo + 0.05)
            if (recording) finishRecording()            // une prise par tour de boucle
            stopNotes(synth)
            jumpTo(synth, loopA)
            publish(force = true)
            return
        }
        if (t > s.duration + 1.5) {
            stopNow(synth)
            t = 0.0
        }
        publish()
    }

    /** Compare la note jouée à la note attendue la plus proche, et ajuste doucement le tempo. */
    private fun followNote(note: Int) {
        val s = song ?: return
        if (waitingFor != null || t < startAt) return
        val beat = s.beatSecondsAt(t)
        var k = s.indexAt(t - beat)
        var best = Double.MAX_VALUE
        while (k < s.events.size && s.events[k].time < t + beat) {
            val e = s.events[k]
            if (e.part == userPart && (e.status and 0xF0) == 0x90 && e.d2 > 0 && e.d1 == note) {
                val err = t - e.time
                if (kotlin.math.abs(err) < kotlin.math.abs(best)) best = err
            }
            k++
        }
        if (best == Double.MAX_VALUE) return
        // en retard (err > 0) : on ralentit ; en avance : on accélère — par petites touches
        val errBeats = (best / beat).coerceIn(-0.5, 0.5)
        tempo = (tempo * (1.0 - 0.07 * errBeats)).coerceIn(0.4, 1.4)
    }

    private fun dispatch(synth: Synth, e: SongEvent) {
        val kind = e.status and 0xF0
        val isOn = kind == 0x90 && e.d2 > 0
        val isOff = kind == 0x80 || (kind == 0x90 && e.d2 == 0)
        if (e.part == userPart) {
            if (isOn) { guideNotes[e.d1] = true; if (waitingFor == null) refreshGuide() }
            else if (isOff) { guideNotes[e.d1] = false; if (waitingFor == null) refreshGuide() }
            if (isOn || isOff) return
        }
        if (isOn) {
            if (!audible(e.part)) return
            sounding[e.part][e.d1] = true
        } else if (isOff) {
            sounding[e.part][e.d1] = false
        }
        synth.handle(e.status, e.d1, e.d2)
    }

    private fun audible(part: Int): Boolean {
        if (part == userPart) return false
        if (muted.getOrElse(part) { false }) return false
        if (soloed.any { it }) return soloed.getOrElse(part) { false }
        return true
    }

    /** Coupe les notes d'une partie qui sonnent encore. */
    private fun silence(synth: Synth, part: Int) {
        val s = song ?: return
        if (part !in s.parts.indices) return
        val ch = s.parts[part].channel
        for (n in 0 until 128) if (sounding[part][n]) {
            synth.handle(0x80 or ch, n, 0)
            sounding[part][n] = false
        }
    }

    private fun applyMix(synth: Synth) {
        val s = song ?: return
        for (p in s.parts.indices) if (!audible(p)) silence(synth, p)
        val gains = FloatArray(16) { -1f }
        for (p in s.parts) {
            val c = p.channel
            gains[c] = maxOf(gains[c], volume[p.index])
        }
        for (c in 0 until 16) synth.channelMix[c] = if (gains[c] < 0f) 1f else gains[c]
    }

    /** Se place à [from] sans décompte : sons et réglages remis comme à cet endroit du morceau. */
    private fun jumpTo(synth: Synth, from: Double) {
        val s = song ?: return
        val target = s.indexAt(from)
        for (k in 0 until target) {
            val e = s.events[k]
            val kind = e.status and 0xF0
            if (kind == 0xC0 || kind == 0xB0 || kind == 0xE0) synth.handle(e.status, e.d1, e.d2)
        }
        idx = target
        startAt = from
        t = from
        clicks = DoubleArray(0)
        clickIdx = 0
        waitingFor = null
        checkedUntil = target - 1
        java.util.Arrays.fill(guideNotes, false)
        refreshGuide()
    }

    private fun startNow(synth: Synth, from: Double) {
        val s = song ?: return
        jumpTo(synth, from)
        if (countIn) {
            val beat = s.beatSecondsAt(from)
            val n = s.beatsPerBar
            clicks = DoubleArray(n) { k -> from - (n - k) * beat }
            t = from - n * beat
        }
        playing = true
        publish(force = true)
    }

    private fun stopNotes(synth: Synth) {
        val s = song
        if (s != null) for (p in s.parts.indices) silence(synth, p)
        for (c in 0 until 16) {
            synth.handle(0xB0 or c, 64, 0)
            synth.handle(0xB0 or c, 123, 0)
        }
    }

    private fun stopNow(synth: Synth) {
        stopNotes(synth)
        if (recording) finishRecording()
        playing = false
        waitingFor = null
        if (t < startAt) t = startAt
        java.util.Arrays.fill(guideNotes, false)
        refreshGuide()
    }

    /** L'enregistrement : la prise de Bermet + l'orchestre de la même période, dans un fichier MIDI. */
    private fun finishRecording() {
        recording = false
        val s = song ?: return
        val played = ArrayList(recEvents)
        recEvents.clear()
        if (played.none { (it.status and 0xF0) == 0x90 }) return
        val end = maxOf(t, recStart + (played.lastOrNull()?.time ?: 0.0))
        val tracks = ArrayList<MidiWriter.Track>()
        tracks.add(MidiWriter.Track("Bermet", 0, 0, played))
        val others = s.parts.filter { it.index != userPart && it.noteCount > 0 && audible(it.index) }
        var ch = 1
        for (p in others) {
            val channel = if (p.isDrums) 9 else { if (ch == 9) ch++; ch++.coerceAtMost(15) }
            val evs = s.events.filter { it.part == p.index && it.time >= recStart && it.time <= end && (it.status and 0xF0) != 0xC0 }
                .map { MidiWriter.Event(it.time - recStart, it.status, it.d1, it.d2) }
            if (evs.isNotEmpty()) tracks.add(MidiWriter.Track(p.name.ifBlank { "Piste ${p.index + 1}" }, channel, p.program, evs))
        }
        val bytes = MidiWriter.write(tracks, s.name)
        val cb = onRecorded ?: return
        Thread { cb(bytes, s.name) }.start()
    }

    private fun refreshGuide() {
        val set = HashSet<Int>()
        for (n in 0 until 128) if (guideNotes[n]) set.add(n)
        _guide.value = set
    }

    private fun publish(force: Boolean = false) {
        val waiting = waitingFor != null
        val cur = _state.value
        if (!force && kotlin.math.abs(t - lastPublished) < 0.1 && waiting == cur.waiting) return
        lastPublished = t
        _state.value = State(song, playing, t.coerceAtLeast(0.0), playing && t < startAt, waiting, tempo,
            loopA, loopB, recording, loops, follow)
    }
}
