package com.pianoorchestra

import com.pianoorchestra.compose.Arranger
import com.pianoorchestra.compose.Composer
import com.pianoorchestra.compose.OnlineComposer
import com.pianoorchestra.compose.Plan
import com.pianoorchestra.exchange.ExchangeServer
import com.pianoorchestra.exchange.FolderStore
import com.pianoorchestra.player.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class ComposerTest {

    @Test
    fun readsFrenchAndRussianRequests() {
        Composer.read("une valse joyeuse en ré majeur").let {
            assertEquals("valse", it.style); assertEquals(2, it.key); assertEquals(false, it.minor)
        }
        Composer.read("une ballade triste en la mineur, lente").let {
            assertEquals("ballade", it.style); assertEquals(9, it.key); assertEquals(true, it.minor)
        }
        Composer.read("бодрый рок в ми миноре, темп 140").let {
            assertEquals("rock", it.style); assertEquals(4, it.key); assertEquals(true, it.minor); assertEquals(140, it.tempo)
        }
        Composer.read("une bossa sans batterie").let { assertEquals("bossa", it.style); assertTrue(it.noDrums) }
    }

    @Test
    fun understandsEveryExampleChip() {
        val res = java.io.File("src/main/res").takeIf { it.exists() } ?: java.io.File("app/src/main/res").takeIf { it.exists() } ?: java.io.File("main/res")
        for (lang in listOf("values", "values-ru")) {
            val xml = java.io.File(res, "$lang/strings.xml").readText()
            for (n in 1..6) {
                val ex = Regex("<string name=\"ex_$n\">([^<]+)</string>").find(xml)!!.groupValues[1]
                val r = Composer.read(ex)
                assertNotNull("$lang ex_$n « $ex » : style", r.style)
                if (n != 3) assertNotNull("$lang ex_$n « $ex » : tonalité", r.key)
            }
        }
    }

    @Test
    fun composesRendersAndReadsBack() {
        for (q in listOf("une valse joyeuse en ré", "un rock énergique", "джаз в фа мажоре", "une berceuse douce")) {
            val plan = Composer.compose(Composer.read(q), seed = 42)
            assertTrue(plan.parts.size >= 4)
            val r = Arranger.render(plan)
            assertTrue("$q : durée ${r.seconds}", r.seconds in 30.0..400.0)
            val song = Song.parse(r.midi, plan.title)
            song.attachPlan(r.planJson)
            assertTrue("$q : pistes ${song.parts.size}", song.parts.size >= 4)
            // le plan relu donne le même morceau
            val again = Plan.fromJson(r.planJson)
            assertEquals(plan.title, again.title)
            assertEquals(plan.tempo, again.tempo)
            assertEquals(plan.parts.sumOf { it.bars }, again.parts.sumOf { it.bars })
        }
    }

    @Test
    fun sameSeedSameSong() {
        val a = Arranger.render(Composer.compose(Composer.read("une ballade"), seed = 7))
        val b = Arranger.render(Composer.compose(Composer.read("une ballade"), seed = 7))
        assertTrue(a.midi.contentEquals(b.midi))
    }

    @Test
    fun modifiesAPlan() {
        val p = Composer.compose(Composer.read("une pop en do majeur, tempo 100"), seed = 1)
        assertTrue(Composer.modify(p, "plus lent")!!.tempo < p.tempo)
        assertEquals(2, Composer.modify(p, "monte d'un ton")!!.key)
        assertTrue(Composer.modify(p, "en mineur")!!.minor)
        assertTrue(Composer.modify(p, "sans batterie")!!.parts.none { it.drums })
        assertNull(Composer.modify(p, "xyzzy"))
    }

    @Test
    fun readsAFreeAiAnswer() {
        // le plan tel que Mistral ou Groq le renvoient : un JSON dans le message (ici entouré de ``` comme il arrive)
        val inner = """
            {"titre":"Neige sur Bichkek","style":"ballade","tempo":72,"tonique":9,"mineur":true,
             "sections":[
              {"type":"intro","nom":"Intro","mesures":4,"accords":["Am","F","C","G"],"energie":1},
              {"type":"couplet","nom":"Couplet","mesures":8,"accords":["Am","F","C","G","Am","F","E7","Am"],
               "notes":[[0,0,1,69],[0,1,1,71],[0,2,2,72],[1,0,4,69]]},
              {"type":"refrain","nom":"Refrain","mesures":8,"accords":["F","G","C","Am"],"energie":3}
             ]}
        """.trimIndent()
        val content = ("```json\n" + inner + "\n```").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val answer = """{"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}]}"""
        assertEquals(OnlineComposer.Provider.GROQ, OnlineComposer.providerFor("gsk_abc123"))
        assertEquals(OnlineComposer.Provider.MISTRAL, OnlineComposer.providerFor("AbCdEf0123456789AbCdEf0123456789"))
        val plan = OnlineComposer.parseResponse(answer)
        assertEquals("Neige sur Bichkek", plan.title)
        assertEquals(3, plan.parts.size)
        assertEquals(4, plan.parts[1].notes!!.size)
        assertEquals(8, plan.parts[2].chords.size)    // les accords sont répétés sur toutes les mesures
        val r = Arranger.render(plan)
        assertTrue(r.seconds > 20)
    }

    @Test
    fun riffmakerSendsTheKey() {
        val dir = Files.createTempDirectory("po-cle").toFile()
        var got: Pair<String, String>? = null
        val server = ExchangeServer(FolderStore(dir), { "4821" }, { mapOf("app" to "Piano Orchestra") }, { ByteArray(0) }, {},
            { n, v -> if (n == "ai-key") { got = n to v; true } else false })
        val port = server.start(18775)
        try {
            fun put(path: String, code: String?): Int {
                val c = URL("http://127.0.0.1:$port$path").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                c.requestMethod = "PUT"; c.doOutput = true
                if (code != null) c.setRequestProperty("X-PO-Code", code)
                c.outputStream.use { it.write("sk-ant-test-123".toByteArray()) }
                return c.responseCode.also { c.disconnect() }
            }
            assertEquals(401, put("/api/settings/ai-key", null))
            assertNull(got)
            assertEquals(200, put("/api/settings/ai-key", "4821"))
            assertNotNull(got)
            assertEquals("sk-ant-test-123", got!!.second)
            assertTrue(put("/api/settings/autre", "4821") >= 400)
        } finally {
            server.stop()
        }
    }
}
