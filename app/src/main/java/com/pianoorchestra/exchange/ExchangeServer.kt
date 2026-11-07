package com.pianoorchestra.exchange

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * L'échange de fichiers entre la tablette et l'ordinateur (Riffmaker, ou n'importe quel navigateur),
 * sur le wifi de la maison ou le point d'accès de la tablette — sans internet.
 *
 * - Un petit serveur web sur la tablette (port 8765) : une page pour le navigateur du PC, et une API
 *   simple pour Riffmaker (liste, téléchargement, envoi de fichiers, sauvegarde complète en .zip).
 * - Un « phare » UDP (port 8767) : Riffmaker crie « PIANO_ORCHESTRA? » sur le réseau, la tablette répond
 *   avec son nom et son port. Pas besoin de taper d'adresse.
 * - Un code à 4 chiffres, affiché sur la tablette, protège l'accès aux fichiers.
 *
 * Écrit sans rien d'Android, pour pouvoir être testé sur un PC.
 */
class ExchangeServer(
    private val store: Store,
    private val code: () -> String,
    private val info: () -> Map<String, Any>,
    private val page: () -> ByteArray,
    private val onEvent: (Event) -> Unit = {},
    /** Un réglage envoyé par l'ordinateur (la clé de l'IA en ligne pour le compositeur). Renvoie true s'il est accepté. */
    private val settings: (String, String) -> Boolean = { _, _ -> false },
) {
    data class FileInfo(val name: String, val size: Long, val modified: Long, val kind: String)

    /** Ce que la tablette sait faire avec ses fichiers. */
    interface Store {
        fun list(): List<FileInfo>
        fun file(name: String): File?
        /** Range un fichier reçu. Renvoie le nom sous lequel il est gardé, ou null s'il est refusé. */
        fun save(name: String, data: InputStream, length: Long, replace: Boolean): String?
    }

    enum class Kind { CONNECTED, RECEIVED, SENT, BACKUP, REFUSED, SETTING }
    data class Event(val kind: Kind, val name: String, val from: String)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var beacon: DatagramSocket? = null
    private var pool = Executors.newCachedThreadPool()
    @Volatile var port: Int = 0
        private set
    val running: Boolean get() = server != null

    /** Démarre (si besoin) et renvoie le port. */
    @Synchronized
    fun start(preferred: Int = PORT): Int {
        if (server != null) return port
        if (pool.isShutdown) pool = Executors.newCachedThreadPool()
        var s: ServerSocket? = null
        for (p in preferred until preferred + 6) {
            s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) } }.getOrNull()
            if (s != null) break
        }
        val sock = s ?: error("aucun port libre")
        server = sock
        port = sock.localPort
        pool.execute { acceptLoop(sock) }
        runCatching {
            val b = DatagramSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true; broadcast = true; bind(InetSocketAddress(DISCOVERY_PORT))
            }
            beacon = b
            pool.execute { beaconLoop(b) }
        }
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        runCatching { beacon?.close() }
        server = null
        beacon = null
        pool.shutdownNow()
    }

    // ------------------------------------------------------------------ le phare

    private fun beaconLoop(b: DatagramSocket) {
        val buf = ByteArray(512)
        while (!b.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                b.receive(p)
                val msg = String(p.data, 0, p.length, Charsets.UTF_8).trim()
                if (msg.startsWith(DISCOVERY_ASK)) {
                    val answer = "$DISCOVERY_ANSWER " + json(info() + mapOf("port" to port))
                    val out = answer.toByteArray(Charsets.UTF_8)
                    b.send(DatagramPacket(out, out.size, p.address, p.port))
                }
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ le serveur web

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: Exception) { break }
            try { pool.execute { handle(c) } } catch (_: Exception) { runCatching { c.close() } }
        }
    }

    private class Request(
        val method: String, val path: String, val query: Map<String, String>,
        val headers: Map<String, String>, val body: InputStream,
    ) {
        val length: Long get() = headers["content-length"]?.toLongOrNull() ?: -1
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val from = sock.inetAddress?.hostAddress ?: "?"
            try {
                val req = readRequest(input) ?: return
                route(req, out, from)
            } catch (e: Exception) {
                runCatching { respond(out, 500, "text/plain; charset=utf-8", (e.message ?: "erreur").toByteArray()) }
            }
            runCatching { out.flush() }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.length > 32_000) return null
        }
        val lines = String(head.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).split("\r\n")
        val first = lines.first().split(' ')
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val target = first[1]
        val path = decode(target.substringBefore('?'))
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }
        return Request(first[0].uppercase(), path, query, headers, input)
    }

    private fun authorized(r: Request): Boolean {
        val given = r.headers["x-po-code"] ?: r.query["code"]
            ?: r.headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("po_code=") }?.substringAfter('=')
        return given != null && given == code()
    }

    private fun route(r: Request, out: OutputStream, from: String) {
        when {
            r.method == "OPTIONS" -> respond(out, 204, "text/plain", ByteArray(0))
            r.method == "GET" && (r.path == "/" || r.path == "/index.html") ->
                respond(out, 200, "text/html; charset=utf-8", page())
            r.method == "GET" && r.path == "/api/hello" ->
                respondJson(out, 200, json(info() + mapOf("port" to port)))
            !authorized(r) -> respondJson(out, 401, json(mapOf("error" to "code")))
            r.method == "GET" && r.path == "/api/info" -> {
                onEvent(Event(Kind.CONNECTED, "", from))
                respondJson(out, 200, json(info() + mapOf("port" to port, "files" to store.list().size)))
            }
            r.method == "GET" && r.path == "/api/files" ->
                respondJson(out, 200, "[" + store.list().joinToString(",") {
                    json(mapOf("name" to it.name, "size" to it.size, "modified" to it.modified, "kind" to it.kind))
                } + "]")
            r.method == "GET" && r.path == "/api/backup.zip" -> {
                sendBackup(out)
                onEvent(Event(Kind.BACKUP, "", from))
            }
            r.method == "GET" && r.path.startsWith("/api/files/") -> {
                val name = r.path.removePrefix("/api/files/")
                val f = store.file(name)
                if (f == null || !f.isFile) respondJson(out, 404, json(mapOf("error" to "introuvable")))
                else {
                    sendFile(out, f)
                    onEvent(Event(Kind.SENT, f.name, from))
                }
            }
            (r.method == "PUT" || r.method == "POST") && r.path.startsWith("/api/settings/") -> {
                val len = r.length
                if (len !in 1..4096) { respondJson(out, 413, json(mapOf("error" to "taille"))); return }
                val value = Limited(r.body, len).readBytes().toString(Charsets.UTF_8).trim()
                val ok = settings(r.path.removePrefix("/api/settings/"), value)
                if (ok) onEvent(Event(Kind.SETTING, r.path.removePrefix("/api/settings/"), from))
                respondJson(out, if (ok) 200 else 404, json(mapOf("ok" to ok)))
            }
            (r.method == "PUT" || r.method == "POST") && r.path.startsWith("/api/files/") -> {
                val name = r.path.removePrefix("/api/files/")
                if (r.length < 0 || r.length > MAX_UPLOAD) {
                    respondJson(out, 413, json(mapOf("error" to "taille")))
                    return
                }
                val limited = Limited(r.body, r.length)
                val saved = store.save(name, limited, r.length, replace = r.query["replace"] != "0")
                limited.drain()
                if (saved == null) {
                    onEvent(Event(Kind.REFUSED, name, from))
                    respondJson(out, 415, json(mapOf("error" to "type")))
                } else {
                    onEvent(Event(Kind.RECEIVED, saved, from))
                    respondJson(out, 200, json(mapOf("ok" to true, "name" to saved)))
                }
            }
            else -> respondJson(out, 404, json(mapOf("error" to "introuvable")))
        }
    }

    private fun sendFile(out: OutputStream, f: File) {
        val disposition = "attachment; filename*=UTF-8''" + URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        header(out, 200, mime(f.name), f.length(), "Content-Disposition: $disposition\r\n")
        f.inputStream().use { it.copyTo(out, 64 * 1024) }
    }

    private fun sendBackup(out: OutputStream) {
        // taille inconnue d'avance : la connexion se ferme à la fin
        val h = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n" +
            "Content-Disposition: attachment; filename=\"Piano Orchestra - sauvegarde.zip\"\r\n" +
            CORS + "Connection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.UTF_8))
        val zip = ZipOutputStream(out)
        for (fi in store.list()) {
            val f = store.file(fi.name) ?: continue
            zip.putNextEntry(ZipEntry(fi.name).apply { time = f.lastModified() })
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
    }

    private fun respondJson(out: OutputStream, status: Int, body: String) =
        respond(out, status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        header(out, status, type, body.size.toLong())
        out.write(body)
    }

    private fun header(out: OutputStream, status: Int, type: String, length: Long, extra: String = "") {
        val text = when (status) {
            200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"; 404 -> "Not Found"
            413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; else -> "Error"
        }
        val h = "HTTP/1.1 $status $text\r\nContent-Type: $type\r\nContent-Length: $length\r\n" +
            CORS + "Cache-Control: no-store\r\n" + extra + "Connection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.UTF_8))
    }

    /** Lit au plus `length` octets du corps de la requête. */
    private class Limited(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else inner.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        fun drain() { val buf = ByteArray(8192); while (read(buf) > 0) Unit }
    }

    companion object {
        const val PORT = 8765
        const val DISCOVERY_PORT = 8767
        const val DISCOVERY_ASK = "PIANO_ORCHESTRA?"
        const val DISCOVERY_ANSWER = "PIANO_ORCHESTRA"
        const val MAX_UPLOAD = 300L * 1024 * 1024
        private const val CORS = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: X-PO-Code, Content-Type\r\n" +
            "Access-Control-Allow-Methods: GET, PUT, POST, OPTIONS\r\n"

        private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        fun mime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "mid", "midi", "kar" -> "audio/midi"
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "xml", "musicxml" -> "application/vnd.recordare.musicxml+xml"
            "mxl" -> "application/vnd.recordare.musicxml"
            else -> "application/octet-stream"
        }

        /** Un petit JSON à plat (texte, nombres, booléens). */
        fun json(m: Map<String, Any?>): String = m.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${esc(k)}\":" + when (v) {
                null -> "null"
                is Number, is Boolean -> v.toString()
                else -> "\"${esc(v.toString())}\""
            }
        }

        private fun esc(s: String) = buildString {
            for (ch in s) when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append(String.format("\\u%04x", ch.code))
                else -> append(ch)
            }
        }

        /** Les adresses de la tablette sur le réseau local (wifi, point d'accès). */
        fun localAddresses(): List<String> = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().map { ni.name to it } }
                .filter { (_, a) -> a is java.net.Inet4Address && a.isSiteLocalAddress }
                .sortedBy { (n, _) -> if (n.startsWith("wlan")) 0 else if (n.startsWith("ap") || n.startsWith("swlan")) 1 else 2 }
                .map { (_, a) -> a.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .distinct()
        }.getOrDefault(emptyList())

        @Suppress("unused")
        private fun loopback(): InetAddress = InetAddress.getLoopbackAddress()
    }
}

