package com.pianoorchestra.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.library.SongLibrary
import com.pianoorchestra.player.Part
import com.pianoorchestra.player.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlayAlongScreen(app: App, lang: String, onBack: () -> Unit, autoOpen: Int = -1, autoPlay: Boolean = false) {
    val state by app.player.state.collectAsState()
    // gardé quand l'écran tourne : l'identifiant du morceau ouvert
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    val opened = remember(openedId) { openedId?.let { id -> app.library.all().firstOrNull { it.id == id } } }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = app.library.import(uri)
            Toast.makeText(context,
                if (name != null) context.getString(R.string.import_done, name) else context.getString(R.string.import_failed),
                Toast.LENGTH_LONG).show()
            refresh++
        }
    }

    // ouvrir un morceau : lecture du fichier en arrière-plan, puis « ma partie » choisie automatiquement
    LaunchedEffect(opened) {
        val e = opened ?: return@LaunchedEffect
        val current = app.player.state.value
        if (current.playing && current.song?.name == e.title(lang)) return@LaunchedEffect   // déjà en cours (écran tourné)
        loading = true
        val song = withContext(Dispatchers.IO) { runCatching { app.library.load(e, lang) }.getOrNull() }
        loading = false
        if (song == null) {
            Toast.makeText(context, context.getString(R.string.import_failed), Toast.LENGTH_LONG).show()
            openedId = null
            return@LaunchedEffect
        }
        app.player.load(song)
        app.player.setUserPart(defaultUserPart(song))
        if (autoPlay) app.player.play()
    }
    LaunchedEffect(autoOpen) {
        if (autoOpen in app.library.builtIn.indices && openedId == null) openedId = app.library.builtIn[autoOpen].id
    }

    val entry = opened
    if (entry == null) {
        SongList(app, lang, refresh, onBack, onImport = { importLauncher.launch(arrayOf("*/*")) },
            onOpen = { openedId = it.id }, onDeleted = { refresh++ })
    } else {
        BackHandler { app.player.stop(); openedId = null }
        val song = state.song
        if (loading || song == null || song.name != entry.title(lang)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Po.Gold) }
        } else {
            PlayerView(app, lang, song, onBack = { app.player.stop(); openedId = null })
        }
    }
}

/** La mélodie si elle existe, sinon le piano, sinon rien. */
fun defaultUserPart(song: Song): Int {
    val melodic = song.parts.filter { !it.isDrums && it.noteCount > 0 }
    melodic.firstOrNull { it.name.lowercase().let { n -> "mélodie" in n || "melod" in n || "melody" in n } }?.let { return it.index }
    melodic.filter { it.program in 0..7 }.maxByOrNull { it.noteCount }?.let { return it.index }
    return -1
}

