package com.pianoorchestra.compose

import com.pianoorchestra.player.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * L'IA en ligne, gratuite : Mistral (français, hébergé en Europe) ou Groq, avec une clé gratuite créée une fois.
 * Elle comprend n'importe quelle demande (en français ou en russe) et écrit le plan complet — structure, accords
 * riches, et la mélodie note par note. La tablette joue ensuite le morceau elle-même.
 * Sans clé ou sans internet, le compositeur de la tablette prend le relais.
 */
object OnlineComposer {

    class Failure(message: String) : Exception(message)

    /** Un service gratuit : son adresse et ses modèles (le meilleur, puis le rapide / de secours). */
    enum class Provider(val label: String, val url: String, val best: String, val fast: String, val maxTokens: Int) {
        MISTRAL("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-large-latest", "mistral-small-latest", 12000),
        GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b", "llama-3.3-70b-versatile", 6000),   // l'offre gratuite de Groq compte les jetons par minute
    }

    /** Le service se reconnaît à la clé : celles de Groq commencent par « gsk_ ». */
    fun providerFor(key: String): Provider = if (key.trim().startsWith("gsk_")) Provider.GROQ else Provider.MISTRAL

    private val SYSTEM = """
Tu es le compositeur de Piano Orchestra, l'application de piano de Bermet (pianiste ; elle parle français et russe).
Elle décrit un morceau ; tu écris son plan complet, que la tablette jouera avec un petit groupe (batterie, basse,
piano ou guitare d'accompagnement, nappe de cordes, mélodie).
Réponds UNIQUEMENT par un objet JSON, sans texte autour, de cette forme :
{"titre":"…","style":"ballade","tempo":80,"tonique":9,"mineur":true,"son_melodie":73,
 "sections":[{"type":"intro","nom":"Intro","mesures":4,"accords":["Am","F","C","G"],"energie":1,
   "batterie":false,"basse":true,"accompagnement":true,"nappe":true,"melodie":false},
  {"type":"couplet","nom":"Couplet 1","mesures":8,"accords":["Am","F","C","G","Am","F","E7","Am"],"energie":2,
   "notes":[[0,0,1,69],[0,1,1,72],[0,2,2,76],[1,0,3,74]]}, …]}

Règles :
- style : un des six styles de la tablette — pop, ballade, rock, valse (3 temps), bossa, jazz (swing). Choisis le plus proche.
- tempo en BPM (40 à 220), tonique 0 = do … 11 = si, mineur true/false.
- sections dans l'ordre joué : type parmi intro, couplet, prerefrain, refrain, pont, solo, interlude, outro ; nom affiché
  (« Intro », « Couplet 1 », « Refrain 2 »… en français, ou en russe si la demande est en russe) ; mesures 2 à 16 ;
  le morceau complet fait 32 à 80 mesures, avec une intro et une fin.
- accords : un par mesure, en notation anglaise (C, Am, F#m7, Bbmaj7, G/B, Dsus4) ; deux dans une mesure : « C G ».
  Des grilles musicales et chantantes, qui conviennent au style ; le refrain doit être différent du couplet ; la fin
  se pose sur la tonique.
- energie : 1 calme, 2 normal, 3 fort ; batterie/basse/accompagnement/nappe/melodie (true/false) : qui joue dans la partie.
- notes (mélodie) : pour les parties qui ont une mélodie, la liste des notes [mesure, temps, durée, hauteur] :
  mesure de 0 à mesures-1 dans la partie, temps depuis 0 (0, 0.5, 1, 1.5…), durée en temps, hauteur MIDI 60 à 84.
  Une vraie mélodie : chantable, des phrases de 2 et 4 mesures, des notes de l'accord sur les temps forts, des
  respirations, un refrain plus haut et plus fort que le couplet, une note longue en fin de phrase.
  Un couplet ou un refrain qui revient peut omettre « notes » : la mélodie du premier est rejouée.
- titre : court, poétique, dans la langue de la demande.
- son_melodie : programme General MIDI de l'instrument de la mélodie (73 flûte, 0 piano, 40 violon, 65 saxophone, 81 lead).
""".trim()

    /** Un nouveau morceau d'après la demande. */
    fun compose(request: String, key: String, fast: Boolean): Plan =
        call("Demande de Bermet : « $request »", key, fast).let { (p, who) -> p.copy(request = request, source = who) }

    /** Modifier le morceau en cours (« plus triste », « un refrain plus long », « change la mélodie du couplet »…). */
    fun modify(plan: Plan, request: String, key: String, fast: Boolean): Plan =
        call("Voici le morceau actuel (plan JSON) :\n${plan.toJson()}\n\nBermet demande de le modifier : « $request ». " +
            "Renvoie le plan complet modifié (garde ce qu'elle n'a pas demandé de changer, y compris la mélodie).", key, fast)
            .let { (p, who) -> p.copy(request = plan.request + " · " + request, source = who, seed = plan.seed) }

    /** Le meilleur modèle d'abord ; s'il est saturé ou inconnu, le rapide. Renvoie le plan et qui l'a écrit. */
    private fun call(user: String, key: String, fast: Boolean): Pair<Plan, String> {
        val p = providerFor(key)
        val models = if (fast) listOf(p.fast) else listOf(p.best, p.fast)
        var last: Failure? = null
        for (m in models) {
            try {
                return request(p, m, user, key) to p.label
            } catch (e: Failure) {
                last = e
                if (e.message?.startsWith("clé") == true || e.message == "pas de connexion") throw e
            }
        }
        throw last ?: Failure("pas de réponse")
    }

    private fun request(p: Provider, model: String, user: String, key: String): Plan {
        val body = "{\"model\":${q(model)},\"temperature\":0.9,\"max_tokens\":${p.maxTokens}," +
            "\"response_format\":{\"type\":\"json_object\"}," +
            "\"messages\":[{\"role\":\"system\",\"content\":${q(SYSTEM)}},{\"role\":\"user\",\"content\":${q(user)}}]}"
        val c = URL(p.url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 120000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer " + key.trim())
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                throw Failure(when (code) {
                    401 -> "clé refusée par ${p.label}"
                    403 -> "accès refusé par ${p.label}"
                    404 -> "modèle inconnu ($model)"
                    413 -> "demande trop longue pour l'offre gratuite"
                    429 -> "limite gratuite atteinte pour l'instant, réessaie dans une minute"
                    500, 502, 503, 529 -> "${p.label} est saturé, réessaie dans un instant"
                    else -> "erreur $code"
                })
            }
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            return parseResponse(text)
        } catch (e: Failure) {
            throw e
        } catch (e: java.io.IOException) {
            throw Failure("pas de connexion")
        } finally {
            c.disconnect()
        }
    }

    /** La réponse (format « chat completions ») → le plan : le JSON du message, même entouré de texte ou de ```. */
    fun parseResponse(text: String): Plan {
        val m = Json.parse(text) as? Map<*, *> ?: throw Failure("réponse illisible")
        val choice = (m["choices"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: throw Failure("réponse vide")
        val content = (choice["message"] as? Map<*, *>)?.get("content")
        val txt = when (content) {
            is String -> content
            is List<*> -> content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
            else -> ""
        }
        val start = txt.indexOf('{')
        val end = txt.lastIndexOf('}')
        if (start >= 0 && end > start) return runCatching { Plan.fromJson(txt.substring(start, end + 1)) }.getOrElse { throw Failure("plan incomplet") }
        throw Failure("pas de plan dans la réponse")
    }

    private fun q(s: String) = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append(String.format("\\u%04x", ch.code))
            else -> append(ch)
        }
        append('"')
    }
}
