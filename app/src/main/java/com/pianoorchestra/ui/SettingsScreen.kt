package com.pianoorchestra.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.media.AudioDeviceInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.update.Updater

@Composable
fun SettingsScreen(app: App, onBack: () -> Unit) {
    var volume by remember { mutableFloatStateOf(app.prefs.masterVolume) }
    var outType by remember { mutableIntStateOf(app.prefs.outputType) }
    val outputs = remember { app.audio.outputs() }
    val update by app.updater.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.menu_settings), onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Panel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle(stringResource(R.string.settings_ambiance))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (a in Ambiance.entries) {
                            AmbianceTile(a, Po.ambiance == a, Modifier.weight(1f)) {
                                Po.ambiance = a; app.prefs.ambiance = a.key
                            }
                        }
                    }
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.settings_sound))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.master_volume), color = Po.Text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        Text("${(volume * 100).toInt()} %", color = Po.Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = volume, valueRange = 0.1f..1.4f,
                        onValueChange = { volume = it; app.setMasterVolume(it) },
                        colors = SliderDefaults.colors(thumbColor = Po.Gold, activeTrackColor = Po.Gold, inactiveTrackColor = Po.Stroke)
                    )
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(stringResource(R.string.audio_output))
                    OutputRow(stringResource(R.string.audio_output_auto), outType == -1) {
                        outType = -1; app.prefs.outputType = -1; app.applyOutput()
                    }
                    for (d in outputs) {
                        OutputRow(outputName(d), outType == d.type) {
                            outType = d.type; app.prefs.outputType = d.type; app.applyOutput()
                        }
                    }
                    Text(stringResource(R.string.audio_output_hint), color = Po.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(stringResource(R.string.updates_title))
                    Text(stringResource(R.string.version_label, app.updater.currentVersion), color = Po.Text, fontSize = 18.sp)
                    val u = update
                    val status = when (u) {
                        is Updater.State.Checking -> stringResource(R.string.update_checking)
                        is Updater.State.UpToDate -> stringResource(R.string.update_none)
                        is Updater.State.Offline -> stringResource(R.string.update_error)
                        is Updater.State.Available -> stringResource(R.string.update_available, u.version)
                        is Updater.State.Downloading -> stringResource(R.string.update_downloading, u.percent)
                        is Updater.State.NeedsPermission -> stringResource(R.string.update_allow)
                        else -> ""
                    }
                    if (status.isNotEmpty()) Text(status, color = Po.Muted, fontSize = 16.sp)
                    Row {
                        Button(onClick = { app.updater.check() },
                            colors = ButtonDefaults.buttonColors(containerColor = Po.CardHigh, contentColor = Po.Text)) {
                            Text(stringResource(R.string.update_check), fontSize = 17.sp)
                        }
                        if (u is Updater.State.Available || u is Updater.State.NeedsPermission) {
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = {
                                val a = u as? Updater.State.Available
                                if (a != null) app.updater.install(a) else app.updater.check()
                            }, colors = ButtonDefaults.buttonColors(containerColor = Po.Gold, contentColor = Po.Night)) {
                                Text(stringResource(R.string.update_install), fontSize = 17.sp)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.credits), color = Po.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/** Un aperçu de l'ambiance : son dégradé, ses lumières et une petite carte de verre. */
@Composable
private fun AmbianceTile(a: Ambiance, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) a.accent else Po.Stroke, shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxWidth().height(110.dp)
                .background(Brush.linearGradient(a.bg))
                .background(Brush.radialGradient(listOf(a.glow1.copy(alpha = 0.55f), Color.Transparent), center = Offset(40f, 20f), radius = 260f))
                .background(Brush.radialGradient(listOf(a.glow2.copy(alpha = 0.45f), Color.Transparent), center = Offset(600f, 300f), radius = 300f)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(width = 120.dp, height = 56.dp).glass(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a.accentLight, a.accentDeep))))
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(when (a) {
                Ambiance.NUIT -> R.string.ambiance_nuit
                Ambiance.AURORE -> R.string.ambiance_aurore
                Ambiance.OCEAN -> R.string.ambiance_ocean
            }), color = Po.Text, fontSize = 18.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Rounded.Check, null, tint = a.accent)
        }
    }
}

@Composable
private fun OutputRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Po.Gold))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Po.Text, fontSize = 19.sp)
    }
}

@Composable
private fun outputName(d: AudioDeviceInfo): String = stringResource(
    when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.out_speaker
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG -> R.string.out_wired
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.out_usb
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> R.string.out_bluetooth
        else -> R.string.out_other
    }
)
