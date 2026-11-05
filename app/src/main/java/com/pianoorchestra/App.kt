package com.pianoorchestra

import android.app.Application
import android.content.Context
import android.util.Log
import com.pianoorchestra.audio.AudioEngine
import com.pianoorchestra.audio.SoundFont
import com.pianoorchestra.audio.Synth
import com.pianoorchestra.library.SongLibrary
import com.pianoorchestra.midi.MidiInput
import com.pianoorchestra.player.SongPlayer
import com.pianoorchestra.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Les réglages, gardés sur la tablette. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("po_prefs", Context.MODE_PRIVATE)
    var lang: String
        get() = sp.getString("lang", "fr") ?: "fr"
        set(v) { sp.edit().putString("lang", v).apply() }
    var echo: Boolean
        get() = sp.getBoolean("echo", false)
        set(v) { sp.edit().putBoolean("echo", v).apply() }
    var echoProgram: Int
        get() = sp.getInt("echo_program", 0)
        set(v) { sp.edit().putInt("echo_program", v).apply() }
    var outputType: Int
        get() = sp.getInt("output_type", -1)
        set(v) { sp.edit().putInt("output_type", v).apply() }
    var masterVolume: Float
        get() = sp.getFloat("master_volume", 0.7f)
        set(v) { sp.edit().putFloat("master_volume", v).apply() }

    companion object {
        fun langOf(context: Context): String =
            context.getSharedPreferences("po_prefs", Context.MODE_PRIVATE).getString("lang", "fr") ?: "fr"
    }
}

/** Ce que le piano joue, en direct : touches enfoncées, pédale, et le journal du moniteur. */
class LiveInput {
    data class Entry(val status: Int, val d1: Int, val d2: Int, val id: Long)

    val keys = MutableStateFlow<Set<Int>>(emptySet())
    val pedal = MutableStateFlow(false)
    val log = MutableStateFlow<List<Entry>>(emptyList())
    private var counter = 0L
    private val down = BooleanArray(128)

    @Synchronized
    fun onMidi(status: Int, d1: Int, d2: Int) {
        val kind = status and 0xF0
        val ch = status and 0x0F
        when {
            kind == 0x90 && d2 > 0 && ch != 9 -> { down[d1] = true; publishKeys() }
            (kind == 0x80 || (kind == 0x90 && d2 == 0)) && ch != 9 -> { down[d1] = false; publishKeys() }
            kind == 0xB0 && d1 == 64 -> pedal.value = d2 >= 64
        }
        val interesting = (kind == 0x90) || (kind == 0x80) || (kind == 0xC0) || (kind == 0xB0 && d1 == 64)
        if (interesting) log.value = (listOf(Entry(status, d1, d2, counter++)) + log.value).take(40)
    }

    private fun publishKeys() {
        val s = HashSet<Int>()
        for (n in 0 until 128) if (down[n]) s.add(n)
        keys.value = s
    }
}

/** Enregistre tout ce qui est joué au piano, sans orchestre. */
class FreeRecorder {
    val activeFlow = MutableStateFlow(false)
    val active: Boolean get() = activeFlow.value
    private var t0 = 0L
    private val events = ArrayList<com.pianoorchestra.player.MidiWriter.Event>()

    @Synchronized fun start() { events.clear(); t0 = System.nanoTime(); activeFlow.value = true }

    @Synchronized fun onMidi(status: Int, d1: Int, d2: Int) {
        if (!active || (status and 0x0F) == 9) return
        val kind = status and 0xF0
        if (kind == 0x90 || kind == 0x80 || (kind == 0xB0 && d1 == 64)) {
            events.add(com.pianoorchestra.player.MidiWriter.Event((System.nanoTime() - t0) / 1e9, status, d1, d2))
        }
    }

    @Synchronized fun stop(): ByteArray? {
        activeFlow.value = false
        if (events.none { (it.status and 0xF0) == 0x90 }) return null
        val first = events.first().time - 0.5
        val shifted = events.map { com.pianoorchestra.player.MidiWriter.Event(it.time - first, it.status, it.d1, it.d2) }
        return com.pianoorchestra.player.MidiWriter.write(
            listOf(com.pianoorchestra.player.MidiWriter.Track("Bermet", 0, 0, shifted)), "Bermet")
    }
}

