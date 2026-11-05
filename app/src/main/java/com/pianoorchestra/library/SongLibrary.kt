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
        fun title(lang: String) = if (lang == "ru") titleRu else titleFr
        fun style(lang: String) = if (lang == "ru") styleRu else styleFr
    }

    private val dir = File(context.filesDir, "songs").apply { mkdirs() }

    val builtIn = listOf(
        Entry("01_ballade", "Ballade en do", "Баллада в до мажоре", "Ballade", "Баллада", asset = "songs/01_ballade.mid"),
        Entry("02_pop_rock", "Pop rock en sol", "Поп-рок в соль мажоре", "Pop rock", "Поп-рок", asset = "songs/02_pop_rock.mid"),
        Entry("03_acoustique", "Balade acoustique en ré", "Акустика в ре мажоре", "Acoustique", "Акустика", asset = "songs/03_acoustique.mid"),
        Entry("04_soul", "Soul en fa", "Соул в фа мажоре", "Soul", "Соул", asset = "songs/04_soul.mid"),
        Entry("05_blues", "Blues en la", "Блюз в ля мажоре", "Blues", "Блюз", asset = "songs/05_blues.mid"),
        Entry("06_cinematique", "Thème de film en la mineur", "Киномузыка в ля миноре", "Musique de film", "Киномузыка", asset = "songs/06_cinematique.mid"),
    )

    fun imported(): List<Entry> =
        (dir.listFiles { f -> f.extension.lowercase() in setOf("mid", "midi", "kar") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val name = f.nameWithoutExtension.replace('_', ' ')
                Entry("file:" + f.name, name, name, "MIDI", "MIDI", file = f)
            }

    fun all(): List<Entry> = imported() + builtIn

    fun load(e: Entry, lang: String): Song {
        val bytes = when {
            e.asset != null -> context.assets.open(e.asset).use { it.readBytes() }
            e.file != null -> e.file.readBytes()
            else -> error("vide")
        }
        return Song.parse(bytes, e.title(lang))
    }

    /** Copie un fichier MIDI choisi par l'utilisatrice dans la bibliothèque. Renvoie son titre, ou null. */
    fun import(uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        try { Song.parse(bytes, "") } catch (_: Exception) { return null }
        var name = displayName(uri) ?: "morceau"
        name = name.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "morceau" }
        var f = File(dir, "$name.mid")
        var k = 2
        while (f.exists()) { f = File(dir, "$name ($k).mid"); k++ }
        f.writeBytes(bytes)
        return f.nameWithoutExtension
    }

    fun delete(e: Entry) { e.file?.delete() }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { uri.lastPathSegment }
}
