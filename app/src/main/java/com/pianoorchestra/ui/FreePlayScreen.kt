package com.pianoorchestra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.band.Band
import com.pianoorchestra.band.Style

private val STYLE_NAMES = mapOf(
    "pop" to R.string.style_pop, "ballade" to R.string.style_ballade, "rock" to R.string.style_rock,
    "valse" to R.string.style_valse, "bossa" to R.string.style_bossa, "jazz" to R.string.style_jazz,
)

/** Jeu libre : Bermet joue des accords de la main gauche, le groupe suit dans le style choisi. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreePlayScreen(app: App, lang: String, onBack: () -> Unit) {
    val st by app.band.state.collectAsState()
    val keys by app.live.keys.collectAsState()
    val names = stringArrayResource(R.array.note_names)
    var tempoDrag by remember { mutableStateOf<Float?>(null) }
    val style = Style.byId(st.style)
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved by app.lastSaved.collectAsState()
    androidx.compose.runtime.LaunchedEffect(saved) {
        saved?.let {
            android.widget.Toast.makeText(context, context.getString(R.string.composition_saved, it), android.widget.Toast.LENGTH_LONG).show()
            app.lastSaved.value = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.menu_freeplay), { app.band.stop(); onBack() })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            val wide = maxWidth > 760.dp
            val stage: @Composable (Modifier) -> Unit = { m ->
                Panel(m) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        modifier = Modifier.fillMaxSize()) {
                        // l'accord, en très grand, dans un anneau de lumière
                        val accent = Po.Gold
                        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val r = size.minDimension / 2f
                                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent)), r)
                                drawCircle(accent.copy(alpha = 0.35f), r * 0.92f, style = Stroke(1.5.dp.toPx()))
                                drawCircle(Color.White.copy(alpha = 0.12f), r * 0.76f, style = Stroke(1.dp.toPx()))
                            }
                            Text(
                                st.chord?.let { MusicText.chord(it, lang) } ?: "—",
                                fontSize = 84.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                style = TextStyle(brush = Brush.verticalGradient(listOf(Color(0xFFFFF4DE), Po.GoldSoft, Po.ambiance.accentDeep)),
                                    shadow = Shadow(accent.copy(alpha = 0.45f), blurRadius = 40f))
                            )
                        }
                        if (st.chord == null) Text(stringResource(R.string.chord_hint), color = Po.Muted, fontSize = 18.sp)
                        // les temps de la mesure
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            for (b in 0 until style.beats) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(
                                    if (st.running && st.beat == b) (if (b == 0) Po.Gold else Po.Teal) else Color.White.copy(alpha = 0.16f)))
                            }
                        }
                        Box(
                                Modifier.size(96.dp).clip(CircleShape).background(if (st.running) Po.tealBrush else Po.accentBrush)
                                    .clickable { app.band.startStop() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(if (st.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                    stringResource(if (st.running) R.string.stop else R.string.start),
                                    tint = Po.Night, modifier = Modifier.size(60.dp))
                            }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BigButton("${stringResource(R.string.variation)} ${if (st.variation == 0) "A" else "B"}", null) { app.band.variation() }
                            BigButton(stringResource(R.string.ending), if (st.ending) Po.Danger else null) { app.band.ending() }
                            RecordButton(st.recording, st.recordSeconds) { app.toggleComposition() }
                        }
                    }
                }
            }
            val settings: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(stringResource(R.string.styles))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (s in Style.ALL) {
                                    Chip(stringResource(STYLE_NAMES[s.id] ?: R.string.style_pop), selected = st.style == s.id) { app.band.setStyle(s.id) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.tempo), color = Po.Text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.bpm_value, (tempoDrag ?: st.bpm.toFloat()).toInt()), color = Po.Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = tempoDrag ?: st.bpm.toFloat(), valueRange = 40f..200f,
                                onValueChange = { tempoDrag = it; app.band.setTempo(it.toInt()) },
                                onValueChangeFinished = { tempoDrag = null },
                                colors = SliderDefaults.colors(thumbColor = Po.Gold, activeTrackColor = Po.Gold, inactiveTrackColor = Po.Stroke)
                            )
                        }
                    }
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionTitle(stringResource(R.string.instruments))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((kind, res) in listOf(Band.PartKind.DRUMS to R.string.part_drums, Band.PartKind.BASS to R.string.part_bass,
                                    Band.PartKind.COMP to R.string.part_comp, Band.PartKind.PAD to R.string.part_pad)) {
                                    val on = kind in st.parts
                                    Chip(stringResource(res), selected = on) { app.band.setPart(kind, !on) }
                                }
                            }
                            SwitchRow(stringResource(R.string.sync_start), stringResource(R.string.sync_start_sub), st.armed) { app.band.setArmed(it) }
                            SwitchRow(stringResource(R.string.left_hand), stringResource(R.string.left_hand_sub), st.leftHandOnly) { app.band.setLeftHandOnly(it) }
                        }
                    }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    stage(Modifier.weight(1.15f).fillMaxHeight())
                    settings(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    stage(Modifier.fillMaxWidth())
                    settings(Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PianoKeyboard(live = keys, noteNames = names,
            onNoteOn = { app.screenNoteOn(it) }, onNoteOff = { app.screenNoteOff(it) })
    }
}

/** Composer : enregistre Bermet et le groupe ; le morceau va dans « Mes morceaux » (et peut partir dans Riffmaker). */
@Composable
private fun RecordButton(recording: Boolean, seconds: Int, onClick: () -> Unit) {
    Row(
        (if (recording) Modifier.clip(RoundedCornerShape(22.dp)).background(Po.Danger.copy(alpha = 0.85f))
         else Modifier.glass(RoundedCornerShape(22.dp)))
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(if (recording) 14.dp else 16.dp).clip(if (recording) RoundedCornerShape(3.dp) else CircleShape)
            .background(if (recording) Color.White else Po.Danger))
        Spacer(Modifier.width(10.dp))
        Text(if (recording) "%d:%02d".format(seconds / 60, seconds % 60) else stringResource(R.string.compose),
            color = Po.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BigButton(label: String, color: Color?, onClick: () -> Unit) {
    Text(
        label, color = Po.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium,
        modifier = (if (color != null) Modifier.clip(RoundedCornerShape(22.dp)).background(color) else Modifier.glass(RoundedCornerShape(22.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp)
    )
}

