package com.pianoorchestra.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pianoorchestra.App
import com.pianoorchestra.R
import com.pianoorchestra.library.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Le catalogue de partitions gratuites : chercher, filtrer par niveau, télécharger, ouvrir. */
@Composable
fun CatalogScreen(app: App, lang: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<Catalog.Item>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var detail by remember { mutableStateOf<Catalog.Item?>(null) }

    LaunchedEffect(tick) { all = withContext(Dispatchers.IO) { app.catalog.items() } }
    LaunchedEffect(Unit) { if (app.catalog.refresh()) tick++ }

    val shown = remember(all, query, level) {
        all.orEmpty().filter { (level == 0 || it.level == level) && it.matches(query) }
    }

    fun download(item: Catalog.Item, open: Boolean) {
        if (busy[item.id] == true) return
        busy[item.id] = true
        scope.launch {
            try {
                val id = app.catalog.download(item)
                tick++
                if (open) onOpen(id)
                else Toast.makeText(context, context.getString(R.string.import_done, item.fileBase), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.catalog_failed), Toast.LENGTH_LONG).show()
            } finally {
                busy.remove(item.id)
            }
        }
    }
    fun openOrDownload(item: Catalog.Item) {
        val f = app.catalog.downloaded(item)
        if (f != null) onOpen("file:" + f.name) else download(item, open = true)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.catalog_title), onBack) {
            Text(stringResource(R.string.catalog_count, shown.size), color = Po.Muted, fontSize = 16.sp, modifier = Modifier.padding(end = 12.dp))
        }
        // recherche + niveau
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.weight(1f).widthIn(min = 220.dp).glass(RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = Po.Muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.catalog_search), color = Po.Muted, fontSize = 18.sp, maxLines = 1)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Po.Text, fontSize = 18.sp, fontFamily = Jost),
                        cursorBrush = SolidColor(Po.Gold), modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) Icon(Icons.Rounded.Close, null, tint = Po.Muted,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable { query = "" })
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
        ) {
            rowItems(listOf(0, 1, 2, 3)) { l ->
                Chip(levelName(l), selected = level == l) { level = l }
            }
        }

        if (all == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Po.Gold) }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shown, key = { it.id }) { item ->
                    val done = remember(item.id, tick) { app.catalog.downloaded(item) != null }
                    CatalogCard(item, lang, done, busy[item.id] == true,
                        onClick = { detail = item }, onAction = { openOrDownload(item) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.catalog_credits), color = Po.Muted.copy(alpha = 0.7f), fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 20.dp))
                }
            }
        }
    }

    detail?.let { item ->
        val done = remember(item.id, tick) { app.catalog.downloaded(item) != null }
        CatalogDetail(app, item, lang, done, busy[item.id] == true,
            onDismiss = { detail = null },
            onAction = { openOrDownload(item) })
    }
}

@Composable
private fun levelName(l: Int) = stringResource(when (l) {
    1 -> R.string.level_1
    2 -> R.string.level_2
    3 -> R.string.level_3
    else -> R.string.level_all
})

/** Style musical, en français ou en russe. */
fun styleName(s: String, lang: String): String {
    val fr = mapOf("baroque" to "Baroque", "classique" to "Classique", "romantique" to "Romantique", "folk" to "Folklore",
        "jazz" to "Ragtime et jazz", "technique" to "Étude technique", "moderne" to "Moderne", "marche" to "Marche",
        "danse" to "Danse", "hymne" to "Hymne", "chanson" to "Chanson", "concert" to "Concert")
    val ru = mapOf("baroque" to "Барокко", "classique" to "Классицизм", "romantique" to "Романтизм", "folk" to "Народная",
        "jazz" to "Регтайм и джаз", "technique" to "Этюд", "moderne" to "Современная", "marche" to "Марш",
        "danse" to "Танец", "hymne" to "Гимн", "chanson" to "Песня", "concert" to "Концертная")
    return (if (lang == "ru") ru else fr)[s] ?: s
}

