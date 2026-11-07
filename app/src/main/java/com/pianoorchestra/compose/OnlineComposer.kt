package com.pianoorchestra.compose

import com.pianoorchestra.player.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Claude en ligne : il comprend n'importe quelle demande (en français ou en russe) et écrit le plan complet —
 * structure, accords riches, et la mélodie note par note. La tablette joue ensuite le morceau elle-même.
 * Sans clé ou sans internet, le compositeur de la tablette prend le relais.
 */
object OnlineComposer {

    class Failure(message: String) : Exception(message)

    const val URL_API = "https://api.anthropic.com/v1/messages"
    val MODELS = listOf("claude-sonnet-5-5", "claude-haiku-5-5")

    private val SYSTEM = """
Tu es le compositeur de Piano Orchestra, l'application de piano de Bermet (pianiste ; elle parle français et russe).
Elle décrit un morceau ; tu écris son plan complet, que la tablette jouera avec un petit groupe (batterie, basse,
piano ou guitare d'accompagnement, nappe de cordes, mélodie). Réponds uniquement en appelant l'outil « ecrire_morceau ».

Règles :
- style : un des six styles de la tablette — pop, ballade, rock, valse (3 temps), bossa, jazz (swing). Choisis le plus proche.
- tempo en BPM (40 à 220), tonique 0 = do … 11 = si, mineur vrai/faux.
- sections dans l'ordre joué : type parmi intro, couplet, prerefrain, refrain, pont, solo, interlude, outro ; nom affiché
  (« Intro », « Couplet 1 », « Refrain 2 »… en français, ou en russe si la demande est en russe) ; mesures 2 à 16 ;
  le morceau complet fait 32 à 80 mesures, avec une intro et une fin.
- accords : un par mesure, en notation anglaise (C, Am, F#m7, Bbmaj7, G/B, Dsus4) ; deux dans une mesure : « C G ».
  Des grilles musicales et chantantes, qui conviennent au style ; le refrain doit être différent du couplet ; la fin
  se pose sur la tonique.
- energie : 1 calme, 2 normal, 3 fort ; batterie/basse/accompagnement/nappe/melodie : qui joue dans cette partie.
- notes (mélodie) : pour les parties qui ont une mélodie, la liste des notes [mesure, temps, durée, hauteur] :
  mesure de 0 à mesures-1 dans la partie, temps depuis 0 (0, 0.5, 1, 1.5…), durée en temps, hauteur MIDI 60 à 84.
  Une vraie mélodie : chantable, des phrases de 2 et 4 mesures, des notes de l'accord sur les temps forts, des
  respirations, un refrain plus haut et plus fort que le couplet, une note longue en fin de phrase.
  Un couplet ou un refrain qui revient peut omettre « notes » : la mélodie du premier est rejouée.
- titre : court, poétique, dans la langue de la demande.
- son_melodie : programme General MIDI de l'instrument de la mélodie (73 flûte, 0 piano, 40 violon, 65 saxophone, 81 lead).
""".trim()

    private val SCHEMA = """
{"type":"object","required":["titre","style","tempo","tonique","mineur","sections"],"properties":{
 "titre":{"type":"string"},"style":{"type":"string","enum":["pop","ballade","rock","valse","bossa","jazz"]},
 "tempo":{"type":"integer"},"tonique":{"type":"integer"},"mineur":{"type":"boolean"},"son_melodie":{"type":"integer"},
 "sections":{"type":"array","items":{"type":"object","required":["type","nom","mesures","accords"],"properties":{
  "type":{"type":"string","enum":["intro","couplet","prerefrain","refrain","pont","solo","interlude","outro"]},
  "nom":{"type":"string"},"mesures":{"type":"integer"},"accords":{"type":"array","items":{"type":"string"}},
  "energie":{"type":"integer"},"batterie":{"type":"boolean"},"basse":{"type":"boolean"},"accompagnement":{"type":"boolean"},
  "nappe":{"type":"boolean"},"melodie":{"type":"boolean"},
  "notes":{"type":"array","items":{"type":"array","items":{"type":"number"}}}}}}}}
""".trim()

    /** Un nouveau morceau d'après la demande. */
    fun compose(request: String, key: String, model: String): Plan =
        call("Demande de Bermet : « $request »", key, model).copy(request = request, source = "Claude")

    /** Modifier le morceau en cours (« plus triste », « un refrain plus long », « change la mélodie du couplet »…). */
    fun modify(plan: Plan, request: String, key: String, model: String): Plan =
        call("Voici le morceau actuel (plan JSON) :\n${plan.toJson()}\n\nBermet demande de le modifier : « $request ». " +
            "Renvoie le plan complet modifié (garde ce qu'elle n'a pas demandé de changer, y compris la mélodie).", key, model)
            .copy(request = plan.request + " · " + request, source = "Claude", seed = plan.seed)

    private fun call(user: String, key: String, model: String): Plan {
        val body = "{\"model\":${q(model)},\"max_tokens\":12000,\"temperature\":0.9,\"system\":${q(SYSTEM)}," +
            "\"messages\":[{\"role\":\"user\",\"content\":${q(user)}}]," +
            "\"tools\":[{\"name\":\"ecrire_morceau\",\"description\":\"Le plan complet du morceau.\",\"input_schema\":$SCHEMA}]," +
            "\"tool_choice\":{\"type\":\"tool\",\"name\":\"ecrire_morceau\"}}"
        val c = URL(URL_API).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 120000
        c.doOutput = true
        c.setRequestProperty("x-api-key", key.trim())
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                throw Failure(when (code) {
                    401 -> "clé refusée"
                    403 -> "accès refusé"
                    404 -> "modèle inconnu ($model)"
                    429 -> "trop de demandes, réessaie dans un instant"
                    529, 503 -> "service saturé, réessaie dans un instant"
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

    /** La réponse de l'API → le plan (l'appel d'outil, ou à défaut un JSON dans le texte). */
    fun parseResponse(text: String): Plan {
        val m = Json.parse(text) as? Map<*, *> ?: throw Failure("réponse illisible")
        val content = m["content"] as? List<*> ?: throw Failure("réponse vide")
        val tool = content.mapNotNull { it as? Map<*, *> }.firstOrNull { it["type"] == "tool_use" }?.get("input") as? Map<*, *>
        if (tool != null) return runCatching { Plan.fromMap(tool) }.getOrElse { throw Failure("plan incomplet") }
        val txt = content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
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
