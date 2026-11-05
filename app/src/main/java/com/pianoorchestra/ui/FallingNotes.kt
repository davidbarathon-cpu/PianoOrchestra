package com.pianoorchestra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.player.Song
import com.pianoorchestra.player.SongPlayer

private const val LOW = 21
private const val HIGH = 108
private val WHITE = setOf(0, 2, 4, 5, 7, 9, 11)
private fun white(n: Int) = (n % 12) in WHITE
private val WHITE_INDEX: Map<Int, Int> = run {
    val m = HashMap<Int, Int>(); var k = 0
    for (n in LOW..HIGH) if (white(n)) m[n] = k++
    m
}

private class FallingNote(val start: Double, val end: Double, val pitch: Int, val mine: Boolean)

/**
 * Les notes qui tombent : chaque note de « ma partie » descend vers la touche à jouer, et arrive sur le
 * clavier au moment où il faut la jouer. Les autres instruments (sauf la batterie) passent en transparence.
 * Les colonnes sont exactement celles du clavier dessous (88 touches, toute la largeur).
 */
@Composable
fun FallingView(app: App, lang: String, song: Song, userPart: Int, state: SongPlayer.State, modifier: Modifier, onClose: () -> Unit) {
    val names = stringArrayResource(R.array.note_names)
    val notes = remember(song, userPart) {
        val open = HashMap<Long, Double>()
        val out = ArrayList<FallingNote>()
        for (e in song.events) {
            val p = song.parts.getOrNull(e.part) ?: continue
            if (p.isDrums || e.d1 !in LOW..HIGH) continue
            val kind = e.status and 0xF0
            val key = e.part.toLong() * 256 + e.d1
            if (kind == 0x90 && e.d2 > 0) {
                open[key]?.let { s -> out.add(FallingNote(s, e.time, e.d1, e.part == userPart)) }
                open[key] = e.time
            } else if (kind == 0x80 || kind == 0x90) {
                open.remove(key)?.let { s -> out.add(FallingNote(s, maxOf(e.time, s + 0.06), e.d1, e.part == userPart)) }
            }
        }
        out.sortedBy { it.start }
    }
    // la position, animée image par image entre deux nouvelles du lecteur
    var now by remember { mutableDoubleStateOf(state.position) }
    LaunchedEffect(state.position, state.playing, state.waiting, state.tempo) {
        val base = state.position
        now = base
        if (!state.playing || state.waiting) return@LaunchedEffect
        val t0 = withFrameNanos { it }
        while (true) {
            withFrameNanos { t -> now = base + (t - t0) / 1e9 * state.tempo }
        }
    }
    val chordName = song.chords.lastOrNull { it.time <= now + 0.05 }?.name
    val acc = Po.ambiance

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // une barre compacte : lecture, début, temps, accord, fermer
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).glass(RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(if (state.playing) Po.tealBrush else Po.accentBrush)
                    .clickable { if (state.playing) app.player.stop() else { app.band.stop(); app.player.play() } },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (state.playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    stringResource(if (state.playing) R.string.stop else R.string.play), tint = Po.Night, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(48.dp).glass(CircleShape).clickable { app.player.restart() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Replay, stringResource(R.string.restart), tint = Po.Text, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.width(18.dp))
            Text("${MusicText.time(now.coerceAtLeast(0.0))} / ${MusicText.time(song.duration)}", color = Po.Text, fontSize = 20.sp)
            if (state.waiting) {
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.falling_waiting), color = Po.Teal, fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            if (chordName != null) {
                Text(MusicText.chord(chordName, lang), color = Po.Gold, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(18.dp))
            }
            Box(
                Modifier.size(48.dp).glass(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Close, stringResource(R.string.falling_close), tint = Po.Text, modifier = Modifier.size(26.dp)) }
        }

        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val ww = size.width / WHITE_INDEX.size
            val bw = ww * 0.62f
            val h = size.height
            val look = 3.2                                   // secondes visibles au-dessus du clavier
            val pps = h / look.toFloat()
            // fond : une bande par octave, une ligne fine à chaque do
            for ((n, k) in WHITE_INDEX) {
                if (n % 12 == 0) drawRect(Color.White.copy(alpha = 0.06f), Offset(k * ww, 0f), Size(1f, h))
                if ((n / 12) % 2 == 0 && n % 12 == 0) drawRect(Color.White.copy(alpha = 0.025f), Offset(k * ww, 0f), Size(ww * 7, h))
            }
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = (ww * 0.34f).coerceIn(9f, 26f)
                isFakeBoldText = true
            }
            // les notes, des autres instruments d'abord (en transparence), puis les miennes
            for (pass in 0..1) {
                for (fn in notes) {
                    if (fn.start > now + look) break
                    if (fn.end < now - 0.3) continue
                    if (fn.mine != (pass == 1)) continue
                    val isWhite = white(fn.pitch)
                    val x = if (isWhite) WHITE_INDEX[fn.pitch]!! * ww + ww * 0.08f
                            else (WHITE_INDEX[fn.pitch - 1] ?: continue) * ww + ww - bw / 2
                    val w = if (isWhite) ww * 0.84f else bw
                    val yBottom = h - ((fn.start - now) * pps).toFloat()
                    val yTop = h - ((fn.end - now) * pps).toFloat()
                    val top = yTop.coerceAtLeast(-10f)
                    val bottom = yBottom.coerceAtMost(h)
                    if (bottom <= top) continue
                    val playingNow = fn.start <= now && fn.end >= now
                    val r = CornerRadius(w * 0.32f)
                    if (fn.mine) {
                        val brush = if (isWhite) Brush.verticalGradient(listOf(acc.accentLight, acc.accent, acc.accentDeep), top, bottom)
                                    else Brush.verticalGradient(listOf(acc.accent, acc.accentDeep, acc.accentDeep.copy(alpha = 0.9f)), top, bottom)
                        if (playingNow) drawRoundRect(acc.accent.copy(alpha = 0.35f), Offset(x - 6f, top - 6f), Size(w + 12f, bottom - top + 12f), CornerRadius(w * 0.5f))
                        drawRoundRect(brush, Offset(x, top), Size(w, bottom - top), r)
                        drawRoundRect(Color.White.copy(alpha = if (playingNow) 0.7f else 0.35f), Offset(x, top), Size(w, bottom - top), r, style = Stroke(1.5f))
                        // le nom de la note, en bas de la barre
                        if (bottom - top > paint.textSize * 1.4f && ww > 22f) {
                            paint.color = Po.Night.toArgb()
                            drawContext.canvas.nativeCanvas.drawText(names[fn.pitch % 12], x + w / 2, bottom - paint.textSize * 0.5f, paint)
                        }
                    } else {
                        drawRoundRect(Color.White.copy(alpha = if (playingNow) 0.22f else 0.10f), Offset(x, top), Size(w, bottom - top), r)
                    }
                }
            }
            // la ligne du « maintenant », posée sur le clavier
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, acc.accent.copy(alpha = 0.28f)), h - 46f, h), Offset(0f, h - 46f), Size(size.width, 46f))
            drawRect(Brush.horizontalGradient(listOf(acc.accentDeep, acc.accentLight, acc.accentDeep)), Offset(0f, h - 3f), Size(size.width, 3f))
        }
    }
}
