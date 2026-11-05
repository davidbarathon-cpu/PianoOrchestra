package com.pianoorchestra.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Le catalogue de partitions gratuites : environ 800 morceaux de piano.
 * - Mutopia Project : domaine public ou Creative Commons, en MIDI (pour jouer avec) et PDF (pour lire).
 * - ASAP : grandes œuvres de concert en MusicXML (partition animée).
 * La liste est livrée avec l'application (consultable sans internet) et se met à jour depuis GitHub.
 * Seul le téléchargement d'un morceau demande internet ; ensuite il reste dans « Mes morceaux ».
 */
class Catalog(private val context: Context, private val library: SongLibrary) {

    class Item(
        val id: String,
        val title: String,
        val opus: String,
        val composerFr: String,
        val composerRu: String,
        /** baroque, classique, romantique, folk, jazz, technique, moderne, marche, danse, hymne, chanson, concert */
        val style: String,
        /** 1 débutant, 2 intermédiaire, 3 avancé */
        val level: Int,
        val source: String,
        val mid: String?,
        val pdf: String?,
        val preview: String?,
        val xml: String?,
        val license: String,
    ) {
        fun composer(lang: String) = if (lang == "ru") composerRu else composerFr
        /** Nom du fichier dans la bibliothèque, sans extension. */
        val fileBase: String get() = "$composerFr – $title".replace(Regex("[\\\\/:*?\"<>|]"), "_").take(90)
        private val words by lazy { "$title $opus $composerFr $composerRu".lowercase().stripAccents() }
        fun matches(q: String) = q.isBlank() || q.lowercase().stripAccents().split(' ').filter { it.isNotBlank() }.all { it in words }
    }

    private val cached = File(context.filesDir, "catalog.json")

    @Volatile private var items: List<Item>? = null

    fun items(): List<Item> = items ?: synchronized(this) {
        items ?: run {
            val bundled = context.assets.open("catalog/catalog.json").use { it.readBytes() }.toString(Charsets.UTF_8)
            val fromWeb = cached.takeIf { it.exists() }?.readText()
            val text = if (fromWeb != null && version(fromWeb) > version(bundled)) fromWeb else bundled
            parse(text).also { items = it }
        }
    }

    /** Récupère une liste plus récente sur GitHub, si internet est là. Renvoie true si elle a changé. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = get(REMOTE).toString(Charsets.UTF_8)
            val current = maxOf(BUNDLED_VERSION, cached.takeIf { it.exists() }?.let { version(it.readText()) } ?: 0)
            if (version(text) > current) {
                parse(text)                                   // vérifie qu'elle se lit
                cached.writeText(text)
                items = null
                true
            } else false
        }.getOrDefault(false)
    }

    /** Déjà téléchargé ? (on regarde si le fichier est dans la bibliothèque) */
    fun downloaded(item: Item): File? {
        val dir = library.songsDir
        return listOf("mid", "musicxml", "pdf").map { File(dir, "${item.fileBase}.$it") }.firstOrNull { it.exists() }
    }

    /**
     * Télécharge le morceau dans « Mes morceaux » : MIDI + PDF pour Mutopia, MusicXML pour ASAP.
     * Renvoie l'identifiant de bibliothèque du fichier à ouvrir.
     */
    suspend fun download(item: Item): String = withContext(Dispatchers.IO) {
        val dir = library.songsDir
        var main: File? = null
        item.mid?.let { url ->
            val bytes = get(url)
            com.pianoorchestra.player.Song.parse(bytes, "")       // c'est bien un MIDI lisible
            main = File(dir, "${item.fileBase}.mid").apply { writeBytes(bytes) }
        }
        item.xml?.let { url ->
            main = File(dir, "${item.fileBase}.musicxml").apply { writeBytes(get(url)) }
        }
        item.pdf?.let { url ->
            runCatching { get(url) }.getOrNull()?.takeIf { it.size > 4 && String(it, 0, 4, Charsets.US_ASCII) == "%PDF" }?.let {
                val f = File(dir, "${item.fileBase}.pdf")
                f.writeBytes(it)
                if (main == null) main = f
            }
        }
        "file:" + (main ?: error("rien à télécharger")).name
    }

    private val previews = object : android.util.LruCache<String, Bitmap>(12) {}

    /** L'aperçu de la première ligne de la partition (Mutopia), gardé en mémoire. */
    suspend fun preview(item: Item): Bitmap? = withContext(Dispatchers.IO) {
        val url = item.preview ?: return@withContext null
        previews.get(url) ?: runCatching { get(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
            ?.also { previews.put(url, it) }
    }

    companion object {
        const val REMOTE = "https://raw.githubusercontent.com/davidbarathon-cpu/PianoOrchestra/main/catalog/catalog.json"
        const val BUNDLED_VERSION = 2

        fun version(json: String): Int = Regex("\"version\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: 0

        fun parse(text: String): List<Item> {
            val arr = JSONObject(text).getJSONArray("items")
            return List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                fun s(k: String) = o.optString(k, "").ifBlank { null }
                Item(o.getString("id"), o.getString("t"), o.optString("o", ""), o.getString("cf"), o.optString("cr", o.getString("cf")),
                    o.optString("s", "classique"), o.optInt("l", 2), o.optString("src", ""),
                    s("mid"), s("pdf"), s("img"), s("xml"), o.optString("lic", ""))
            }
        }

        fun get(url: String, redirects: Int = 5): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "PianoOrchestra/1.0 (Android)")
            try {
                val code = c.responseCode
                if (code in 300..399 && redirects > 0) {
                    val loc = c.getHeaderField("Location") ?: error("redirection vide")
                    return get(URL(URL(url), loc).toString(), redirects - 1)
                }
                if (code !in 200..299) error("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
    }
}

private fun String.stripAccents(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
