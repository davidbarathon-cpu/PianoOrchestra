package com.pianoorchestra.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Ce qui fait avancer un morceau : appelé par le moteur audio avant chaque petit bloc de son. */
fun interface Sequencer {
    fun advance(synth: Synth, frames: Int, sampleRate: Int)
}

/**
 * Le synthétiseur : 16 canaux MIDI, jusqu'à [MAX_VOICES] notes à la fois, une réverbe légère.
 * Il ne dépend pas d'Android : [render] remplit des tableaux, le moteur audio les envoie au haut-parleur
 * (et les tests les écrivent dans un fichier WAV).
 *
 * Les messages des autres fils (piano, écran) passent par une file ; le séquenceur, lui, est appelé
 * dans le fil audio et utilise [handle] directement, au bloc près (64 échantillons, soit ~1 ms).
 */
class Synth(private val sf: SoundFont, val sampleRate: Int) {

    private class Channel {
        var program = 0
        var volume = 100
        var expression = 127
        var pan = 64
        var sustain = false
        var bend = 8192
        fun reset() { expression = 127; sustain = false; bend = 8192 }
    }

    private class Voice {
        var active = false
        var channel = 0
        var note = 0
        var zone: SoundFont.Zone? = null
        var pos = 0.0
        var step = 0.0
        var env = 0f
        var stage = 0
        var counter = 0
        var attackInc = 0f
        var holdSamples = 0
        var decayCoef = 0f
        var releaseCoef = 0f
        var sustainLevel = 0f
        var velGain = 0f
        var panL = 0f
        var panR = 0f
        var released = false
        var heldByPedal = false
        var age = 0L
        var drumSamples = 0
    }

    private val channels = Array(CHANNELS) { Channel() }
    private val voices = Array(MAX_VOICES) { Voice() }
    private val queue = ConcurrentLinkedQueue<Int>()
    private var ageCounter = 0L
    private val samples = sf.samples

    /** Gains du mixeur, par canal (0 à 1). Écrits par le lecteur de morceaux. */
    val channelMix = FloatArray(CHANNELS) { 1f }

    @Volatile var masterGain = 0.7f
    @Volatile var sequencer: Sequencer? = null

    private val reverb = Reverb(sampleRate)
    private val dryL = FloatArray(BLOCK)
    private val dryR = FloatArray(BLOCK)
    private val wet = FloatArray(BLOCK)

    /** Depuis n'importe quel fil : un message MIDI (statut, donnée 1, donnée 2). */
    fun send(status: Int, d1: Int, d2: Int) = sendOn(status and 0xF0, status and 0x0F, d1, d2)

    /** Comme [send], mais le canal peut aussi être [PRIVATE_CHANNEL] (celui de la tablette). */
    fun sendOn(kind: Int, channel: Int, d1: Int, d2: Int) {
        queue.add((kind and 0xF0) or ((d1 and 0x7F) shl 8) or ((d2 and 0x7F) shl 16) or ((channel and 0x1F) shl 24))
    }

    fun allNotesOff() {
        for (c in 0 until CHANNELS) {
            sendOn(0xB0, c, 64, 0)
            sendOn(0xB0, c, 123, 0)
        }
    }

