package com.pianoorchestra.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.library.SongLibrary
import com.pianoorchestra.player.Json
import com.pianoorchestra.player.Song

/**
 * La partition (Guitar Pro, MusicXML) : alphaTab la dessine, Piano Orchestra joue l'orchestre
 * avec le même lecteur que « Jouer avec un groupe » (attente, tempo, ma partie), et le curseur suit.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScoreView(app: App, lang: String, entry: SongLibrary.Entry, onBack: () -> Unit) {
    val state by app.player.state.collectAsState()
    val guide by app.player.guide.collectAsState()
    val keys by app.live.keys.collectAsState()
    val names = stringArrayResource(R.array.note_names)
    val wide = LocalConfiguration.current.screenWidthDp > 760

    var webView by remember { mutableStateOf<WebView?>(null) }
    var song by remember { mutableStateOf<Song?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rendered by remember { mutableStateOf(false) }
    val trackOfPart = remember { HashMap<Int, Int>() }         // partie -> piste de la partition
    val melodic = remember { mutableStateListOf<Pair<Int, String>>() }
    var userPart by remember { mutableIntStateOf(-1) }
    var waitMode by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { app.player.stop(); webView?.destroy() }
    }

    // le curseur suit la lecture
    LaunchedEffect(state.position, song, rendered) {
        val s = song ?: return@LaunchedEffect
        if (rendered && s === state.song) webView?.evaluateJavascript("po.cursor(${s.tickAt(state.position)})", null)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(entry.title(lang), { app.player.stop(); onBack() })
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .build()
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                loader.shouldInterceptRequest(request.url)
                        }
                        val bytes = app.library.bytes(entry)
                        val scale = if (wide) 1.0 else 0.75
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onReady() {
                                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                post { evaluateJavascript("po.load('$b64', $scale, false)", null) }
                            }

                            @JavascriptInterface
                            fun onMidi(b64: String, tracksJson: String, @Suppress("UNUSED_PARAMETER") title: String) {
                                try {
                                    val s = Song.parse(Base64.decode(b64, Base64.DEFAULT), entry.title(lang))
                                    // les noms des pistes de la partition, par canal
                                    val tracks = Json.parse(tracksJson) as? List<*> ?: emptyList<Any>()
                                    for (t in tracks) {
                                        val m = t as? Map<*, *> ?: continue
                                        val idx = (m["index"] as? Double)?.toInt() ?: continue
                                        val nm = m["name"] as? String ?: ""
                                        val chans = listOfNotNull((m["channel"] as? Double)?.toInt(), (m["channel2"] as? Double)?.toInt())
                                        for (p in s.parts) if (p.channel in chans) {
                                            if (nm.isNotBlank()) p.name = nm
                                            trackOfPart[p.index] = idx
                                        }
                                    }
                                    val parts = s.parts.filter { it.noteCount > 0 && !it.isDrums }
                                    val def = parts.firstOrNull { p -> p.name.lowercase().let { "mélodie" in it || "melod" in it || "chant" in it || "voice" in it || "vocal" in it } }
                                        ?: parts.firstOrNull { it.program < 8 } ?: parts.firstOrNull()
                                    post {
                                        melodic.clear()
                                        melodic.addAll(parts.map { it.index to it.name })
                                        userPart = def?.index ?: -1
                                        app.band.stop()
                                        app.player.load(s)
                                        app.player.setUserPart(userPart)
                                        song = s
                                        trackOfPart[userPart]?.let { evaluateJavascript("po.showTracks([$it])", null) }
                                    }
                                } catch (e: Exception) {
                                    post { error = e.message ?: "?" }
                                }
                            }

                            @JavascriptInterface
                            fun onRendered() { post { rendered = true } }

                            @JavascriptInterface
                            fun onError(msg: String) { post { error = msg } }
                        }, "Android")
                        loadUrl("https://appassets.androidplatform.net/assets/score/index.html")
                        webView = this
                    }
                }
            )
            if (!rendered && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Po.Gold)
            error?.let {
                Text(stringResource(R.string.import_failed) + "\n" + it, color = Po.Danger, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.Center).background(Po.Card).padding(20.dp))
            }
        }

        // barre de lecture compacte
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(if (state.playing) Po.Teal else Po.Gold)
                    .clickable(enabled = song != null) { if (state.playing) app.player.stop() else app.player.play() },
                contentAlignment = Alignment.Center
            ) { Icon(if (state.playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, tint = Po.Night, modifier = Modifier.size(40.dp)) }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Po.CardHigh).clickable { app.player.restart() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Replay, null, tint = Po.Text) }
            Text(MusicText.time(state.position), color = Po.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { app.player.setTempo(state.tempo - 0.05) }) { Icon(Icons.Rounded.Remove, null, tint = Po.Text) }
            Text(stringResource(R.string.tempo_value, (state.tempo * 100).toInt()), color = Po.Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { app.player.setTempo(state.tempo + 0.05) }) { Icon(Icons.Rounded.Add, null, tint = Po.Text) }
            Chip(stringResource(R.string.wait_mode), selected = waitMode) { waitMode = !waitMode; app.player.setWaitMode(waitMode) }
            if (state.waiting) Text(stringResource(R.string.waiting), color = Po.Gold, fontSize = 16.sp)
            Spacer(Modifier.width(4.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((idx, nm) in melodic) {
                    Chip(nm.ifBlank { "${idx + 1}" }, selected = userPart == idx) {
                        userPart = idx
                        app.player.setUserPart(idx)
                        trackOfPart[idx]?.let { webView?.evaluateJavascript("po.showTracks([$it])", null) }
                    }
                }
            }
        }
        PianoKeyboard(live = keys, guide = guide, noteNames = names, height = 120.dp,
            onNoteOn = { app.screenNoteOn(it) }, onNoteOff = { app.screenNoteOff(it) })
    }
}