/** La bibliothèque de la tablette vue par l'échange : « Mes morceaux », enregistrements, imports. */
class FolderStore(private val dir: File) : ExchangeServer.Store {
    override fun list(): List<ExchangeServer.FileInfo> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && accepted(it.name) }
            .sortedByDescending { it.lastModified() }
            .map { ExchangeServer.FileInfo(it.name, it.length(), it.lastModified(), kind(it.name)) }

    override fun file(name: String): File? {
        val clean = clean(name) ?: return null
        return File(dir, clean).takeIf { it.isFile }
    }

    override fun save(name: String, data: InputStream, length: Long, replace: Boolean): String? {
        var clean = clean(name) ?: return null
        if (!accepted(clean)) return null
        var target = File(dir, clean)
        if (target.exists() && !replace) {
            val ext = extension(clean)
            val base = clean.removeSuffix(".$ext")
            var k = 2
            while (target.exists()) { clean = "$base ($k).$ext"; target = File(dir, clean); k++ }
        }
        val tmp = File(dir, ".reception-${System.nanoTime()}")
        try {
            tmp.outputStream().use { data.copyTo(it, 64 * 1024) }
            if (length >= 0 && tmp.length() != length) { tmp.delete(); return null }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return clean
    }

    companion object {
        val EXTENSIONS = setOf("mid", "midi", "kar", "gp", "gp3", "gp4", "gp5", "gpx", "xml", "musicxml", "mxl", "pdf",
            "wav", "mp3", "m4a", "ogg", "flac")

        /** « Plan.json » : la grille d'accords de Riffmaker, rangée à côté de son MIDI. */
        fun extension(name: String) = when {
            name.lowercase().endsWith(".plan.json") -> "plan.json"
            name.lowercase().endsWith(".carnet.json") -> "carnet.json"
            else -> name.substringAfterLast('.', "").lowercase()
        }

        fun accepted(name: String) = extension(name) in EXTENSIONS || extension(name) == "plan.json" || extension(name) == "carnet.json"

        fun kind(name: String) = when (extension(name)) {
            "mid", "midi", "kar" -> "midi"
            "pdf" -> "pdf"
            "plan.json" -> "plan"
            "carnet.json" -> "carnet"
            "wav", "mp3", "m4a", "ogg", "flac" -> "audio"
            "gp", "gp3", "gp4", "gp5", "gpx" -> "guitarpro"
            else -> "musicxml"
        }

        /** Seulement un nom de fichier : pas de dossier, pas de « .. », pas de caractères interdits. */
        fun clean(name: String): String? {
            val base = name.replace('\\', '/').substringAfterLast('/').trim()
            if (base.isEmpty() || base.startsWith(".")) return null
            return base.replace(Regex("[:*?\"<>|\\u0000-\\u001f]"), "_").take(150)
        }
    }
}
