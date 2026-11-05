package com.pianoorchestra

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import com.pianoorchestra.ui.FreePlayScreen
import com.pianoorchestra.ui.HomeScreen
import com.pianoorchestra.ui.KeyboardScreen
import com.pianoorchestra.ui.PlayAlongScreen
import com.pianoorchestra.ui.PoTheme
import com.pianoorchestra.ui.CatalogScreen
import com.pianoorchestra.ui.ExchangeScreen
import com.pianoorchestra.ui.Po
import com.pianoorchestra.ui.Ambiance
import com.pianoorchestra.ui.Screen
import com.pianoorchestra.ui.SettingsScreen
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** La langue choisie (français ou russe) s'applique à toute l'application. */
    override fun attachBaseContext(newBase: Context) {
        val lang = Prefs.langOf(newBase)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(Locale(lang))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // l'écran reste allumé pendant qu'on joue

        // utilisé pour les captures d'écran automatiques (format tablette, français et russe)
        intent.getStringExtra("po_lang")?.let { l ->
            if (l != app.prefs.lang) { app.prefs.lang = l; recreate(); return }
        }
        intent.getStringExtra("po_ambiance")?.let { app.prefs.ambiance = it }
        Po.ambiance = Ambiance.of(app.prefs.ambiance)
        val startScreen = intent.getStringExtra("po_screen")?.let { s -> Screen.entries.firstOrNull { it.name.equals(s, true) } }
        val autoSong = intent.getIntExtra("po_song", -1)
        val autoPlay = intent.getBooleanExtra("po_autoplay", false)
        // captures : un accord tenu sur le clavier à l'écran pour montrer le jeu libre
        intent.getStringExtra("po_chord")?.let { c ->
            val notes = c.split(',').mapNotNull { it.trim().toIntOrNull() }
            Thread {
                Thread.sleep(4000)
                notes.forEach { app.screenNoteOn(it) }
                Thread.sleep(300)
                notes.forEach { app.screenNoteOff(it) }
            }.start()
        }

        if (savedInstanceState == null) handleIncomingFile(app, intent)

        setContent {
            PoTheme {
                var screen by rememberSaveable { mutableStateOf(startScreen ?: (if (autoSong >= 0) Screen.PLAYALONG else Screen.HOME)) }
                val lang = app.prefs.lang
                var resumeId by rememberSaveable { mutableStateOf<String?>(null) }
                var catalogBack by rememberSaveable { mutableStateOf(Screen.PLAYALONG) }
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    BackHandler(enabled = screen != Screen.HOME) { screen = if (screen == Screen.CATALOG) catalogBack else Screen.HOME }
                    when (screen) {
                        Screen.HOME -> HomeScreen(app, lang, onLang = { l -> app.prefs.lang = l; recreate() },
                            onOpen = { resumeId = null; if (it == Screen.CATALOG) catalogBack = Screen.HOME; screen = it },
                            onResume = { id -> resumeId = id; screen = Screen.PLAYALONG })
                        Screen.KEYBOARD -> KeyboardScreen(app, lang) { screen = Screen.HOME }
                        Screen.PLAYALONG -> PlayAlongScreen(app, lang, onBack = { screen = Screen.HOME }, autoOpen = autoSong, autoPlay = autoPlay, openId = resumeId,
                            onCatalog = { resumeId = null; catalogBack = Screen.PLAYALONG; screen = Screen.CATALOG })
                        Screen.FREEPLAY -> FreePlayScreen(app, lang) { screen = Screen.HOME }
                        Screen.SETTINGS -> SettingsScreen(app) { screen = Screen.HOME }
                        Screen.EXCHANGE -> ExchangeScreen(app) { screen = Screen.HOME }
                        Screen.CATALOG -> CatalogScreen(app, lang, onBack = { screen = catalogBack },
                            onOpen = { id -> resumeId = id; screen = Screen.PLAYALONG })
                    }
                }
            }
        }
    }

    /** « Ouvrir avec Piano Orchestra » ou « Partager » un fichier MIDI depuis une autre application. */
    private fun handleIncomingFile(app: App, intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            val name = app.library.import(uri)
            Toast.makeText(
                this,
                if (name != null) getString(R.string.import_done, name) else getString(R.string.import_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