    /** Remplit [left] et [right] (même longueur, [frames] échantillons). */
    fun render(left: FloatArray, right: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            while (true) {
                val m = queue.poll() ?: break
                handleOn(m and 0xF0, (m shr 24) and 0x1F, (m shr 8) and 0x7F, (m shr 16) and 0x7F)
            }
            sequencer?.advance(this, n, sampleRate)
            renderBlock(n)
            val g = masterGain
            for (i in 0 until n) {
                left[done + i] = softClip((dryL[i]) * g)
                right[done + i] = softClip((dryR[i]) * g)
            }
            done += n
        }
    }

    /** Un message MIDI, traité tout de suite (fil audio seulement). */
    fun handle(status: Int, d1: Int, d2: Int) = handleOn(status and 0xF0, status and 0x0F, d1, d2)

    private fun handleOn(kind: Int, ch: Int, d1: Int, d2: Int) {
        if (ch >= CHANNELS) return
        val c = channels[ch]
        when (kind) {
            0x90 -> if (d2 > 0) noteOn(ch, d1, d2) else noteOff(ch, d1)
            0x80 -> noteOff(ch, d1)
            0xC0 -> c.program = d1
            0xE0 -> c.bend = d1 or (d2 shl 7)
            0xB0 -> when (d1) {
                7 -> c.volume = d2
                10 -> c.pan = d2
                11 -> c.expression = d2
                64 -> {
                    c.sustain = d2 >= 64
                    if (!c.sustain) for (v in voices) {
                        if (v.active && v.channel == ch && v.heldByPedal) { v.heldByPedal = false; release(v) }
                    }
                }
                120 -> for (v in voices) if (v.channel == ch) v.active = false
                121 -> c.reset()
                123 -> for (v in voices) if (v.active && v.channel == ch) { v.heldByPedal = false; release(v) }
            }
        }
    }

    private fun noteOn(ch: Int, note: Int, vel: Int) {
        val c = channels[ch]
        val drums = ch == 9
        // la même note rejouée : on relâche l'ancienne
        for (v in voices) if (v.active && v.channel == ch && v.note == note && !v.released) {
            v.heldByPedal = false
            release(v)
        }
        val zones = sf.zones(if (drums) 128 else 0, if (drums) 0 else c.program)
        for (z in zones) {
            if (note < z.keyLo || note > z.keyHi || vel < z.velLo || vel > z.velHi) continue
            if (z.exclusiveClass != 0) {
                for (v in voices) if (v.active && v.channel == ch && v.zone?.exclusiveClass == z.exclusiveClass) {
                    v.releaseCoef = coef(0.02f); v.stage = STAGE_RELEASE; v.released = true
                }
            }
            val v = allocate()
            start(v, ch, note, vel, z, drums)
        }
    }

    private fun start(v: Voice, ch: Int, note: Int, vel: Int, z: SoundFont.Zone, drums: Boolean) {
        val semis = (note - z.rootKey) * z.scaleTuning + z.tuneCents / 100f
        v.active = true
        v.channel = ch
        v.note = note
        v.zone = z
        v.pos = z.start.toDouble()
        v.step = 2.0.pow(semis / 12.0) * z.sampleRate / sampleRate
        v.env = 0f
        v.stage = STAGE_ATTACK
        v.attackInc = 1f / (z.attack * sampleRate).coerceAtLeast(1f)
        v.holdSamples = (z.hold * sampleRate).toInt()
        v.decayCoef = coef(z.decay)
        v.releaseCoef = coef(z.release)
        v.sustainLevel = z.sustain
        val vf = vel / 127f
        v.velGain = z.gain * vf * vf
        val p = ((z.pan + (channels[ch].pan - 64) / 64f).coerceIn(-1f, 1f) + 1f) / 2f
        v.panL = sqrt(1f - p)
        v.panR = sqrt(p)
        v.released = false
        v.heldByPedal = false
        v.counter = 0
        v.age = ageCounter++
        v.drumSamples = if (drums) (3 * sampleRate) else 0
    }

    private fun noteOff(ch: Int, note: Int) {
        if (ch == 9) return                    // la batterie joue ses sons jusqu'au bout
        val pedal = channels[ch].sustain
        for (v in voices) if (v.active && v.channel == ch && v.note == note && !v.released) {
            if (pedal) v.heldByPedal = true else release(v)
        }
    }

    private fun release(v: Voice) {
        if (v.released) return
        v.released = true
        v.stage = STAGE_RELEASE
    }

    private fun allocate(): Voice {
        voices.firstOrNull { !it.active }?.let { return it }
        // plus de place : la note relâchée la plus ancienne, sinon la plus ancienne tout court
        return voices.filter { it.released }.minByOrNull { it.age } ?: voices.minByOrNull { it.age }!!
    }

    private fun coef(seconds: Float): Float = exp(-5.0 / (seconds.coerceAtLeast(0.001f) * sampleRate)).toFloat()

    private fun renderBlock(n: Int) {
        java.util.Arrays.fill(dryL, 0, n, 0f)
        java.util.Arrays.fill(dryR, 0, n, 0f)
        java.util.Arrays.fill(wet, 0, n, 0f)
        for (v in voices) {
            if (!v.active) continue
            val c = channels[v.channel]
            val chGain = (c.volume / 127f) * (c.expression / 127f) * channelMix[v.channel]
            val g = v.velGain * chGain
            val gl = g * v.panL
            val gr = g * v.panR
            val bendFactor = if (c.bend == 8192) 1.0 else 2.0.pow((c.bend - 8192) / 8192.0 * 2.0 / 12.0)
            renderVoice(v, n, gl, gr, v.step * bendFactor)
        }
        for (i in 0 until n) wet[i] = (dryL[i] + dryR[i]) * 0.5f * REVERB_SEND
        reverb.process(wet, dryL, dryR, n)
    }

    private fun renderVoice(v: Voice, n: Int, gl: Float, gr: Float, step: Double) {
        val z = v.zone ?: run { v.active = false; return }
        var pos = v.pos
        var env = v.env
        val looping = z.loopMode == 1 || (z.loopMode == 3 && !v.released)
        val canLoop = looping && z.loopEnd > z.loopStart + 2
        val loopLen = (z.loopEnd - z.loopStart).toDouble()
        val end = z.end - 1
        for (i in 0 until n) {
            when (v.stage) {
                STAGE_ATTACK -> {
                    env += v.attackInc
                    if (env >= 1f) { env = 1f; v.stage = STAGE_HOLD; v.counter = 0 }
                }
                STAGE_HOLD -> if (++v.counter >= v.holdSamples) v.stage = STAGE_DECAY
                STAGE_DECAY -> {
                    env = v.sustainLevel + (env - v.sustainLevel) * v.decayCoef
                    if (env - v.sustainLevel < 0.0001f) { env = v.sustainLevel; v.stage = STAGE_SUSTAIN }
                }
                STAGE_RELEASE -> {
                    env *= v.releaseCoef
                    if (env < 0.0001f) { v.active = false; break }
                }
            }
            if (v.drumSamples > 0 && !v.released && --v.drumSamples == 0) release(v)
            val ip = pos.toInt()
            val fr = (pos - ip).toFloat()
            val a = samples[ip].toFloat()
            val b = samples[ip + 1].toFloat()
            val s = (a + (b - a) * fr) * (env / 32768f)
            dryL[i] += s * gl
            dryR[i] += s * gr
            pos += step
            if (canLoop) {
                while (pos >= z.loopEnd) pos -= loopLen
            } else if (pos >= end) {
                v.active = false
                break
            }
        }
        v.pos = pos
        v.env = env
        if (v.sustainLevel <= 0.0001f && v.stage == STAGE_SUSTAIN) v.active = false
    }

    /** Nombre de notes en train de sonner (pour les tests et l'affichage). */
    fun activeVoices(): Int = voices.count { it.active }

    private fun softClip(x: Float): Float {
        if (x > 3f) return 1f
        if (x < -3f) return -1f
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }

    companion object {
        const val MAX_VOICES = 64
        /** 16 canaux MIDI + 1 canal privé pour la tablette (écho du piano, clavier à l'écran). */
        const val CHANNELS = 17
        const val PRIVATE_CHANNEL = 16
        const val BLOCK = 64
        private const val REVERB_SEND = 0.22f
        private const val STAGE_ATTACK = 0
        private const val STAGE_HOLD = 1
        private const val STAGE_DECAY = 2
        private const val STAGE_SUSTAIN = 3
        private const val STAGE_RELEASE = 4
    }
}

