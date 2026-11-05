package com.pianoorchestra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val LOWEST = 21          // la0
private const val HIGHEST = 108        // do8
private val WHITE_PC = setOf(0, 2, 4, 5, 7, 9, 11)

private fun isWhite(n: Int) = (n % 12) in WHITE_PC

/** Index de la touche blanche (0 à 51) pour une note blanche. */
private val whiteIndex: Map<Int, Int> = run {
    val m = HashMap<Int, Int>()
    var k = 0
    for (n in LOWEST..HIGHEST) if (isWhite(n)) m[n] = k++
    m
}
private const val WHITE_COUNT = 52

/**
 * Le clavier de 88 touches, comme l'ES-520.
 * [live] : touches jouées (turquoise) ; [guide] : notes de « ma partie » (doré).
 * Sur un écran étroit (téléphone), le clavier défile et se centre sur le do du milieu.
 */
@Composable
fun PianoKeyboard(
    live: Set<Int>,
    guide: Set<Int> = emptySet(),
    noteNames: Array<String>,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    minWhiteWidth: Dp = 22.dp,
    onNoteOn: (Int) -> Unit = {},
    onNoteOff: (Int) -> Unit = {},
) {
    val touched = remember { mutableStateOf(setOf<Int>()) }
    val on = rememberUpdatedState(onNoteOn)
    val off = rememberUpdatedState(onNoteOff)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val whiteW = max(maxWidth.value / WHITE_COUNT, minWhiteWidth.value).dp
        val total = whiteW * WHITE_COUNT
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        val viewport = maxWidth
        LaunchedEffect(whiteW, viewport) {
            if (total > viewport) {
                val center = with(density) { (whiteW * (whiteIndex[60]!! + 0.5f)).toPx() - viewport.toPx() / 2 }
                scroll.scrollTo(center.toInt().coerceAtLeast(0))
            }
        }
        val scope = rememberCoroutineScope()
        Column {
        if (total > viewport) {
            // téléphone : le clavier ne tient pas en entier, deux flèches le font glisser d'une octave
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val octave = with(density) { (whiteW * 7).toPx() }
                IconButton(onClick = { scope.launch { scroll.animateScrollBy(-octave) } }) {
                    Icon(Icons.Rounded.ChevronLeft, null, tint = Po.Gold)
                }
                IconButton(onClick = { scope.launch { scroll.animateScrollBy(octave) } }) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = Po.Gold)
                }
            }
        }
        Row(Modifier.horizontalScroll(scroll, enabled = false)) {
            Canvas(
                Modifier
                    .width(total)
                    .height(height)
                    .pointerInput(whiteW) {
                        val ww = whiteW.toPx()
                        val bw = ww * 0.62f
                        val bh = size.height * 0.62f
                        fun noteAt(o: Offset): Int? {
                            if (o.x < 0 || o.y < 0 || o.y > size.height) return null
                            if (o.y < bh) {
                                for (n in LOWEST..HIGHEST) if (!isWhite(n)) {
                                    val left = (whiteIndex[n - 1] ?: continue) * ww + ww - bw / 2
                                    if (o.x >= left && o.x <= left + bw) return n
                                }
                            }
                            val k = (o.x / ww).toInt().coerceIn(0, WHITE_COUNT - 1)
                            return whiteIndex.entries.first { it.value == k }.key
                        }
                        awaitPointerEventScope {
                            val active = HashMap<PointerId, Int>()
                            while (true) {
                                val ev = awaitPointerEvent()
                                for (c in ev.changes) {
                                    val prev = active[c.id]
                                    val now = if (c.pressed) noteAt(c.position) else null
                                    if (now != prev) {
                                        if (prev != null) { off.value(prev); active.remove(c.id) }
                                        if (now != null) { on.value(now); active[c.id] = now }
                                        touched.value = active.values.toSet()
                                    }
                                    if (c.pressed) c.consume()
                                }
                            }
                        }
                    }
            ) {
                val ww = whiteW.toPx()
                val bw = ww * 0.62f
                val bh = size.height * 0.62f
                val pressed = live + touched.value
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = (ww * 0.42f).coerceIn(9f, 28f)
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val h = size.height
                val gap = (ww * 0.06f).coerceIn(1.5f, 3f)
                val r = CornerRadius(ww * 0.16f)
                val acc = Po.ambiance
                // le lit du clavier, sombre, qu'on aperçoit entre les touches
                drawRect(Brush.verticalGradient(listOf(Color(0xFF07070F), Color(0xFF16152A))))
                // touches blanches : ivoire nacré, légère ombre en bas
                for ((n, k) in whiteIndex) {
                    val x = k * ww
                    val isPressed = n in pressed
                    val isGuide = n in guide
                    val brush = when {
                        isPressed -> Brush.verticalGradient(listOf(Color(0xFFB8FFF4), Color(0xFF5ED3C3), Color(0xFF2FA597)), 0f, h)
                        isGuide -> Brush.verticalGradient(listOf(acc.accentLight, acc.accent, acc.accentDeep), 0f, h)
                        else -> Brush.verticalGradient(listOf(Color(0xFFE9E5DC), Color(0xFFFBF9F4), Color(0xFFF4F0E7), Color(0xFFDCD6CA)), 0f, h)
                    }
                    drawRoundRect(brush, Offset(x + gap / 2, -r.x), Size(ww - gap, h + r.x), r)
                    // la tranche de la touche, plus sombre (sauf enfoncée : elle descend)
                    if (!isPressed) drawRoundRect(Color.Black.copy(alpha = 0.10f), Offset(x + gap / 2, h - ww * 0.22f), Size(ww - gap, ww * 0.22f), r)
                    if (isPressed && isGuide) {
                        drawRoundRect(acc.accent, Offset(x + 3f, 3f), Size(ww - 6f, h - 6f), r, style = Stroke(width = 5f))
                    }
                    if (n % 12 == 0) {                                   // les do, pour se repérer
                        paint.color = (if (n == 60) acc.accentDeep else Color(0xFF8A8BA6)).toArgb()
                        paint.isFakeBoldText = n == 60
                        drawContext.canvas.nativeCanvas.drawText(noteNames[0], x + ww / 2, h - ww * 0.42f, paint)
                    }
                }
                // touches noires : laquées, avec un reflet
                for (n in LOWEST..HIGHEST) if (!isWhite(n)) {
                    val left = (whiteIndex[n - 1] ?: continue) * ww + ww - bw / 2
                    val isPressed = n in pressed
                    val isGuide = n in guide
                    val brush = when {
                        isPressed -> Brush.verticalGradient(listOf(Color(0xFF5ED3C3), Color(0xFF1F7F74)), 0f, bh)
                        isGuide -> Brush.verticalGradient(listOf(acc.accent, acc.accentDeep.copy(alpha = 1f)), 0f, bh)
                        else -> Brush.verticalGradient(listOf(Color(0xFF0C0C18), Color(0xFF2A2A40), Color(0xFF111120)), 0f, bh)
                    }
                    drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(left + 2f, 0f), Size(bw, bh + 4f), CornerRadius(bw * 0.2f))
                    drawRoundRect(brush, Offset(left, -bw * 0.2f), Size(bw, bh + bw * 0.2f), CornerRadius(bw * 0.2f))
                    // reflet vertical et biseau du bout de la touche
                    drawRoundRect(Color.White.copy(alpha = if (isPressed || isGuide) 0.30f else 0.14f),
                        Offset(left + bw * 0.18f, bh * 0.06f), Size(bw * 0.16f, bh * 0.70f), CornerRadius(bw * 0.08f))
                    drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(left + bw * 0.1f, bh - bw * 0.42f), Size(bw * 0.8f, bw * 0.30f), CornerRadius(bw * 0.12f))
                    if (isPressed && isGuide) {
                        drawRoundRect(acc.accentLight, Offset(left + 2f, 2f), Size(bw - 4f, bh - 4f), CornerRadius(bw * 0.2f), style = Stroke(width = 4f))
                    }
                }
                // l'ombre du feutre sur le haut des touches
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), 0f, h * 0.10f), Offset.Zero, Size(size.width, h * 0.10f))
                // fine ligne dorée en haut, comme le feutre d'un piano
                drawRect(Brush.horizontalGradient(listOf(acc.accentDeep, acc.accentLight, acc.accentDeep)), Offset(0f, 0f), Size(size.width, 3f))
            }
        }
        }
    }
}
