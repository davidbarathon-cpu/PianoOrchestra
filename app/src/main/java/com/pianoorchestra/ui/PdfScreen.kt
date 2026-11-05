package com.pianoorchestra.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.library.SongLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lecteur de partitions PDF (IMSLP, partitions achetées, scans) : affichage seul,
 * une page (ou deux sur tablette à l'horizontale), tourne-page au toucher ou au pédalier.
 */
@Composable
fun PdfView(app: App, lang: String, entry: SongLibrary.Entry, onBack: () -> Unit) {
    val file = entry.file ?: return
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    val count = renderer?.pageCount ?: 0
    var page by rememberSaveable { mutableIntStateOf(0) }
    val turn by app.live.pageTurn.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPages = maxWidth > maxHeight && maxWidth > 900.dp
        val step = if (twoPages) 2 else 1
        LaunchedEffect(turn) {
            if (turn.first > 0) page = (page + turn.second * step).coerceIn(0, (count - 1).coerceAtLeast(0))
        }
        Column(Modifier.fillMaxSize()) {
            TopBar(entry.title(lang), onBack) {
                Text(stringResource(R.string.page_of, page + 1, count), color = Po.Muted, fontSize = 18.sp,
                    modifier = Modifier.padding(end = 16.dp))
            }
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .pointerInput(count, twoPages) {
                        detectTapGestures { o ->
                            page = if (o.x < size.width / 3f) (page - step).coerceAtLeast(0)
                                   else (page + step).coerceAtMost((count - 1).coerceAtLeast(0))
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val shown = if (twoPages) listOf(page, page + 1) else listOf(page)
                for (p in shown) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
                        if (renderer != null && p < count) PdfPage(renderer, p)
                    }
                }
            }
            Text(stringResource(R.string.pdf_hint), color = Po.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(200, 2400)
        var image by remember(index, wPx) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(index, wPx) {
            image = withContext(Dispatchers.IO) {
                synchronized(renderer) {
                    runCatching {
                        renderer.openPage(index).use { pg ->
                            val h = (wPx.toLong() * pg.height / pg.width).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(wPx, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(AColor.WHITE)
                            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp.asImageBitmap()
                        }
                    }.getOrNull()
                }
            }
        }
        image?.let { Image(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}