/** Une réverbe légère, dans l'esprit de Freeverb : 4 filtres en peigne et 2 passe-tout par côté. */
private class Reverb(sampleRate: Int) {
    private val scale = sampleRate / 44100.0
    private val combL = intArrayOf(1116, 1188, 1277, 1356).map { Comb((it * scale).toInt()) }
    private val combR = intArrayOf(1139, 1211, 1300, 1379).map { Comb((it * scale).toInt()) }
    private val apL = intArrayOf(556, 441).map { AllPass((it * scale).toInt()) }
    private val apR = intArrayOf(579, 464).map { AllPass((it * scale).toInt()) }

    fun process(input: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = input[i]
            var l = 0f
            var r = 0f
            for (c in combL) l += c.process(x)
            for (c in combR) r += c.process(x)
            for (a in apL) l = a.process(l)
            for (a in apR) r = a.process(r)
            outL[i] += l * 0.25f
            outR[i] += r * 0.25f
        }
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(1))
        private var idx = 0
        private var store = 0f
        fun process(x: Float): Float {
            val out = buf[idx]
            store = out * 0.8f + store * 0.2f        // amortissement des aigus
            buf[idx] = x + store * 0.84f             // durée de la salle
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private class AllPass(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(1))
        private var idx = 0
        fun process(x: Float): Float {
            val b = buf[idx]
            buf[idx] = x + b * 0.5f
            if (++idx >= buf.size) idx = 0
            return b - x
        }
    }
}
