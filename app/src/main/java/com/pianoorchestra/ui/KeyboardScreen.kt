package com.pianoorchestra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.LiveInput
import com.pianoorchestra.R

/** Les sons proposés pour faire sonner la tablette (numéros General MIDI). */
val ECHO_SOUNDS = listOf(
    0 to R.string.instr_piano, 4 to R.string.instr_epiano, 19 to R.string.instr_organ,
    48 to R.string.instr_strings, 52 to R.string.instr_choir, 73 to R.string.instr_flute,
    24 to R.string.instr_guitar, 89 to R.string.instr_pad, 11 to R.string.instr_vibes, 46 to R.string.instr_harp,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardScreen(app: App, lang: String, onBack: () -> Unit) {
    val device by app.midi.deviceName.collectAsState()
    val keys by app.live.keys.collectAsState()
    val pedal by app.live.pedal.collectAsState()
    val log by app.live.log.collectAsState()
    val names = stringArrayResource(R.array.note_names)
    var echo by remember { mutableStateOf(app.prefs.echo) }
    var program by remember { mutableIntStateOf(app.prefs.echoProgram) }
    val recording by app.recorder.activeFlow.collectAsState()
    val saved by app.lastSaved.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(saved) {
        saved?.let {
            android.widget.Toast.makeText(context, context.getString(R.string.recorded, it), android.widget.Toast.LENGTH_LONG).show()
            app.lastSaved.value = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.menu_keyboard), onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            val wide = maxWidth > 760.dp
            val settings: @Composable (Modifier) -> Unit = { m ->
                Panel(m) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            if (device != null) stringResource(R.string.piano_connected, device!!) else stringResource(R.string.piano_disconnected),
                            color = if (device != null) Po.Teal else Po.Muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.echo_label), color = Po.Text, fontSize = 20.sp)
                                Text(stringResource(R.string.echo_sub), color = Po.Muted, fontSize = 15.sp)
                            }
                            Switch(
                                checked = echo,
                                onCheckedChange = { echo = it; app.prefs.echo = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Po.Night, checkedTrackColor = Po.Teal)
                            )
                        }
                        SectionTitle(stringResource(R.string.instrument_label))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((prog, label) in ECHO_SOUNDS) {
                                Chip(stringResource(label), selected = program == prog) {
                                    program = prog
                                    app.setEchoProgram(prog)
                                }
                            }
                        }
                        Text(stringResource(R.string.demo_hint), color = Po.Muted, fontSize = 15.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(if (recording) R.string.recording else R.string.record),
                                    color = if (recording) Po.Danger else Po.Text, fontSize = 20.sp)
                                Text(stringResource(R.string.record_free_sub), color = Po.Muted, fontSize = 15.sp)
                            }
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(if (recording) Po.Danger else Po.CardHigh)
                                    .clickable { app.toggleFreeRecording() },
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.material3.Icon(
                                    if (recording) androidx.compose.material.icons.Icons.Rounded.Stop else androidx.compose.material.icons.Icons.Rounded.FiberManualRecord,
                                    stringResource(R.string.record), tint = if (recording) Po.Text else Po.Danger)
                            }
                        }
                    }
                }
            }
            val monitor: @Composable (Modifier) -> Unit = { m ->
                Panel(m) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(stringResource(R.string.monitor_title), Modifier.weight(1f))
                            Text(
                                stringResource(if (pedal) R.string.monitor_pedal_down else R.string.monitor_pedal_up),
                                color = if (pedal) Po.Gold else Po.Muted, fontSize = 15.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (log.isEmpty()) Text(stringResource(R.string.monitor_empty), color = Po.Muted, fontSize = 18.sp)
                        LazyColumn {
                            items(log, key = { it.id }) { e -> MonitorLine(e, names, lang) }
                        }
                    }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    settings(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    monitor(Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    settings(Modifier.fillMaxWidth())
                    monitor(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PianoKeyboard(
            live = keys,
            noteNames = names,
            onNoteOn = { app.screenNoteOn(it) },
            onNoteOff = { app.screenNoteOff(it) },
        )
    }
}

@Composable
private fun MonitorLine(e: LiveInput.Entry, names: Array<String>, lang: String) {
    val kind = e.status and 0xF0
    val ch = (e.status and 0x0F) + 1
    val text = when {
        kind == 0x90 && e.d2 > 0 -> stringResource(R.string.monitor_note, noteLabel(e.d1, names, lang), e.d2, ch)
        kind == 0x80 || kind == 0x90 -> stringResource(R.string.monitor_note_off, noteLabel(e.d1, names, lang))
        kind == 0xB0 -> stringResource(if (e.d2 >= 64) R.string.monitor_pedal_down else R.string.monitor_pedal_up)
        kind == 0xC0 -> stringResource(R.string.monitor_program, e.d1 + 1)
        else -> ""
    }
    val on = kind == 0x90 && e.d2 > 0
    Text(text, color = if (on) Po.Text else Po.Muted, fontSize = 17.sp, modifier = Modifier.padding(vertical = 3.dp))
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Po.Night else Po.Text,
        fontSize = 17.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Po.Gold else Po.CardHigh)
            .border(1.dp, if (selected) Po.Gold else Po.Stroke, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}
