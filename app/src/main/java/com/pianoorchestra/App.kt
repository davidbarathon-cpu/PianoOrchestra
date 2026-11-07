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
    /** Le code à 4 chiffres de l'échange avec l'ordinateur (gardé : Riffmaker s'en souvient). */
    var exchangeCode: String
        get() = sp.getString("exchange_code", null) ?: newExchangeCode()
        set(v) { sp.edit().putString("exchange_code", v).apply() }
    fun newExchangeCode(): String = (1000 + java.security.SecureRandom().nextInt(9000)).toString().also { exchangeCode = it }
    var ambiance: String
        get() = sp.getString("ambiance", "nuit") ?: "nuit"
        set(v) { sp.edit().putString("ambiance", v).apply() }
    /** Le dernier morceau ouvert, pour « Reprendre » sur l'accueil. */
    var lastSong: String?
        get() = sp.getString("last_song", null)
        set(v) { sp.edit().putString("last_song", v).apply() }
    /** L'IA gratuite en ligne pour le compositeur (Mistral ou Groq) : la clé gratuite et « rapide » ou non. */
    var aiKey: String
        get() = sp.getString("ai_key", "") ?: ""
        set(v) { sp.edit().putString("ai_key", v.trim()).apply() }
    var aiFast: Boolean
        get() = sp.getBoolean("ai_fast", false)
        set(v) { sp.edit().putBoolean("ai_fast", v).apply() }
    var aiEnabled: Boolean
        get() = sp.getBoolean("ai_enabled", true)
        set(v) { sp.edit().putBoolean("ai_enabled", v).apply() }
    /** Calage du carnet sur le son du groupe, par chanson (secondes, + = le carnet attend). */
    fun carnetOffset(id: String): Float = sp.getFloat("offset_$id", 0f)
    fun setCarnetOffset(id: String, v: Float) { sp.edit().putFloat("offset_$id", v).apply() }
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
    /** Tourne-page au pédalier : (compteur, sens) — +1 pédale du milieu, -1 pédale de gauche. */
    val pageTurn = MutableStateFlow(0L to 0)
    val log = MutableStateFlow<List<Entry>>(emptyList())
    private var counter = 0L
    private val down = BooleanArray(128)
    private val pedalDown = BooleanArray(2)

    @Synchronized
    fun onMidi(status: Int, d1: Int, d2: Int) {
        val kind = status and 0xF0
        val ch = status and 0x0F
        when {
            kind == 0x90 && d2 > 0 && ch != 9 -> { down[d1] = true; publishKeys() }
            (kind == 0x80 || (kind == 0x90 && d2 == 0)) && ch != 9 -> { down[d1] = false; publishKeys() }
            kind == 0xB0 && d1 == 64 -> pedal.value = d2 >= 64
            kind == 0xB0 && (d1 == 66 || d1 == 67) && d2 >= 64 && !pedalDown[d1 - 66] -> {
                pedalDown[d1 - 66] = true
                pageTurn.value = (pageTurn.value.first + 1) to (if (d1 == 66) 1 else -1)
            }
            kind == 0xB0 && (d1 == 66 || d1 == 67) && d2 < 64 -> pedalDown[d1 - 66] = false
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
    lateinit var catalog: com.pianoorchestra.library.Catalog
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
        catalog = com.pianoorchestra.library.Catalog(this, library)
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
    /** Captures d'écran automatiques : ouvrir le lecteur directement en « notes qui tombent ». */
    var demoFalling = false

    // ------------------------------------------------------------------ échange avec l'ordinateur
    data class ExchangeLog(val kind: com.pianoorchestra.exchange.ExchangeServer.Kind, val name: String, val from: String, val time: Long)
    val exchangeLog = MutableStateFlow<List<ExchangeLog>>(emptyList())
    /** Change quand des morceaux arrivent : les listes se relisent. */
    val libraryVersion = MutableStateFlow(0)
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    val exchange by lazy {
        com.pianoorchestra.exchange.ExchangeServer(
            com.pianoorchestra.exchange.FolderStore(library.songsDir),
            code = { prefs.exchangeCode },
            info = { mapOf("app" to "Piano Orchestra", "name" to getString(R.string.exchange_device_name),
                "version" to BuildConfig.VERSION_NAME) },
            page = { assets.open("exchange/index.html").use { it.readBytes() } },
            settings = { name, value ->
                when (name) {
                    "ai-key" -> { prefs.aiKey = value; prefs.aiEnabled = true; true }     // envoyée par Riffmaker
                    else -> false
                }
            },
            onEvent = { e ->
                exchangeLog.value = (listOf(ExchangeLog(e.kind, e.name, e.from, System.currentTimeMillis())) + exchangeLog.value).take(60)
                if (e.kind == com.pianoorchestra.exchange.ExchangeServer.Kind.RECEIVED) libraryVersion.value++
            })
    }

    // ------------------------------------------------------------------ le compositeur
    /** Le résultat d'une composition : le plan, et pourquoi l'IA en ligne n'a pas servi (s'il y a lieu). */
    class Composed(val plan: com.pianoorchestra.compose.Plan, val note: String?)

    val aiReady: Boolean get() = prefs.aiEnabled && prefs.aiKey.isNotBlank()

    /** Composer d'après une demande : l'IA en ligne si possible, sinon le compositeur de la tablette. Hors du fil principal. */
    fun compose(request: String, seed: Long = System.nanoTime()): Composed {
        if (aiReady) {
            try {
                return Composed(com.pianoorchestra.compose.OnlineComposer.compose(request, prefs.aiKey, prefs.aiFast), null)
            } catch (e: com.pianoorchestra.compose.OnlineComposer.Failure) {
                val offline = com.pianoorchestra.compose.Composer.compose(com.pianoorchestra.compose.Composer.read(request), seed)
                return Composed(offline, e.message)
            }
        }
        return Composed(com.pianoorchestra.compose.Composer.compose(com.pianoorchestra.compose.Composer.read(request), seed), null)
    }

    /** Modifier le morceau en cours. Null : la tablette n'a pas compris (sans IA). */
    fun modify(plan: com.pianoorchestra.compose.Plan, request: String): Composed? {
        if (aiReady) {
            try {
                return Composed(com.pianoorchestra.compose.OnlineComposer.modify(plan, request, prefs.aiKey, prefs.aiFast), null)
            } catch (e: com.pianoorchestra.compose.OnlineComposer.Failure) {
                return com.pianoorchestra.compose.Composer.modify(plan, request)?.let { Composed(it, e.message) }
            }
        }
        return com.pianoorchestra.compose.Composer.modify(plan, request)?.let { Composed(it, null) }
    }

    /** Garder un morceau composé dans « Mes morceaux » : le MIDI et sa grille. Renvoie l'identifiant à ouvrir. */
    fun saveComposition(plan: com.pianoorchestra.compose.Plan, midi: ByteArray, planJson: String): String {
        val name = library.saveNamed(midi, plan.title, "mid")
        runCatching { java.io.File(library.songsDir, "$name.plan.json").writeText(planJson) }
        libraryVersion.value++
        return "file:$name.mid"
    }

    fun startExchange(): Int {
        val port = exchange.start()
        if (multicastLock == null) runCatching {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            multicastLock = wifi.createMulticastLock("piano-orchestra").apply { setReferenceCounted(false); acquire() }
        }
        return port
    }

    fun stopExchange() {
        exchange.stop()
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
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

    /** Jeu libre : enregistrer une composition (Bermet + le groupe), puis la garder avec sa grille d'accords. */
    fun toggleComposition() {
        if (band.state.value.recording) {
            band.stopRecording { comp ->
                if (comp == null) return@stopRecording
                val name = library.saveRecording(comp.midi, getString(R.string.composition_prefix))
                runCatching { java.io.File(library.songsDir, "$name.plan.json").writeText(comp.planJson) }
                libraryVersion.value++
                lastSaved.value = name
            }
        } else band.startRecording()
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