@Composable
private fun LevelDots(level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..3) Box(Modifier.size(8.dp).clip(CircleShape)
            .background(if (i <= level) Po.Gold else Color.White.copy(alpha = 0.18f)))
    }
}

@Composable
private fun Cover(item: Catalog.Item, size: Int) {
    val (c1, c2) = Covers[(item.composerFr.hashCode() and 0x7fffffff) % Covers.size]
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp))
            .background(Brush.linearGradient(listOf(c1, c2)))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape((size * 0.3f).dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(if (item.xml != null) Icons.Rounded.LibraryMusic else Icons.Rounded.MusicNote, null,
            tint = Color.White, modifier = Modifier.size((size * 0.48f).dp))
    }
}

@Composable
private fun ActionButton(done: Boolean, busy: Boolean, big: Boolean = false, onClick: () -> Unit) {
    val s = if (big) 64.dp else 50.dp
    Box(
        Modifier.size(s).clip(CircleShape)
            .then(if (done) Modifier.background(Po.accentBrush) else Modifier.glass(CircleShape, strong = true))
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(s * 0.5f), color = Po.Gold, strokeWidth = 3.dp)
            done -> Icon(Icons.Rounded.PlayArrow, stringResource(R.string.catalog_open), tint = Po.Night, modifier = Modifier.size(s * 0.56f))
            else -> Icon(Icons.Rounded.CloudDownload, stringResource(R.string.catalog_download), tint = Po.Text, modifier = Modifier.size(s * 0.5f))
        }
    }
}

@Composable
private fun CatalogCard(item: Catalog.Item, lang: String, done: Boolean, busy: Boolean, onClick: () -> Unit, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 104.dp).glass(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(item, 60)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, color = Po.Text, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
            Text("${item.composer(lang)} · ${styleName(item.style, lang)}", color = Po.Muted, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelDots(item.level)
                if (done) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Rounded.CheckCircle, null, tint = Po.Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.catalog_in_library), color = Po.Teal, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        ActionButton(done, busy, onClick = onAction)
    }
}

@Composable
private fun CatalogDetail(app: App, item: Catalog.Item, lang: String, done: Boolean, busy: Boolean, onDismiss: () -> Unit, onAction: () -> Unit) {
    var image by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    var noImage by remember(item.id) { mutableStateOf(item.preview == null) }
    LaunchedEffect(item.id) {
        val b = app.catalog.preview(item)
        if (b != null) image = b.asImageBitmap() else noImage = true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF2201D40), Color(0xF2121228))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f))), RoundedCornerShape(32.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(item, 72)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, color = Po.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 29.sp)
                    Text(item.composer(lang) + (if (item.opus.isNotBlank()) " · ${item.opus}" else ""), color = Po.Muted, fontSize = 17.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LevelDots(item.level)
                        Spacer(Modifier.width(10.dp))
                        Text("${levelName(item.level)} · ${styleName(item.style, lang)}", color = Po.Muted, fontSize = 15.sp)
                    }
                }
                Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = Po.Muted,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDismiss).padding(8.dp))
            }
            // aperçu du début de la partition (fond blanc, comme une feuille)
            if (!noImage) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFFBF9F4)).padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val img = image
                    if (img == null) CircularProgressIndicator(Modifier.size(28.dp), color = Po.ambiance.accentDeep, strokeWidth = 3.dp)
                    else Image(img, null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
            Text(stringResource(if (item.xml != null) R.string.catalog_formats_xml else R.string.catalog_formats_midi_pdf),
                color = Po.Text, fontSize = 17.sp)
            Text(stringResource(R.string.catalog_license, item.license), color = Po.Muted, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Po.accentBrush)
                        .clickable(enabled = !busy) { onAction() }
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Po.Night, strokeWidth = 3.dp)
                    else Icon(if (done) Icons.Rounded.PlayArrow else Icons.Rounded.CloudDownload, null, tint = Po.Night)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(when {
                        busy -> R.string.catalog_downloading
                        done -> R.string.catalog_open
                        else -> R.string.catalog_download_open
                    }), color = Po.Night, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
