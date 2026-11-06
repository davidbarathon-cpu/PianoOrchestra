package com.pianoorchestra.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.audio.BackingTrack
import com.pianoorchestra.library.Carnet
import com.pianoorchestra.library.SongLibrary
import kotlin.math.floor

/**
 * Une chanson de répétition : le carnet de Bermet (les parties, ses accords, ses indications) qui défile avec
 * le son du groupe. Plus lent pour apprendre, une partie en boucle, et la partition Guitar Pro à côté.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RehearsalView(app: App, lang: String, entry: SongLibrary.Entry, onBack: () -> Unit, onOpenScore: (SongLibrary.Entry) -> Unit) {
    val carnet = remember(entry.id) { app.library.carnet(entry) }
    if (carnet == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar(entry.title(lang), onBack)
            Text(stringResource(R.string.carnet_unreadable), color = Po.Danger, fontSize = 18.sp, modifier = Modifier.padding(24.dp))
        }
        return
    }
    val audio = remember(entry.id) { app.library.sibling(carnet.audioFile) }
    val track = remember(audio) { audio?.let { runCatching { BackingTrack(it) }.getOrNull() } }
    DisposableEffect(track) { onDispose { track?.release() } }
    val scoreEntry = remember(entry.id) { app.library.entryFor(app.library.sibling(carnet.scoreFile)) }

    var position by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(false) }
    var loopSection by remember { mutableStateOf<Carnet.Section?>(null) }
    var offset by remember { mutableFloatStateOf(app.prefs.carnetOffset(entry.id)) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    // la position du son, image par image ; la boucle sur une partie
    LaunchedEffect(track) {
        val t = track ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            position = t.position
            playing = t.playing
            val ls = loopSection
            if (loop && ls != null && playing) {
                val end = carnet.secondsAtBar(ls.end) + offset
                if (position >= end - 0.03) t.seek(carnet.secondsAtBar(ls.start) + offset)
            }
        }
    }
    val bar = carnet.barAt(position - offset)
    val curBar = floor(bar).toInt()
    val current = carnet.sectionAt(curBar)
    val currentIndex = current?.let { carnet.sections.indexOf(it) } ?: -1
    val next = carnet.sections.getOrNull(currentIndex + 1)
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
    }
    fun seekBar(b: Int) { track?.seek(carnet.secondsAtBar(b) + offset); position = track?.position ?: position }

    Column(Modifier.fillMaxSize()) {
        TopBar(carnet.title.ifBlank { entry.title(lang) }, { track?.pause(); onBack() }) {
            if (scoreEntry != null) {
                GlassPill(stringResource(R.string.carnet_score), Icons.Rounded.LibraryMusic) { track?.pause(); onOpenScore(scoreEntry) }
                Spacer(Modifier.width(8.dp))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            val wide = maxWidth > 760.dp
            val book: @Composable (Modifier) -> Unit = { m ->
                LazyColumn(m, state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(carnet.sections, key = { i, s -> "$i-${s.name}" }) { i, s ->
                        SectionCard(s, lang, isCurrent = i == currentIndex, curBar = curBar,
                            looping = loop && loopSection == s,
                            onClick = { seekBar(s.start); if (loop) loopSection = s })
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
            val transport: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // où on en est
                            SectionTitle(if (carnet.list == "Concert") stringResource(R.string.carnet_concert) else stringResource(R.string.carnet_rehearsal))
                            Text(current?.let { MusicText.section(it.name, lang) }
                                ?: if (curBar < 0) stringResource(R.string.carnet_countin) else "—",
                                color = Po.Text, fontSize = 34.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (current != null) Text(stringResource(R.string.carnet_bar_of, curBar - current.start + 1, current.bars),
                                color = Po.Gold, fontSize = 18.sp)
                            if (next != null) Text(stringResource(R.string.carnet_next, MusicText.section(next.name, lang)), color = Po.Muted, fontSize = 16.sp)
                        }
                    }
                    Panel(Modifier.fillMaxWidth()) {
                        if (track == null) {
                            Text(stringResource(if (carnet.audioFile == null) R.string.carnet_no_audio else R.string.carnet_audio_missing),
                                color = Po.Muted, fontSize = 17.sp, lineHeight = 23.sp)
                        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(78.dp).clip(CircleShape).background(if (playing) Po.tealBrush else Po.accentBrush)
                                        .clickable { if (track.playing) track.pause() else { app.player.stop(); app.band.stop(); track.play() } },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        stringResource(if (playing) R.string.stop else R.string.play), tint = Po.Night, modifier = Modifier.size(46.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    Modifier.size(54.dp).glass(CircleShape).clickable { seekBar(current?.start ?: 0) },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.Replay, stringResource(R.string.carnet_part_again), tint = Po.Text, modifier = Modifier.size(28.dp)) }
                                Spacer(Modifier.width(16.dp))
                                Text("${MusicText.time(position)} / ${MusicText.time(track.duration)}", color = Po.Text, fontSize = 19.sp)
                            }
                            Slider(
                                value = seeking ?: (if (track.duration > 0) (position / track.duration).toFloat() else 0f),
                                onValueChange = { seeking = it },
                                onValueChangeFinished = { seeking?.let { v -> track.seek(v * track.duration) }; seeking = null },
                                colors = SliderDefaults.colors(thumbColor = Po.Gold, activeTrackColor = Po.Gold, inactiveTrackColor = Po.Stroke)
                            )
                            Text(stringResource(R.string.carnet_speed), color = Po.Text, fontSize = 17.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (v in listOf(0.6f, 0.7f, 0.8f, 0.9f, 1f)) {
                                    Chip("${(v * 100).toInt()} %", selected = kotlin.math.abs(speed - v) < 0.01f) { speed = v; track.setSpeed(v) }
                                }
                            }
                            SwitchRow(stringResource(R.string.carnet_loop), stringResource(R.string.carnet_loop_sub), loop) {
                                loop = it; loopSection = if (it) current ?: carnet.sections.firstOrNull() else null
                            }
                            // calage du carnet sur le son (si le WAV ne commence pas pile à la mesure 1)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.carnet_offset), color = Po.Text, fontSize = 17.sp)
                                    Text(stringResource(R.string.carnet_offset_sub), color = Po.Muted, fontSize = 14.sp)
                                }
                                for ((label, d) in listOf("−" to -0.05f, "+" to 0.05f)) {
                                    Box(
                                        Modifier.size(44.dp).glass(CircleShape).clickable {
                                            offset = ((offset + d) * 100).toInt() / 100f
                                            app.prefs.setCarnetOffset(entry.id, offset)
                                        },
                                        contentAlignment = Alignment.Center
                                    ) { Text(label, color = Po.Text, fontSize = 22.sp) }
                                    if (d < 0) Text("%+.2f s".format(offset), color = Po.Gold, fontSize = 16.sp, textAlign = TextAlign.Center,
                                        modifier = Modifier.width(78.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    book(Modifier.weight(1.55f).fillMaxHeight())
                    transport(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    transport(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                    book(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** Une partie du carnet : son nom, ses mesures (4 par ligne), ses accords, ses indications. */
