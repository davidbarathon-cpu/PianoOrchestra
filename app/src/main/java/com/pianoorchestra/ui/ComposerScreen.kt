package com.pianoorchestra.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.compose.Arranger
import com.pianoorchestra.compose.Plan
import com.pianoorchestra.player.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Couleur de chaque type de partie, dans la structure du morceau. */
private fun partColor(type: String): Color = when (type) {
    "intro", "outro" -> Color(0xFF9CA3C8)
    "couplet" -> Color(0xFF7FB4FF)
    "prerefrain" -> Color(0xFF8FF0E3)
    "refrain" -> Color(0xFFF3CC86)
    "pont", "interlude" -> Color(0xFFB9A6FF)
    "solo" -> Color(0xFFFFB3CF)
    else -> Color(0xFFB4B3CC)
}

/**
 * Le compositeur : Bermet décrit un morceau (« une valse joyeuse en ré »), la tablette — ou Claude en ligne —
 * l'écrit ; on l'écoute, on le change (« plus lent », « autre mélodie »…), puis on joue avec l'orchestre.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerScreen(app: App, lang: String, onBack: () -> Unit, onPlayAlong: (String) -> Unit, autoRequest: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var change by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var render by remember { mutableStateOf<Arranger.Result?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var savedId by remember { mutableStateOf<String?>(null) }
    val playerState by app.player.state.collectAsState()
    val previewing = playerState.playing && playerState.song?.name == plan?.title

    DisposableEffect(Unit) { onDispose { if (app.player.state.value.song?.name == plan?.title) app.player.stop() } }

    fun show(p: Plan, n: String?) {
        app.player.stop()
        plan = p
        note = n
        savedId = null
    }
    fun run(work: () -> App.Composed?) {
        if (busy) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { work()?.let { it to Arranger.render(it.plan) } }.getOrNull() }
            busy = false
            if (r == null) {
                Toast.makeText(context, context.getString(R.string.composer_not_understood), Toast.LENGTH_LONG).show()
            } else {
                show(r.first.plan, r.first.note)
                render = r.second
            }
        }
    }
    fun compose() {
        val q = text.trim().ifBlank { context.getString(R.string.ex_1) }
        run { app.compose(q) }
    }
    fun modify(what: String) {
        val p = plan ?: return
        run { app.modify(p, what) }
    }
    fun listen() {
        val p = plan ?: return
        val r = render ?: return
        if (previewing) { app.player.stop(); return }
        val song = runCatching { Song.parse(r.midi, p.title).also { it.attachPlan(r.planJson) } }.getOrNull() ?: return
        app.band.stop()
        app.player.load(song)
        app.player.setUserPart(-1)
        app.player.play()
    }
    fun playAlong() {
        val p = plan ?: return
        val r = render ?: return
        app.player.stop()
        val id = savedId ?: app.saveComposition(p, r.midi, r.planJson).also {
            savedId = it
            Toast.makeText(context, context.getString(R.string.composer_saved, p.title), Toast.LENGTH_SHORT).show()
        }
        onPlayAlong(id)
    }

    // po_compose (captures d'écran) : un texte, ou le numéro d'un exemple (1 à 6)
    LaunchedEffect(autoRequest) {
        val ex = listOf(R.string.ex_1, R.string.ex_2, R.string.ex_3, R.string.ex_4, R.string.ex_5, R.string.ex_6)
        val q = autoRequest?.toIntOrNull()?.let { n -> ex.getOrNull(n - 1)?.let { context.getString(it) } } ?: autoRequest
        if (!q.isNullOrBlank()) { text = q; compose() }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.composer_title), { app.player.stop(); onBack() })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            val wide = maxWidth > 760.dp
            val ask: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            SectionTitle(stringResource(R.string.composer_ask))
                            Box(
                                Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black.copy(alpha = 0.22f)).border(1.dp, Po.Stroke, RoundedCornerShape(20.dp))
                                    .padding(18.dp)
                            ) {
                                if (text.isEmpty()) Text(stringResource(R.string.composer_hint), color = Po.Muted, fontSize = 19.sp, lineHeight = 26.sp)
                                BasicTextField(text, { text = it }, textStyle = TextStyle(color = Po.Text, fontSize = 21.sp, lineHeight = 28.sp, fontFamily = Jost),
                                    cursorBrush = SolidColor(Po.Gold), modifier = Modifier.fillMaxWidth())
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (res in listOf(R.string.ex_1, R.string.ex_2, R.string.ex_3, R.string.ex_4, R.string.ex_5, R.string.ex_6)) {
                                    val ex = stringResource(res)
                                    Chip(ex, selected = text == ex) { text = ex }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (app.aiReady) Icons.Rounded.Cloud else Icons.Rounded.TabletAndroid, null,
                                    tint = if (app.aiReady) Po.Teal else Po.Muted, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(if (app.aiReady) R.string.composer_online else R.string.composer_offline),
                                    color = Po.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).background(Po.accentBrush)
                                        .clickable(enabled = !busy) { compose() }.padding(horizontal = 28.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Po.Night, strokeWidth = 3.dp)
                                    else Icon(Icons.Rounded.AutoAwesome, null, tint = Po.Night)
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(if (busy) R.string.composer_working else R.string.composer_go), color = Po.Night,
                                        fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            val result: @Composable (Modifier) -> Unit = { m ->
                val p = plan
                Panel(m) {
                    if (p == null) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFFB3CF), Color(0xFF8A6BFF)))),
                                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(42.dp)) }
                            Text(stringResource(R.string.composer_empty), color = Po.Muted, fontSize = 17.sp, lineHeight = 24.sp)
                        }
                    } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(p.title, color = Po.Text, fontSize = 32.sp, fontWeight = FontWeight.Medium, lineHeight = 36.sp)
                        val secs = render?.seconds ?: 0.0
                        Text("${styleName(p.style, lang)} · ${p.keyName(lang)} · ${p.tempo} BPM · ${MusicText.time(secs)} · " +
                            stringResource(if (p.source == "Claude") R.string.composer_by_claude else R.string.composer_by_tablet),
                            color = Po.Gold, fontSize = 16.sp)
                        note?.let { Text(stringResource(R.string.composer_fallback, it), color = Po.Muted, fontSize = 14.sp) }
                        // la structure : une pastille par partie, largeur selon ses mesures
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (part in p.parts) {
                                val c = partColor(part.type)
                                Column(
                                    Modifier.clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.18f))
                                        .border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(MusicText.section(part.name, lang), color = Po.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text(part.chords.take(4).joinToString("  ") { ch -> ch.split(' ').joinToString(" ") { MusicText.chord(it, lang) } } +
                                        if (part.chords.size > 4) " …" else "", color = Po.Muted, fontSize = 13.sp, maxLines = 1)
                                }
                            }
                        }
                        // écouter, jouer avec, autre version
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(if (previewing) Po.tealBrush else Po.accentBrush)
                                    .clickable { listen() }.padding(horizontal = 22.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, tint = Po.Night)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.composer_listen), color = Po.Night, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                            GlassPill(stringResource(R.string.composer_play_along), Icons.Rounded.Piano) { playAlong() }
                            GlassPill(stringResource(R.string.composer_again), Icons.Rounded.Refresh) {
                                if (p.source == "Claude" && app.aiReady) run { app.compose(p.request.substringBefore(" · ")) }
                                else run { App.Composed(com.pianoorchestra.compose.Composer.compose(
                                    com.pianoorchestra.compose.Composer.read(p.request.substringBefore(" · ")), System.nanoTime()), null) }
                            }
                        }
                        // changer quelque chose
                        SectionTitle(stringResource(R.string.composer_change), Modifier.padding(top = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (res in listOf(R.string.chip_slower, R.string.chip_faster, R.string.chip_happier, R.string.chip_sadder,
                                R.string.chip_up, R.string.chip_no_drums, R.string.chip_other_melody)) {
                                val label = stringResource(res)
                                val french = when (res) {
                                    R.string.chip_slower -> "plus lent"; R.string.chip_faster -> "plus rapide"
                                    R.string.chip_happier -> "plus joyeux, en majeur"; R.string.chip_sadder -> "plus triste, en mineur"
                                    R.string.chip_up -> "monte d'un ton"; R.string.chip_no_drums -> "sans batterie"
                                    else -> "autre mélodie"
                                }
                                Chip(label, selected = false) { modify(french) }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.22f))
                                .border(1.dp, Po.Stroke, RoundedCornerShape(50)).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (change.isEmpty()) Text(stringResource(R.string.composer_change_hint), color = Po.Muted, fontSize = 16.sp, maxLines = 1)
                                BasicTextField(change, { change = it }, singleLine = true,
                                    textStyle = TextStyle(color = Po.Text, fontSize = 17.sp, fontFamily = Jost), cursorBrush = SolidColor(Po.Gold),
                                    modifier = Modifier.fillMaxWidth())
                            }
                            Box(
                                Modifier.size(46.dp).clip(CircleShape).background(Po.accentBrush)
                                    .clickable(enabled = change.isNotBlank() && !busy) { modify(change.trim()); change = "" },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Po.Night, modifier = Modifier.size(24.dp)) }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ask(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                    result(Modifier.weight(1.1f).verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ask(Modifier.fillMaxWidth())
                    result(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