class App : Application() {

    enum class SoundState { LOADING, READY, ERROR }

    lateinit var prefs: Prefs
    lateinit var midi: MidiInput
    lateinit var library: SongLibrary
    lateinit var updater: Updater
    lateinit var audio: AudioEngine
    val player = SongPlayer()
    val band = com.pianoorchestra.band.Band()
    val live = LiveInput()

    private val _soundState = MutableStateFlow(SoundState.LOADING)
    val soundState: StateFlow<SoundState> = _soundState

    @Volatile var synth: Synth? = null
        private set

    /** Canal de la tablette pour l'écho du piano et le clavier à l'écran. */
    private val echoChannel = Synth.PRIVATE_CHANNEL

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        midi = MidiInput(this)
        library = SongLibrary(this)
        updater = Updater(this)
        audio = AudioEngine(this)

        midi.addListener { s, a, b ->
            live.onMidi(s, a, b)
            player.onLive(s, a, b)
            band.onLive(s, a, b)
            recorder.onMidi(s, a, b)
            val s2 = synth ?: return@addListener
            if (!prefs.echo) return@addListener
            val kind = s and 0xF0
            if ((s and 0x0F) == 9) return@addListener
            if (kind == 0x90 || kind == 0x80 || (kind == 0xB0 && (a == 64 || a == 7))) {
                s2.sendOn(kind, echoChannel, a, b)
            }
        }
        midi.start()
        player.onRecorded = { bytes, title -> saveTake(bytes, title) }
        band.onStart = { player.stop() }
        loadSounds()
        updater.check(quiet = true)
    }

    private fun loadSounds() {
        Thread {
            try {
                val bytes = assets.open("sounds.sf2").use { it.readBytes() }
                val sf = SoundFont(bytes)
                val s = Synth(sf, audio.sampleRate)
                s.masterGain = prefs.masterVolume
                s.sequencer = com.pianoorchestra.audio.Sequencer { sy, f, r -> player.advance(sy, f, r); band.advance(sy, f, r) }
                s.sendOn(0xC0, echoChannel, prefs.echoProgram, 0)
                synth = s
                applyOutput()
                audio.start(s)
                _soundState.value = SoundState.READY
            } catch (e: Throwable) {
                Log.e("PianoOrchestra", "sons", e)
                _soundState.value = SoundState.ERROR
            }
        }.start()
    }

    fun applyOutput() {
        val type = prefs.outputType
        audio.setOutputDevice(if (type < 0) null else audio.outputs().firstOrNull { it.type == type })
    }

    fun setEchoProgram(program: Int) {
        prefs.echoProgram = program
        synth?.sendOn(0xC0, echoChannel, program, 0)
    }

    fun setMasterVolume(v: Float) {
        prefs.masterVolume = v
        synth?.masterGain = v
    }

    /** Le dernier enregistrement gardé (pour l'annoncer à l'écran). */
    val lastSaved = MutableStateFlow<String?>(null)
    val recorder = FreeRecorder()

    private fun saveTake(bytes: ByteArray, title: String) {
        val prefix = getString(R.string.take_prefix)
        val name = library.saveRecording(bytes, "$prefix - $title")
        lastSaved.value = name
    }

    /** Enregistrement libre (écran Clavier) : démarrer, arrêter et garder. */
    fun toggleFreeRecording() {
        if (recorder.active) {
            val bytes = recorder.stop() ?: return
            saveTake(bytes, getString(R.string.take_free))
        } else recorder.start()
    }

    /** Le clavier à l'écran joue sur la tablette. */
    fun screenNoteOn(note: Int) {
        synth?.sendOn(0x90, echoChannel, note, 96)
        player.onLive(0x90, note, 96)
        band.onLive(0x90, note, 96)
        live.onMidi(0x90, note, 96)
    }
    fun screenNoteOff(note: Int) {
        synth?.sendOn(0x80, echoChannel, note, 0)
        player.onLive(0x80, note, 0)
        band.onLive(0x80, note, 0)
        live.onMidi(0x80, note, 0)
    }
}
