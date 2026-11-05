package com.pianoorchestra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.BuildConfig
import com.pianoorchestra.R
import com.pianoorchestra.update.Updater

@Composable
fun HomeScreen(app: App, lang: String, onLang: (String) -> Unit, onOpen: (Screen) -> Unit, onResume: (String) -> Unit = {}) {
    val device by app.midi.deviceName.collectAsState()
    val sound by app.soundState.collectAsState()
    val update by app.updater.state.collectAsState()
    val lastId = app.prefs.lastSong
    val last = remember(lastId) { lastId?.let { id -> app.library.all().firstOrNull { it.id == id } } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 760.dp
        val tall = maxHeight > 620.dp
        Column(
            Modifier
                .fillMaxSize()
                .then(if (wide && tall) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(horizontal = if (wide) 52.dp else 20.dp, vertical = if (wide) 40.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (wide) 26.dp else 18.dp)
        ) {
            // en-tête : nom de l'app, bonjour, et le choix de la langue
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name).uppercase(), color = Po.Gold, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.home_greeting), color = Po.Text,
                        fontSize = if (wide) 56.sp else 38.sp, fontWeight = FontWeight.Medium, lineHeight = if (wide) 62.sp else 44.sp)
                    Text(stringResource(R.string.home_subtitle), color = Po.Muted, fontSize = 21.sp, fontWeight = FontWeight.Light)
                }
                if (wide) LanguageToggle(lang, onLang)
            }
            if (!wide) LanguageToggle(lang, onLang)

            // état du piano + reprendre le dernier morceau
            val statusCard: @Composable (Modifier) -> Unit = { m ->
                Row(m.glass(RoundedCornerShape(26.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val on = device != null
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(17.dp))
                            .background((if (on) Po.Teal else Po.Muted).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Usb, null, tint = if (on) Po.Teal else Po.Muted, modifier = Modifier.size(28.dp)) }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (on) stringResource(R.string.piano_connected, device!!) else stringResource(R.string.piano_disconnected),
                            color = Po.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        when (sound) {
                            App.SoundState.LOADING -> Text(stringResource(R.string.sounds_loading), color = Po.Gold, fontSize = 15.sp)
                            App.SoundState.ERROR -> Text(stringResource(R.string.sounds_error), color = Po.Danger, fontSize = 15.sp)
                            else -> Text(if (on) stringResource(R.string.sounds_ready) else stringResource(R.string.piano_hint),
                                color = Po.Muted, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (sound == App.SoundState.LOADING) CircularProgressIndicator(Modifier.size(26.dp), color = Po.Gold, strokeWidth = 3.dp)
                    else Box(Modifier.size(12.dp).shadow(if (on) 8.dp else 0.dp, CircleShape, ambientColor = Po.Teal, spotColor = Po.Teal)
                        .clip(CircleShape).background(if (on) Po.Teal else Po.Muted.copy(alpha = 0.4f)))
                }
            }
            val resumeCard: @Composable (Modifier) -> Unit = { m ->
                Row(m.glass(RoundedCornerShape(26.dp))
                    .clickable { if (last != null) onResume(last.id) else onOpen(Screen.PLAYALONG) }
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(58.dp).shadow(14.dp, CircleShape, ambientColor = Po.Gold, spotColor = Po.Gold)
                            .clip(CircleShape).background(Po.accentBrush),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.PlayArrow, null, tint = Po.Night, modifier = Modifier.size(30.dp)) }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.resume).uppercase(), color = Po.Gold, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                        Text(last?.title(lang) ?: stringResource(R.string.resume_empty), color = Po.Text, fontSize = 20.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (last != null) Text(if (lang == "ru") last.styleRu else last.styleFr, color = Po.Muted, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (wide) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    statusCard(Modifier.weight(1f).fillMaxHeight())
                    resumeCard(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                statusCard(Modifier.fillMaxWidth())
                resumeCard(Modifier.fillMaxWidth())
            }

            // une nouvelle version ?
            val up = update
            if (up is Updater.State.Available) {
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp), strong = true).padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = Po.Gold)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.update_available, up.version), color = Po.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.update_install), color = Po.Night, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Po.accentBrush)
                            .clickable { app.updater.install(up) }.padding(horizontal = 22.dp, vertical = 10.dp))
                }
            }

            // les quatre grandes portes
            val cards = listOf<@Composable (Modifier) -> Unit>(
                { m -> BigCard(Icons.Rounded.LibraryMusic, stringResource(R.string.menu_playalong), stringResource(R.string.menu_playalong_sub), m) { onOpen(Screen.PLAYALONG) } },
                { m -> BigCard(Icons.Rounded.MusicNote, stringResource(R.string.menu_freeplay), stringResource(R.string.menu_freeplay_sub), m, accent = Color(0xFFB9A6FF)) { onOpen(Screen.FREEPLAY) } },
                { m -> BigCard(Icons.Rounded.Piano, stringResource(R.string.menu_keyboard), stringResource(R.string.menu_keyboard_sub), m, accent = Po.Teal) { onOpen(Screen.KEYBOARD) } },
                { m -> BigCard(Icons.Rounded.Tune, stringResource(R.string.menu_settings), stringResource(R.string.menu_settings_sub), m, accent = Po.Text) { onOpen(Screen.SETTINGS) } },
            )
            when {
                wide && tall -> Row(Modifier.weight(1f).fillMaxWidth().heightIn(min = 220.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    cards.forEach { c -> c(Modifier.weight(1f).fillMaxHeight()) }
                }
                wide -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    cards.forEach { c -> c(Modifier.weight(1f).fillMaxHeight()) }
                }
                else -> cards.forEach { c -> c(Modifier.fillMaxWidth()) }
            }

            // bas de page
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME), color = Po.Muted.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }
    }
}
