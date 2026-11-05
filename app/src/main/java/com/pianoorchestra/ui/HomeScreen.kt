package com.pianoorchestra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.update.Updater

@Composable
fun HomeScreen(app: App, lang: String, onLang: (String) -> Unit, onOpen: (Screen) -> Unit) {
    val device by app.midi.deviceName.collectAsState()
    val sound by app.soundState.collectAsState()
    val update by app.updater.state.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 760.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 48.dp else 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // en-tête : nom de l'app, bonjour, et le choix de la langue
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name).uppercase(), color = Po.Gold, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.home_greeting), color = Po.Text,
                        fontSize = if (wide) 44.sp else 34.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.home_subtitle), color = Po.Muted, fontSize = 20.sp)
                }
                if (wide) LanguageToggle(lang, onLang)
            }
            if (!wide) LanguageToggle(lang, onLang)

            // le piano est-il branché ? les sons sont-ils prêts ?
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (device != null) Po.Teal else Po.Muted.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Rounded.Usb, null, tint = if (device != null) Po.Teal else Po.Muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (device != null) stringResource(R.string.piano_connected, device!!) else stringResource(R.string.piano_disconnected),
                            color = Po.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (device == null) Text(stringResource(R.string.piano_hint), color = Po.Muted, fontSize = 16.sp)
                        when (sound) {
                            App.SoundState.LOADING -> Text(stringResource(R.string.sounds_loading), color = Po.Gold, fontSize = 16.sp)
                            App.SoundState.ERROR -> Text(stringResource(R.string.sounds_error), color = Po.Danger, fontSize = 16.sp)
                            else -> {}
                        }
                    }
                    if (sound == App.SoundState.LOADING) CircularProgressIndicator(Modifier.size(28.dp), color = Po.Gold, strokeWidth = 3.dp)
                }
            }

            // une nouvelle version ?
            val up = update
            if (up is Updater.State.Available) {
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = Po.Gold)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.update_available, up.version), color = Po.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Button(onClick = { app.updater.install(up) },
                            colors = ButtonDefaults.buttonColors(containerColor = Po.Gold, contentColor = Po.Night)) {
                            Text(stringResource(R.string.update_install), fontSize = 17.sp)
                        }
                    }
                }
            }

            // les trois grandes portes
            val cards = listOf<@Composable (Modifier) -> Unit>(
                { m -> BigCard(Icons.Rounded.LibraryMusic, stringResource(R.string.menu_playalong), stringResource(R.string.menu_playalong_sub), m) { onOpen(Screen.PLAYALONG) } },
                { m -> BigCard(Icons.Rounded.Piano, stringResource(R.string.menu_keyboard), stringResource(R.string.menu_keyboard_sub), m, accent = Po.Teal) { onOpen(Screen.KEYBOARD) } },
                { m -> BigCard(Icons.Rounded.Tune, stringResource(R.string.menu_settings), stringResource(R.string.menu_settings_sub), m, accent = Po.GoldSoft) { onOpen(Screen.SETTINGS) } },
            )
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    cards.forEachIndexed { i, c -> c(Modifier.weight(if (i == 0) 1.3f else 1f)) }
                }
            } else {
                cards.forEach { c -> c(Modifier.fillMaxWidth()) }
            }
        }
    }
}
