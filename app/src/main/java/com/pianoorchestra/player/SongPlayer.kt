package com.pianoorchestra.player

import com.pianoorchestra.audio.Sequencer
import com.pianoorchestra.audio.Synth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Le lecteur de morceaux : joue l'orchestre, coupe la partie de la musicienne et affiche ses notes
 * comme guide. Il tourne dans le fil audio (au bloc près), les commandes de l'écran passent par une file.
 */
class SongPlayer : Sequencer {

    data class State(
        val song: Song? = null,
        val playing: Boolean = false,
        val position: Double = 0.0,
        val countingIn: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _guide = MutableStateFlow<Set<Int>>(emptySet())
    /** Les notes de « ma partie » qui devraient sonner maintenant. */
    val guide: StateFlow<Set<Int>> = _guide

    private val commands = ConcurrentLinkedQueue<(Synth) -> Unit>()

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

    @Volatile var tempoFactor = 1.0
    @Volatile var countIn = true

    fun load(newSong: Song) = post { synth ->
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
        for (c in 0 until 16) synth.channelMix[c] = 1f
        publish(force = true)
    }

    fun play() = post { synth -> startNow(synth, t.coerceAtLeast(0.0).let { if (song != null && it >= song!!.duration) 0.0 else it }) }
    fun stop() = post { synth -> stopNow(synth); publish(force = true) }
    fun restart() = post { synth -> stopNow(synth); t = 0.0; startNow(synth, 0.0) }

    /** Aller à [seconds] (le morceau continue s'il jouait). */
    fun seek(seconds: Double) = post { synth ->
        val wasPlaying = playing
        stopNow(synth)
        t = seconds.coerceIn(0.0, song?.duration ?: 0.0)
        if (wasPlaying) startNow(synth, t) else publish(force = true)
    }

    fun setUserPart(part: Int) = post { synth -> userPart = part; silence(synth, part); refreshGuide() }
    fun setMuted(part: Int, value: Boolean) = post { synth -> if (part in muted.indices) { muted[part] = value; applyMix(synth) } }
    fun setSolo(part: Int, value: Boolean) = post { synth -> if (part in soloed.indices) { soloed[part] = value; applyMix(synth) } }
    fun setVolume(part: Int, value: Float) = post { synth -> if (part in volume.indices) { volume[part] = value; applyMix(synth) } }

    private fun post(cmd: (Synth) -> Unit) { commands.add(cmd) }

    override fun advance(synth: Synth, frames: Int, sampleRate: Int) {
        while (true) {
            val c = commands.poll() ?: break
            c(synth)
        }
        val s = song ?: return
        if (!playing) return
        val tEnd = t + frames.toDouble() / sampleRate * tempoFactor
        while (clickIdx < clicks.size && clicks[clickIdx] < tEnd) {
            synth.handle(0x99, if (clickIdx == 0) 76 else 77, if (clickIdx == 0) 110 else 90)  // wood-block
            clickIdx++
        }
        val events = s.events
        while (idx < events.size && events[idx].time < tEnd) {
            val e = events[idx++]
            if (e.time >= startAt) dispatch(synth, e)
        }
        t = tEnd
        if (t > s.duration + 1.5) {
            stopNow(synth)
            t = 0.0
        }
        publish()
    }

    private fun dispatch(synth: Synth, e: SongEvent) {
        val kind = e.status and 0xF0
        val isOn = kind == 0x90 && e.d2 > 0
        val isOff = kind == 0x80 || (kind == 0x90 && e.d2 == 0)
        if (e.part == userPart) {
            if (isOn) { guideNotes[e.d1] = true; refreshGuide() }
            else if (isOff) { guideNotes[e.d1] = false; refreshGuide() }
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

    private fun startNow(synth: Synth, from: Double) {
        val s = song ?: return
        // remet sons et réglages (programme, volume…) tels qu'ils sont à cet endroit du morceau
        val target = s.indexAt(from)
        for (k in 0 until target) {
            val e = s.events[k]
            val kind = e.status and 0xF0
            if (kind == 0xC0 || kind == 0xB0 || kind == 0xE0) synth.handle(e.status, e.d1, e.d2)
        }
        idx = target
        startAt = from
        if (countIn) {
            val beat = s.beatSecondsAt(from)
            val n = s.beatsPerBar
            clicks = DoubleArray(n) { k -> from - (n - k) * beat }
            t = from - n * beat
        } else {
            clicks = DoubleArray(0)
            t = from
        }
        clickIdx = 0
        playing = true
        java.util.Arrays.fill(guideNotes, false)
        refreshGuide()
        publish(force = true)
    }

    private fun stopNow(synth: Synth) {
        val s = song
        if (s != null) for (p in s.parts.indices) silence(synth, p)
        for (c in 0 until 16) {
            synth.handle(0xB0 or c, 64, 0)
            synth.handle(0xB0 or c, 123, 0)
        }
        playing = false
        if (t < 0) t = startAt
        java.util.Arrays.fill(guideNotes, false)
        refreshGuide()
    }

    private fun refreshGuide() {
        val set = HashSet<Int>()
        for (n in 0 until 128) if (guideNotes[n]) set.add(n)
        _guide.value = set
    }

    private fun publish(force: Boolean = false) {
        if (!force && kotlin.math.abs(t - lastPublished) < 0.1) return
        lastPublished = t
        _state.value = State(song, playing, t.coerceAtLeast(0.0), playing && t < startAt)
    }
}