@Composable
private fun SectionCard(s: Carnet.Section, lang: String, isCurrent: Boolean, curBar: Int, looping: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth()
            .then(if (isCurrent) Modifier.clip(shape).background(Brush.verticalGradient(listOf(Po.Gold.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f))))
                .border(2.dp, Po.Gold.copy(alpha = 0.8f), shape) else Modifier.glass(shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(MusicText.section(s.name, lang), color = if (isCurrent) Po.GoldSoft else Po.Text, fontSize = 22.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (looping) Icon(Icons.Rounded.Repeat, null, tint = Po.Teal, modifier = Modifier.size(22.dp).padding(end = 4.dp))
            Text(stringResource(R.string.carnet_bars, s.bars), color = Po.Muted, fontSize = 15.sp)
        }
        for (row in s.chords.indices.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in row) {
                    val b = s.start + k
                    val on = isCurrent && b == curBar
                    Box(
                        Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) Po.accentBrush else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f))))
                            .border(1.dp, Color.White.copy(alpha = if (on) 0.5f else 0.08f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val c = s.chords[k]
                        Text(if (c.isBlank()) "%" else c.split(' ').joinToString("  ") { MusicText.chord(it, lang) },
                            color = if (on) Po.Night else if (c.isBlank()) Po.Muted else Po.Text,
                            fontSize = 21.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        for (n in s.notes) {
            Text("›  $n", color = Po.GoldSoft, fontSize = 17.sp, fontStyle = FontStyle.Italic, lineHeight = 22.sp)
        }
    }
}
