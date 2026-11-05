package com.pianoorchestra

import com.pianoorchestra.exchange.ExchangeServer
import com.pianoorchestra.exchange.FolderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files

/** L'échange tablette ↔ ordinateur : ce que Riffmaker fait, de bout en bout, sur la machine de test. */
class ExchangeTest {

    private fun call(url: String, method: String = "GET", code: String? = "4821", body: ByteArray? = null): Pair<Int, ByteArray> {
        val c = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        c.requestMethod = method
        if (code != null) c.setRequestProperty("X-PO-Code", code)
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val status = c.responseCode
        val bytes = (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        return status to bytes
    }

    @Test
    fun riffmakerSendsAndReceives() {
        val dir = Files.createTempDirectory("po-echange").toFile()
        val events = mutableListOf<ExchangeServer.Event>()
        val server = ExchangeServer(FolderStore(dir), { "4821" },
            { mapOf("app" to "Piano Orchestra", "name" to "Tablette de Bermet", "version" to "test") },
            { "<html>page</html>".toByteArray() }, { synchronized(events) { events += it } })
        val port = server.start(18765)
        val base = "http://127.0.0.1:$port"
        try {
            // la page s'ouvre sans code, l'API non
            assertEquals(200, call("$base/", code = null).first)
            assertEquals(401, call("$base/api/files", code = null).first)
            assertEquals(401, call("$base/api/files", code = "0000").first)

            // Riffmaker envoie un morceau : MIDI + grille d'accords
            val midi = File("src/main/assets/songs/01_ballade.mid").readBytes()
            val name = URLEncoder.encode("Ballade pour Bermet.mid", "UTF-8").replace("+", "%20")
            val (s1, r1) = call("$base/api/files/$name", "PUT", body = midi)
            assertEquals(200, s1)
            assertTrue(String(r1).contains("Ballade pour Bermet.mid"))
            assertEquals(200, call("$base/api/files/Ballade%20pour%20Bermet.plan.json", "PUT", body = "{}".toByteArray()).first)
            assertTrue(File(dir, "Ballade pour Bermet.mid").readBytes().contentEquals(midi))

            // sans « remplacer », un deuxième envoi garde les deux
            assertTrue(String(call("$base/api/files/$name?replace=0", "PUT", body = midi).second).contains("(2)"))
            // un type inconnu et un chemin piégé sont refusés ou nettoyés
            assertEquals(415, call("$base/api/files/virus.exe", "PUT", body = ByteArray(10)).first)
            call("$base/api/files/..%2F..%2Fevil.mid", "PUT", body = midi)
            assertTrue(File(dir, "evil.mid").exists())
            assertTrue(!File(dir.parentFile.parentFile, "evil.mid").exists())

            // la liste, puis le téléchargement vers l'ordinateur
            val list = String(call("$base/api/files").second)
            assertTrue(list.contains("\"kind\":\"plan\"") && list.contains("\"kind\":\"midi\""))
            val (s2, back) = call("$base/api/files/$name")
            assertEquals(200, s2)
            assertTrue(back.contentEquals(midi))
            assertEquals(404, call("$base/api/files/absent.mid").first)

            // la sauvegarde complète est un zip
            val zip = call("$base/api/backup.zip").second
            assertTrue(zip.size > midi.size && zip[0] == 'P'.code.toByte() && zip[1] == 'K'.code.toByte())

            // le phare répond sur le réseau
            DatagramSocket().use { s ->
                s.soTimeout = 3000
                val ask = ExchangeServer.DISCOVERY_ASK.toByteArray()
                s.send(DatagramPacket(ask, ask.size, InetAddress.getLoopbackAddress(), ExchangeServer.DISCOVERY_PORT))
                val buf = ByteArray(1024)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val answer = String(p.data, 0, p.length)
                assertTrue(answer, answer.startsWith(ExchangeServer.DISCOVERY_ANSWER) && answer.contains("\"port\":$port"))
            }
            synchronized(events) {
                assertTrue(events.count { it.kind == ExchangeServer.Kind.RECEIVED } >= 4)
                assertTrue(events.any { it.kind == ExchangeServer.Kind.REFUSED })
            }
            println("Échange : ${dir.list()?.sorted()}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("evil.mid", FolderStore.clean("../../evil.mid"))
        assertEquals("a_b.mid", FolderStore.clean("a:b.mid"))
        assertNull(FolderStore.clean(".cache"))
        assertEquals("plan.json", FolderStore.extension("X.plan.json"))
        assertTrue(FolderStore.accepted("Ma chanson.gp5") && !FolderStore.accepted("photo.jpg") && !FolderStore.accepted("autre.json"))
    }
}
