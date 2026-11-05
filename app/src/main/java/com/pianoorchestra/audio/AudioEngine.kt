package com.pianoorchestra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Le moteur audio : un fil prioritaire qui demande du son au synthétiseur et l'envoie à la sortie
 * choisie (haut-parleurs, prise casque vers le Line In du piano, USB, Bluetooth).
 */
class AudioEngine(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val sampleRate: Int = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile private var preferred: AudioDeviceInfo? = null

    fun start(synth: Synth) {
        if (running) return
        running = true
        thread = Thread({ loop(synth) }, "po-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    /** null = automatique (le système choisit). */
    fun setOutputDevice(device: AudioDeviceInfo?) {
        preferred = device
        track?.setPreferredDevice(device)
    }

    private fun loop(synth: Synth) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            playLoop(synth)
        } catch (e: Throwable) {
            // pas de sortie audio disponible : le morceau avance quand même (affichage, guide)
            Log.e("PianoOrchestra", "audio indisponible", e)
            silentLoop(synth)
        }
    }

    private fun playLoop(synth: Synth) {
        val frames = 256
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, frames * 8 * 4))
            .build()
        track = t
        try {
            preferred?.let { t.setPreferredDevice(it) }
            // petit tampon (faible latence), agrandi tout seul si le son craque
            var bufferFrames = maxOf(frames * 2, minOf(t.bufferCapacityInFrames, 512))
            t.bufferSizeInFrames = bufferFrames
            t.play()
            val left = FloatArray(frames)
            val right = FloatArray(frames)
            val out = FloatArray(frames * 2)
            var lastUnderruns = 0
            var checks = 0
            while (running) {
                synth.render(left, right, frames)
                for (i in 0 until frames) {
                    out[2 * i] = left[i]
                    out[2 * i + 1] = right[i]
                }
                val w = t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (w < 0) error("écriture audio impossible : $w")
                if (++checks >= 200) {
                    checks = 0
                    val u = t.underrunCount
                    if (u > lastUnderruns && bufferFrames + 256 <= t.bufferCapacityInFrames) {
                        bufferFrames += 256
                        t.bufferSizeInFrames = bufferFrames
                    }
                    lastUnderruns = u
                }
            }
        } finally {
            try { t.stop() } catch (_: Exception) {}
            t.release()
            track = null
        }
    }

    private fun silentLoop(synth: Synth) {
        val frames = 512
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        val blockMs = frames * 1000L / sampleRate
        while (running) {
            synth.render(l, r, frames)
            Thread.sleep(blockMs)
        }
    }

    /** Les sorties utiles, pour les réglages. */
    fun outputs(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in USEFUL_TYPES }
            .distinctBy { it.type }

    companion object {
        val USEFUL_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}