@Composable
private fun SongList(
    app: App, lang: String, refresh: Int, onBack: () -> Unit,
    onImport: () -> Unit, onOpen: (SongLibrary.Entry) -> Unit, onDeleted: () -> Unit,
) {
    val mine = remember(refresh) { app.library.imported() }
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<SongLibrary.Entry?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.songs_title), onBack) {
            TextButton(onClick = onImport) {
                Icon(Icons.Rounded.Add, null, tint = Po.Gold)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.import_song), color = Po.Gold, fontSize = 17.sp)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (mine.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.songs_mine), Modifier.padding(top = 8.dp)) }
                items(mine, key = { it.id }) { e ->
                    SongCard(e, lang, onClick = { onOpen(e) }, onDelete = { toDelete = e }, onShare = {
                        app.library.shareIntent(e)?.let { context.startActivity(android.content.Intent.createChooser(it, null)) }
                    })
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.songs_builtin), Modifier.padding(top = 8.dp)) }
            items(app.library.builtIn, key = { it.id }) { e -> SongCard(e, lang, onClick = { onOpen(e) }) }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
    }
    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_confirm, e.title(lang))) },
            confirmButton = { TextButton(onClick = { app.library.delete(e); toDelete = null; onDeleted() }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SongCard(e: SongLibrary.Entry, lang: String, onClick: () -> Unit, onDelete: (() -> Unit)? = null, onShare: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Po.Card)
            .border(1.dp, Po.Stroke, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Po.Gold.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.MusicNote, null, tint = Po.Gold, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title(lang), color = Po.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(e.style(lang), color = Po.Muted, fontSize = 16.sp)
        }
        if (onShare != null) {
            IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, stringResource(R.string.share), tint = Po.Muted) }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete_song), tint = Po.Muted) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerView(app: App, lang: String, song: Song, onBack: () -> Unit) {
    val state by app.player.state.collectAsState()
    val guide by app.player.guide.collectAsState()
    val keys by app.live.keys.collectAsState()
    val names = stringArrayResource(R.array.note_names)
    val context = LocalContext.current

    val parts = remember(song) { song.parts.filter { it.noteCount > 0 } }
    var userPart by remember(song) { mutableIntStateOf(defaultUserPart(song)) }
    val muted = remember(song) { mutableStateListOf(*Array(song.parts.size) { false }) }
    val solo = remember(song) { mutableStateListOf(*Array(song.parts.size) { false }) }
    val volume = remember(song) { mutableStateListOf(*Array(song.parts.size) { 1f }) }
    var countIn by remember { mutableStateOf(app.player.countIn) }
    var waitMode by remember { mutableStateOf(false) }
    var progressive by remember { mutableStateOf(app.player.progressive) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var tempoDrag by remember { mutableStateOf<Float?>(null) }
    var loopA by remember(song) { mutableStateOf(-1.0) }

    // une prise vient d'être enregistrée : on l'annonce
    val saved by app.lastSaved.collectAsState()
    LaunchedEffect(saved) {
        saved?.let {
            Toast.makeText(context, context.getString(R.string.recorded, it), Toast.LENGTH_LONG).show()
            app.lastSaved.value = null
        }
    }
    LaunchedEffect(Unit) { app.player.setWaitMode(false) }

    val section = song.sectionAt(state.position)
    val (chordNow, chordNext) = song.chordAt(state.position)

    Column(Modifier.fillMaxSize()) {
        TopBar(song.name, onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            val wide = maxWidth > 760.dp
            val controls: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // lecture
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(84.dp).clip(CircleShape).background(if (state.playing) Po.Teal else Po.Gold)
                                        .clickable { if (state.playing) app.player.stop() else app.player.play() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(if (state.playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                        stringResource(if (state.playing) R.string.stop else R.string.play),
                                        tint = Po.Night, modifier = Modifier.size(52.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    Modifier.size(60.dp).clip(CircleShape).background(Po.CardHigh)
                                        .clickable { app.player.restart() },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.Replay, stringResource(R.string.restart), tint = Po.Text, modifier = Modifier.size(32.dp)) }
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    Modifier.size(60.dp).clip(CircleShape)
                                        .background(if (state.recording) Po.Danger else Po.CardHigh)
                                        .clickable { if (state.recording) app.player.stopRecording() else { app.player.startRecording(); if (!state.playing) app.player.play() } },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.FiberManualRecord, stringResource(R.string.record),
                                    tint = if (state.recording) Po.Text else Po.Danger, modifier = Modifier.size(30.dp)) }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${MusicText.time(state.position)} / ${MusicText.time(song.duration)}", color = Po.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.song_info, (song.bpm * state.tempo).toInt(), "${song.beatsPerBar}/4"), color = Po.Muted, fontSize = 16.sp)
                                    when {
                                        state.recording -> Text(stringResource(R.string.recording), color = Po.Danger, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        state.waiting -> Text(stringResource(R.string.waiting), color = Po.Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        section != null -> Text(MusicText.section(section.name, lang), color = Po.Teal, fontSize = 16.sp)
                                    }
                                }
                                // l'accord du moment, en grand, et le suivant
                                if (chordNow != null) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(MusicText.chord(chordNow.name, lang), color = Po.Gold, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                        if (chordNext != null) Text("→ " + MusicText.chord(chordNext.name, lang), color = Po.Muted, fontSize = 18.sp)
                                    }
                                }
                            }
                            Slider(
                                value = seeking ?: (state.position / song.duration.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f),
                                onValueChange = { seeking = it },
                                onValueChangeFinished = { seeking?.let { app.player.seek(it * song.duration) }; seeking = null },
                                colors = SliderDefaults.colors(thumbColor = Po.Gold, activeTrackColor = Po.Gold, inactiveTrackColor = Po.Stroke)
                            )
                        }
                    }
                    // parties du morceau (repères)
                    if (song.sections.isNotEmpty()) {
                        Panel(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle(stringResource(R.string.sections))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (sec in song.sections) {
                                        Chip(MusicText.section(sec.name, lang), selected = sec == section) { app.player.seek(sec.start) }
                                    }
                                }
                            }
                        }
                    }
                    // travailler : tempo, attente, boucle
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionTitle(stringResource(R.string.practice))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.tempo), color = Po.Text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.tempo_value, ((tempoDrag ?: state.tempo.toFloat()) * 100).toInt()), color = Po.Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = tempoDrag ?: state.tempo.toFloat(), valueRange = 0.5f..1.2f, steps = 13,
                                onValueChange = { tempoDrag = it; app.player.setTempo(it.toDouble()) },
                                onValueChangeFinished = { tempoDrag = null },
                                colors = SliderDefaults.colors(thumbColor = Po.Gold, activeTrackColor = Po.Gold, inactiveTrackColor = Po.Stroke)
                            )
                            SwitchRow(stringResource(R.string.countin), null, countIn) { countIn = it; app.player.countIn = it }
                            SwitchRow(stringResource(R.string.wait_mode), stringResource(R.string.wait_mode_sub), waitMode) { waitMode = it; app.player.setWaitMode(it) }
                            SwitchRow(stringResource(R.string.progressive), stringResource(R.string.progressive_sub), progressive) { progressive = it; app.player.progressive = it }
                            Text(stringResource(R.string.loop), color = Po.Text, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip(stringResource(R.string.loop_a), selected = loopA >= 0 && !state.looping) {
                                    loopA = state.position
                                }
                                Chip(stringResource(R.string.loop_b), selected = false) {
                                    if (loopA >= 0 && state.position > loopA + 0.5) { app.player.setLoop(loopA, state.position); loopA = -1.0 }
                                }
                                if (section != null) Chip(stringResource(R.string.loop_section), selected = state.looping &&
                                        kotlin.math.abs(state.loopStart - section.start) < 0.05) {
                                    app.player.setLoop(section.start, section.end)
                                }
                                Chip(stringResource(R.string.loop_off), selected = !state.looping && loopA < 0) {
                                    loopA = -1.0; app.player.setLoop(-1.0, -1.0)
                                }
                            }
                            if (state.looping) Text(
                                stringResource(R.string.loop_info, MusicText.time(state.loopStart), MusicText.time(state.loopEnd), state.loops),
                                color = Po.Teal, fontSize = 16.sp
                            )
                        }
                    }
                    // ma partie
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(stringResource(R.string.my_part))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip(stringResource(R.string.my_part_none), selected = userPart == -1) {
                                    userPart = -1; app.player.setUserPart(-1)
                                }
                                for (p in parts.filter { !it.isDrums }) {
                                    Chip(partLabel(p), selected = userPart == p.index) {
                                        userPart = p.index; app.player.setUserPart(p.index)
                                    }
                                }
                            }
                            if (userPart >= 0) Text(stringResource(R.string.my_part_hint), color = Po.Muted, fontSize = 15.sp)
                        }
                    }
                }
            }
            val mixer: @Composable (Modifier) -> Unit = { m ->
                Panel(m) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle(stringResource(R.string.mixer))
                        for (p in parts) {
                            MixerRow(
                                label = partLabel(p),
                                isUser = p.index == userPart,
                                muted = muted[p.index],
                                solo = solo[p.index],
                                volume = volume[p.index],
                                onMute = { muted[p.index] = it; app.player.setMuted(p.index, it) },
                                onSolo = { solo[p.index] = it; app.player.setSolo(p.index, it) },
                                onVolume = { volume[p.index] = it; app.player.setVolume(p.index, it) },
                            )
                        }
                    }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    controls(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    mixer(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    controls(Modifier.fillMaxWidth())
                    mixer(Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PianoKeyboard(live = keys, guide = guide, noteNames = names,
            onNoteOn = { app.screenNoteOn(it) }, onNoteOff = { app.screenNoteOff(it) })
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Po.Text, fontSize = 18.sp)
            if (subtitle != null) Text(subtitle, color = Po.Muted, fontSize = 14.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Po.Night, checkedTrackColor = Po.Teal))
    }
}

