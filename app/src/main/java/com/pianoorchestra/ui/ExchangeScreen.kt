package com.pianoorchestra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.exchange.ExchangeServer
import kotlinx.coroutines.delay

/**
 * Échanger avec l'ordinateur : la tablette ouvre une porte sur le wifi local pendant que cet écran est affiché.
 * Riffmaker la trouve tout seul ; un navigateur peut aussi l'ouvrir avec l'adresse affichée.
 */
@Composable
fun ExchangeScreen(app: App, onBack: () -> Unit) {
    var port by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf(app.prefs.exchangeCode) }
    var addresses by remember { mutableStateOf(ExchangeServer.localAddresses()) }
    val log by app.exchangeLog.collectAsState()

    DisposableEffect(Unit) {
        try { port = app.startExchange() } catch (_: Exception) { error = true }
        onDispose { app.stopExchange() }
    }
    // le wifi peut changer (point d'accès activé, autre réseau) : on relit les adresses
    LaunchedEffect(Unit) {
        while (true) { delay(3000); addresses = ExchangeServer.localAddresses() }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.exchange_title), onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            val wide = maxWidth > 760.dp
            val door: @Composable (Modifier) -> Unit = { m ->
                Panel(m) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Halo(ready = port > 0 && addresses.isNotEmpty())
                        if (error) {
                            Text(stringResource(R.string.exchange_error), color = Po.Danger, fontSize = 18.sp)
                        } else if (addresses.isEmpty()) {
                            Icon(Icons.Rounded.WifiOff, null, tint = Po.Muted, modifier = Modifier.size(34.dp))
                            Text(stringResource(R.string.exchange_no_wifi), color = Po.Text, fontSize = 18.sp, lineHeight = 25.sp)
                        } else {
                            Text(stringResource(R.string.exchange_ready), color = Po.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                            // l'adresse à taper dans un navigateur, au besoin
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SectionTitle(stringResource(R.string.exchange_address))
                                for (a in addresses.take(2)) {
                                    Text("http://$a:$port", color = Po.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            // le code
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionTitle(stringResource(R.string.exchange_code))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    for (d in code) {
                                        Box(
                                            Modifier.size(width = 62.dp, height = 76.dp).glass(RoundedCornerShape(18.dp), strong = true),
                                            contentAlignment = Alignment.Center
                                        ) { Text(d.toString(), color = Po.GoldSoft, fontSize = 42.sp, fontWeight = FontWeight.SemiBold) }
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier.size(48.dp).glass(CircleShape).clickable { code = app.prefs.newExchangeCode() },
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Rounded.Refresh, stringResource(R.string.exchange_new_code), tint = Po.Muted) }
                                }
                            }
                        }
                    }
                }
            }
            val help: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(stringResource(R.string.exchange_how))
                            Step(1, stringResource(R.string.exchange_step1))
                            Step(2, stringResource(R.string.exchange_step2))
                            Step(3, stringResource(R.string.exchange_step3))
                            Text(stringResource(R.string.exchange_hotspot), color = Po.Muted, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                    Panel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle(stringResource(R.string.exchange_log))
                            if (log.isEmpty()) Text(stringResource(R.string.exchange_log_empty), color = Po.Muted, fontSize = 16.sp)
                            LazyColumn(Modifier.fillMaxWidth().height(if (wide) 260.dp else 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(log, key = { it.time.toString() + it.name + it.kind }) { e -> LogRow(e) }
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    door(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                    help(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    door(Modifier.fillMaxWidth())
                    help(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Un ordinateur dans un halo qui respire doucement : la porte est ouverte. */
@Composable
private fun Halo(ready: Boolean) {
    val accent = if (ready) Po.Teal else Po.Muted
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(ready) {
        while (ready) { delay(40); phase = (phase + 0.02f) % 1f }
    }
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.25f), Color.Transparent)), r)
            if (ready) {
                for (k in 0..1) {
                    val p = (phase + k * 0.5f) % 1f
                    drawCircle(accent.copy(alpha = (1f - p) * 0.5f), r * (0.45f + 0.5f * p), center = Offset(size.width / 2, size.height / 2),
                        style = Stroke(2.dp.toPx()))
                }
            }
        }
        Box(
            Modifier.size(78.dp).clip(CircleShape)
                .background(if (ready) Po.tealBrush else Brush.linearGradient(listOf(Color(0xFF55557A), Color(0xFF33334E)))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Computer, null, tint = Po.Night, modifier = Modifier.size(40.dp)) }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Po.accentBrush),
            contentAlignment = Alignment.Center
        ) { Text(n.toString(), color = Po.Night, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(14.dp))
        Text(text, color = Po.Text, fontSize = 17.sp, lineHeight = 23.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LogRow(e: App.ExchangeLog) {
    val (icon, color, res) = when (e.kind) {
        ExchangeServer.Kind.RECEIVED -> Triple(Icons.Rounded.Download, Po.Teal, R.string.exchange_received)
        ExchangeServer.Kind.SENT -> Triple(Icons.Rounded.Upload, Po.Gold, R.string.exchange_sent)
        ExchangeServer.Kind.BACKUP -> Triple(Icons.Rounded.Inventory2, Po.Gold, R.string.exchange_backup)
        ExchangeServer.Kind.REFUSED -> Triple(Icons.Rounded.Block, Po.Danger, R.string.exchange_refused)
        ExchangeServer.Kind.CONNECTED -> Triple(Icons.Rounded.Link, Color(0xFFB9A6FF), R.string.exchange_connected)
    }
    val time = remember(e.time) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(e.time)) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(res), color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (e.name.isNotEmpty()) Text(e.name, color = Po.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(time, color = Po.Muted, fontSize = 14.sp)
    }
}
