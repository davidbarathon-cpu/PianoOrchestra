package com.pianoorchestra.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pianoorchestra.player.Song
import java.io.File

/**
 * La bibliothèque, entièrement sur la tablette (rien ne passe par internet) :
 * les morceaux fournis avec l'application et ceux que Bermet importe.
 */
class SongLibrary(private val context: Context) {

    class Entry(
        val id: String,
        val titleFr: String,
        val titleRu: String,
        val styleFr: String,
        val styleRu: String,
        val asset: String? = null,
        val file: File? = null,
    ) {
        val builtIn: Boolean get() = asset != null
        /** Partition (Guitar Pro, MusicXML) plutôt que fichier MIDI. */
        val isPdf: Boolean get() = (asset ?: file?.name ?: "").lowercase().endsWith(".pdf")
        val isScore: Boolean get() = (asset ?: file?.name ?: "").substringAfterLast('.').lowercase() in SCORE_EXT
        fun title(lang: String) = if (lang == "ru") titleRu else titleFr
        fun style(lang: String) = if (lang == "ru") styleRu else styleFr
    }

    private val dir = File(context.filesDir, "songs").apply { mkdirs() }
    val songsDir: File get() = dir

    val builtIn = listOf(
        Entry("01_ballade", "Ballade en do", "Баллада в до мажоре", "Ballade", "Баллада", asset = "songs/01_ballade.mid"),
        Entry("02_pop_rock", "Pop rock en sol", "Поп-рок в соль мажоре", "Pop rock", "Поп-рок", asset = "songs/02_pop_rock.mid"),
        Entry("03_acoustique", "Balade acoustique en ré", "Акустика в ре мажоре", "Acoustique", "Акустика", asset = "songs/03_acoustique.mid"),
        Entry("04_soul", "Soul en fa", "Соул в фа мажоре", "Soul", "Соул", asset = "songs/04_soul.mid"),
        Entry("05_blues", "Blues en la", "Блюз в ля мажоре", "Blues", "Блюз", asset = "songs/05_blues.mid"),
        Entry("06_cinematique", "Thème de film en la mineur", "Киномузыка в ля миноре", "Musique de film", "Киномузыка", asset = "songs/06_cinematique.mid"),
    )

    /** Les mêmes morceaux, en partition (Guitar Pro écrit par Riffmaker) : mélodie, guitare, basse, batterie. */
    val builtInScores = builtIn.map { e ->
        Entry(e.id + "_gp", e.titleFr, e.titleRu, "Partition · " + e.styleFr, "Ноты · " + e.styleRu,
            asset = e.asset!!.removeSuffix(".mid") + ".gp5")
    }

    fun imported(): List<Entry> =
        (dir.listFiles { f -> f.extension.lowercase() in MIDI_EXT + SCORE_EXT + "pdf" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val name = f.nameWithoutExtension.replace('_', ' ')
                val kind = if (f.extension.lowercase() == "pdf") "PDF" else if (f.extension.lowercase() in SCORE_EXT) {
                    if (f.extension.lowercase().startsWith("gp")) "Guitar Pro" else "MusicXML"
                } else "MIDI"
                Entry("file:" + f.name, name, name, kind, kind, file = f)
            }

    fun all(): List<Entry> = imported() + builtIn + builtInScores

    /** Le contenu brut d'un morceau (pour la partition). */
    fun bytes(e: Entry): ByteArray = when {
        e.asset != null -> context.assets.open(e.asset).use { it.readBytes() }
        e.file != null -> e.file.readBytes()
        else -> ByteArray(0)
    }

    fun load(e: Entry, lang: String): Song {
        val bytes = when {
            e.asset != null -> context.assets.open(e.asset).use { it.readBytes() }
            e.file != null -> e.file.readBytes()
            else -> error("vide")
        }
        val song = Song.parse(bytes, e.title(lang))
        // la grille d'accords de Riffmaker, si le plan accompagne le morceau
        val plan = when {
            e.asset != null -> runCatching { context.assets.open(e.asset.removeSuffix(".mid") + ".plan.json").use { it.readBytes() } }.getOrNull()
            e.file != null -> File(e.file.parentFile, e.file.nameWithoutExtension + ".plan.json").takeIf { it.exists() }?.readBytes()
            else -> null
        }
        if (plan != null) runCatching { song.attachPlan(String(plan, Charsets.UTF_8)) }
        return song
    }

    /** Garde un enregistrement dans « Mes morceaux ». Renvoie son titre. */
    fun saveRecording(bytes: ByteArray, base: String): String {
        val stamp = java.text.SimpleDateFormat("d MMM HH'h'mm", java.util.Locale.FRANCE).format(java.util.Date())
        var name = "$base - $stamp".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var f = File(dir, "$name.mid")
        var k = 2
        while (f.exists()) { f = File(dir, "$name ($k).mid"); k++ }
        f.writeBytes(bytes)
        return f.nameWithoutExtension
    }

    /** Envoyer un morceau à une autre app (e-mail, Drive, Riffmaker…). */
    fun shareIntent(e: Entry): android.content.Intent? {
        val f = e.file ?: return null
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        return android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("audio/midi")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Copie un fichier MIDI choisi par l'utilisatrice dans la bibliothèque. Renvoie son titre, ou null. */
    fun import(uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val display = displayName(uri) ?: "morceau"
        var ext = display.substringAfterLast('.', "").lowercase()
        if (ext !in SCORE_EXT && ext != "pdf") {
            // MIDI : on vérifie qu'il se lit
            try { Song.parse(bytes, "") } catch (_: Exception) { return null }
            ext = "mid"
        }
        var name = display.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "morceau" }
        var f = File(dir, "$name.$ext")
        var k = 2
        while (f.exists()) { f = File(dir, "$name ($k).$ext"); k++ }
        f.writeBytes(bytes)
        return f.nameWithoutExtension
    }

    fun delete(e: Entry) { e.file?.delete() }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { uri.lastPathSegment }

    companion object {
        val MIDI_EXT = setOf("mid", "midi", "kar")
        val SCORE_EXT = setOf("gp", "gp3", "gp4", "gp5", "gpx", "xml", "musicxml", "mxl")
    }
}