@Composable
private fun MixerRow(
    label: String, isUser: Boolean, muted: Boolean, solo: Boolean, volume: Float,
    onMute: (Boolean) -> Unit, onSolo: (Boolean) -> Unit, onVolume: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(150.dp)) {
            Text(label, color = if (muted) Po.Muted else Po.Text, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (isUser) Text(stringResource(R.string.you_play), color = Po.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (isUser) {
            Icon(Icons.Rounded.Headphones, null, tint = Po.Gold, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.weight(1f))
        } else {
            IconButton(onClick = { onMute(!muted) }) {
                Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, stringResource(R.string.mute),
                    tint = if (muted) Po.Danger else Po.Text)
            }
            Text(
                "S",
                color = if (solo) Po.Night else Po.Muted,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (solo) Po.Teal else Po.CardHigh)
                    .clickable { onSolo(!solo) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Slider(
                value = volume, onValueChange = onVolume, modifier = Modifier.weight(1f).padding(start = 12.dp),
                colors = SliderDefaults.colors(thumbColor = Po.Teal, activeTrackColor = Po.Teal, inactiveTrackColor = Po.Stroke)
            )
        }
    }
}

/** « Mélodie · Flûte », « Basse », « Batterie »… dans la langue choisie. */
@Composable
fun partLabel(p: Part): String {
    val n = p.name.lowercase()
    val role = when {
        "mélodie" in n || "melod" in n -> R.string.role_melody
        "thème" in n || "theme" in n -> R.string.role_theme
        "contrechant" in n || "counter" in n -> R.string.role_counter
        "réponse" in n -> R.string.role_answers
        "solo" in n -> R.string.role_solo
        else -> null
    }
    val fam = when {
        p.isDrums -> R.string.fam_drums
        p.program < 8 -> R.string.fam_piano
        p.program < 16 -> R.string.fam_chromatic
        p.program < 24 -> R.string.fam_organ
        p.program < 32 -> R.string.fam_guitar
        p.program < 40 -> R.string.fam_bass
        p.program < 52 -> R.string.fam_strings
        p.program < 55 -> R.string.fam_choir
        p.program < 56 -> R.string.fam_ensemble
        p.program < 64 -> R.string.fam_brass
        p.program < 72 -> R.string.fam_reed
        p.program < 80 -> R.string.fam_pipe
        p.program < 104 -> R.string.fam_synth
        else -> R.string.fam_other
    }
    return if (role != null) "${stringResource(role)} · ${stringResource(fam)}" else stringResource(fam)
}
