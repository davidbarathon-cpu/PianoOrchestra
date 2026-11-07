package com.pianoorchestra.compose

import com.pianoorchestra.band.Chord
import com.pianoorchestra.band.ChordName
import java.text.Normalizer
import kotlin.random.Random

/**
 * Le compositeur de la tablette, sans internet : il lit la demande (« ballade rock en la mineur à 80 »,
 * « вальс в ре мажоре », « quelque chose de joyeux »), puis écrit un plan : la structure, les accords de
 * chaque partie selon le style et l'humeur. La mélodie est inventée ensuite par l'arrangeur.
 */
object Composer {

    /** Ce qu'on a compris de la demande. Tout est facultatif : ce qui manque est choisi au mieux. */
    data class Request(
        val text: String,
        val style: String? = null,
        val key: Int? = null,
        val minor: Boolean? = null,
        val tempo: Int? = null,
        val mood: Mood? = null,
        val length: Length = Length.NORMAL,
        val noDrums: Boolean = false,
        val noMelody: Boolean = false,
    )

    enum class Mood { HAPPY, SAD, CALM, ENERGETIC, EPIC, ROMANTIC }
    enum class Length { SHORT, NORMAL, LONG }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private val STYLE_WORDS = listOf(
        "valse" to listOf("valse", "waltz", "вальс", "3/4", "trois temps"),
        "bossa" to listOf("bossa", "босса", "samba", "самба", "bresil", "latin", "латин"),
        "jazz" to listOf("jazz", "джаз", "swing", "свинг", "blues", "блюз"),
        "rock" to listOf("rock", "рок", "metal", "метал", "hard", "punk", "панк", "guitare", "гитар"),
        "ballade" to listOf("ballade", "ballad", "баллад", "lent", "slow", "медлен", "douce", "doux", "нежн", "romant", "романт", "film", "кино", "фильм", "cinema"),
        "pop" to listOf("pop", "поп", "variete", "эстрад", "dance", "danse", "танц"),
    )
    private val MOOD_WORDS = listOf(
        Mood.SAD to listOf("triste", "melancol", "nostalg", "sad", "груст", "печал", "тоск", "ностальг", "pluie", "дожд"),
        Mood.HAPPY to listOf("joyeu", "gai", "happy", "heureu", "soleil", "fete", "весел", "радост", "счаст", "солн", "праздн"),
        Mood.CALM to listOf("calme", "doux", "douce", "paisib", "zen", "reve", "спокой", "тих", "нежн", "мечт", "сон"),
        Mood.ENERGETIC to listOf("energ", "fort", "puissant", "rapide", "danse", "энерг", "быстр", "мощн", "сильн"),
        Mood.EPIC to listOf("epique", "epic", "film", "heroi", "grandiose", "эпич", "кино", "фильм", "героич", "величеств"),
        Mood.ROMANTIC to listOf("amour", "romant", "tendre", "любов", "романт", "нежн"),
    )

    /** Les noms de notes dans la demande : « la mineur », « ré majeur », « en Am », « ля минор », « до мажор ». */
    private val NOTE_WORDS = listOf(
        "do" to 0, "re" to 2, "mi" to 4, "fa" to 5, "sol" to 7, "la" to 9, "si" to 11,
        "до" to 0, "ре" to 2, "ми" to 4, "фа" to 5, "соль" to 7, "ля" to 9, "си" to 11,
    )

    fun read(text: String): Request {
        val t = " " + norm(text) + " "
        val style = STYLE_WORDS.firstOrNull { (_, ws) -> ws.any { norm(it) in t } }?.first
        val mood = MOOD_WORDS.firstOrNull { (_, ws) -> ws.any { norm(it) in t } }?.first
        // le tempo : un nombre de 40 à 220 (« à 80 », « 120 bpm »)
        val tempo = Regex("(\\d{2,3})\\s*(bpm|уд|temps)?").findAll(t).map { it.groupValues[1].toInt() }.firstOrNull { it in 40..220 }
        var key: Int? = null
        var minor: Boolean? = null
        // « en la mineur », « ля минор », « ре-мажор », « en ré »
        val m = Regex("(?:^|[\\s,(])(do|re|mi|fa|sol|la|si|до|ре|ми|фа|соль|ля|си)(?:[\\s-]*(diese|#|бемоль|bemol|b|диез))?(?:[\\s-]*(mineur|majeur|minor|major|минор[а-я]*|мажор[а-я]*|m\\b))?(?=[\\s,.!)]|$)")
            .findAll(t).firstOrNull { mt ->
                val word = mt.groupValues[1]
                // « la » et « si » sont aussi des mots français : on les garde s'ils suivent « en » ou sont suivis d'un mode
                val modeGiven = mt.groupValues[3].isNotEmpty()
                val before = t.substring(0, mt.range.first).trimEnd()
                modeGiven || before.endsWith(" en") || before.endsWith(" в") || word.length > 2 && word !in listOf("sol")
            }
        if (m != null) {
            key = NOTE_WORDS.first { it.first == m.groupValues[1] }.second
            val acc = m.groupValues[2]
            if (acc.startsWith("d") || acc == "#" || acc.startsWith("ди")) key = (key + 1) % 12
            else if (acc.isNotEmpty()) key = (key + 11) % 12
            val mode = m.groupValues[3]
            if (mode.isNotEmpty()) minor = mode.startsWith("min") || mode.startsWith("мин") || mode == "m"
        } else {
            // en lettres : « en Am », « in D », « en F# mineur »
            Regex("(?:en|in|в)\\s+([a-g])([#b]?)(m(?!a))?(?:\\s*(mineur|majeur|minor|major|минор[а-я]*|мажор[а-я]*))?(?=[\\s,.!)]|$)").find(t)?.let { mt ->
                key = ChordName.root(mt.groupValues[1].uppercase() + mt.groupValues[2])?.first
                minor = when {
                    mt.groupValues[3].isNotEmpty() -> true
                    mt.groupValues[4].isNotEmpty() -> mt.groupValues[4].startsWith("min") || mt.groupValues[4].startsWith("мин")
                    else -> null
                }
            }
        }
        val length = when {
            listOf("court", "petit", "short", "корот", "небольш").any { norm(it) in t } -> Length.SHORT
            listOf("long", "длин", "complet", "entier", "полн").any { norm(it) in t } -> Length.LONG
            else -> Length.NORMAL
        }
        val noDrums = listOf("sans batterie", "no drums", "без бараб", "без ударн").any { norm(it) in t }
        val noMelody = listOf("sans melodie", "no melody", "без мелод").any { norm(it) in t }
        return Request(text, style, key, minor, tempo, mood, length, noDrums, noMelody)
    }

    // ------------------------------------------------------------------ les grilles

    /** Les degrés : 1 = tonique ; « m » mineur, « 7 », « maj7 »… ; en majeur ou en mineur. */
    private val MAJOR_SCALE = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MINOR_SCALE = intArrayOf(0, 2, 3, 5, 7, 8, 10)

    private class Grid(val degrees: List<String>)

    /** Grilles de 4 mesures, par style et par mode : (couplet, refrain, pont). */
    private fun grids(style: String, minor: Boolean): Triple<List<String>, List<String>, List<String>> = when {
        style == "jazz" && !minor -> Triple(listOf("1maj7", "6m7", "2m7", "57"), listOf("2m7", "57", "1maj7", "4maj7"), listOf("3m7", "67", "2m7", "57"))
        style == "jazz" -> Triple(listOf("1m7", "4m7", "2m7b5", "57"), listOf("1m7", "6maj7", "2m7b5", "57"), listOf("4m7", "7", "3maj7", "57"))
        style == "bossa" && !minor -> Triple(listOf("1maj7", "2m7", "3m7", "4maj7"), listOf("2m7", "57", "1maj7", "6m7"), listOf("4maj7", "4m6", "3m7", "6m7"))
        style == "bossa" -> Triple(listOf("1m7", "4m7", "2m7b5", "57"), listOf("6maj7", "2m7b5", "57", "1m7"), listOf("4m7", "7", "3maj7", "6maj7"))
        style == "valse" && !minor -> Triple(listOf("1", "4", "5", "1"), listOf("1", "6m", "2m", "5"), listOf("4", "1", "2m", "5"))
        style == "valse" -> Triple(listOf("1m", "4m", "5", "1m"), listOf("6", "3", "4m", "5"), listOf("4m", "1m", "6", "5"))
        style == "rock" && !minor -> Triple(listOf("1", "7b", "4", "1"), listOf("1", "5", "6m", "4"), listOf("6m", "4", "1", "5"))
        style == "rock" -> Triple(listOf("1m", "6", "7", "1m"), listOf("1m", "6", "3", "7"), listOf("4m", "6", "7", "5"))
        style == "ballade" && !minor -> Triple(listOf("1", "5/7", "6m", "4"), listOf("4", "1/3", "2m", "5"), listOf("6m", "3m", "4", "5"))
        style == "ballade" -> Triple(listOf("1m", "6", "3", "7"), listOf("6", "7", "1m", "1m"), listOf("4m", "1m", "6", "5"))
        !minor -> Triple(listOf("1", "5", "6m", "4"), listOf("4", "5", "1", "6m"), listOf("6m", "4", "1", "5"))
        else -> Triple(listOf("1m", "6", "3", "7"), listOf("6", "7", "3", "1m"), listOf("4m", "6", "7", "5"))
    }

    /** « 6m7 » en la mineur → « F#m7 »… ; « 5/7 » → accord du 5e degré avec le 7e à la basse ; « 7b » = 7e degré bémolisé. */
    fun degreeToChord(deg: String, key: Int, minor: Boolean): String {
        val slash = deg.indexOf('/')
        val main = if (slash > 0) deg.substring(0, slash) else deg
        val bassDeg = if (slash > 0) deg.substring(slash + 1).toIntOrNull() else null
        val scale = if (minor) MINOR_SCALE else MAJOR_SCALE
        val d = main[0].digitToInt().coerceIn(1, 7)        // un seul chiffre : « 57 » = 5e degré, septième
        var rest = main.substring(1)
        var root = key + scale[d - 1]
        if (rest.startsWith("b") && !rest.startsWith("b5")) { root -= 1; rest = rest.substring(1) }
        // en mineur, le 5e degré est majeur (la sensible) quand il est écrit « 5 » ou « 57 »
        val names = ChordName.spelling(key, minor)
        val name = names[Math.floorMod(root, 12)] + rest
        return if (bassDeg != null) name + "/" + names[Math.floorMod(key + scale[bassDeg - 1], 12)] else name
    }

    /** Écrit le plan d'un morceau à partir de la demande. [seed] : une autre graine = une autre version. */
    fun compose(req: Request, seed: Long = System.nanoTime(), title: String? = null): Plan {
        val rnd = Random(seed)
        val style = req.style ?: when (req.mood) {
            Mood.SAD, Mood.CALM, Mood.ROMANTIC, Mood.EPIC -> "ballade"
            Mood.ENERGETIC -> "rock"
            else -> listOf("pop", "ballade", "pop", "bossa").random(rnd)
        }
        val minor = req.minor ?: when (req.mood) {
            Mood.SAD, Mood.EPIC -> true
            Mood.HAPPY, Mood.ENERGETIC -> false
            // une tonalité donnée sans mode (« en sol ») : majeur, comme on le dit d'habitude
            else -> if (req.key != null) false else if (style == "rock" || style == "ballade") rnd.nextInt(3) == 0 else rnd.nextInt(4) == 0
        }
        val key = req.key ?: if (minor) listOf(9, 4, 2, 7, 0).random(rnd) else listOf(0, 7, 2, 5, 9).random(rnd)
        val base = com.pianoorchestra.band.Style.byId(style).bpm
        val tempo = (req.tempo ?: when (req.mood) {
            Mood.CALM, Mood.SAD, Mood.ROMANTIC -> (base * 0.85).toInt()
            Mood.ENERGETIC -> (base * 1.12).toInt()
            else -> base + rnd.nextInt(-6, 7)
        }).coerceIn(40, 220)
        val (verseG, chorusG, bridgeG) = grids(style, minor)
        fun line(g: List<String>, bars: Int, shift: Int = 0): List<String> =
            List(bars) { degreeToChord(g[(it + shift) % g.size], key, minor) }
        val tonic = degreeToChord(if (minor) "1m" else "1", key, minor)
        val five = degreeToChord("5", key, minor)

        // la structure
        val bridge = req.length != Length.SHORT && rnd.nextInt(3) != 0
        val structure = mutableListOf<Plan.Part>()
        fun part(type: String, n: Int, bars: Int, chords: List<String>, energy: Int, pad: Boolean = false, melody: Boolean = true) {
            val label = mapOf("intro" to "Intro", "couplet" to "Couplet", "refrain" to "Refrain", "pont" to "Pont",
                "outro" to "Fin", "solo" to "Solo", "prerefrain" to "Pré-refrain", "interlude" to "Interlude")[type] ?: type
            val calmStyle = style == "ballade" || req.mood == Mood.CALM
            structure += Plan.Part(type, if (n > 0) "$label $n" else label, bars, chords, energy,
                drums = !req.noDrums && !(calmStyle && type == "intro"), bass = !(calmStyle && type == "intro" && energy == 1),
                comp = true, pad = pad, melody = melody && !req.noMelody)
        }
        val verseBars = if (req.length == Length.SHORT) 8 else 8
        part("intro", 0, 4, line(chorusG, 4), 1, melody = false)
        part("couplet", 1, verseBars, line(verseG, verseBars), 2)
        part("refrain", 1, 8, line(chorusG, 8), 3, pad = true)
        if (req.length != Length.SHORT) {
            part("couplet", 2, verseBars, line(verseG, verseBars), 2)
            part("refrain", 2, 8, line(chorusG, 8), 3, pad = true)
        }
        if (bridge) part("pont", 0, 4, line(bridgeG, 3) + listOf(five), 2, pad = true)
        if (req.length == Length.LONG) part("solo", 0, 8, line(verseG, 8), 3, melody = true)
        part("refrain", if (req.length == Length.SHORT) 2 else 3, 8, line(chorusG, 8), 3, pad = true)
        part("outro", 0, 4, line(chorusG, 3) + listOf(tonic), 1, pad = true, melody = false)

        val name = title ?: autoTitle(req, style, minor, rnd)
        return Plan(name, style, tempo, Math.floorMod(key, 12), minor, structure, "tablette", req.text, seed,
            melodyProgram = when (style) { "rock" -> 81; "jazz" -> 66; "bossa" -> 73; "valse" -> 40; else -> 73 })
    }

    private fun autoTitle(req: Request, style: String, minor: Boolean, rnd: Random): String {
        val ru = req.text.any { it in 'а'..'я' || it in 'А'..'Я' }
        val fr = listOf("Lumière d'hiver", "Le chemin du soir", "Danse des étoiles", "Promenade", "Nuit de velours", "Matin clair",
            "Le jardin secret", "Horizon", "Fleur de neige", "Petite mélodie", "Rêverie", "Vent du sud")
        val rus = listOf("Зимний свет", "Вечерняя дорога", "Танец звёзд", "Прогулка", "Бархатная ночь", "Ясное утро",
            "Тайный сад", "Горизонт", "Снежный цветок", "Мелодия", "Мечта", "Южный ветер")
        return (if (ru) rus else fr).random(rnd)
    }

    // ------------------------------------------------------------------ modifier un plan sans internet

    /** « plus lent », « plus rapide », « monte d'un ton », « en mineur », « sans batterie », « ajoute un pont »… */
    fun modify(plan: Plan, text: String, seed: Long = System.nanoTime()): Plan? {
        val t = " " + norm(text) + " "
        var p = plan
        var changed = false
        if (listOf("plus lent", "moins vite", "ralenti", "медленн", "slower").any { norm(it) in t }) { p = p.copy(tempo = (p.tempo * 0.88).toInt().coerceAtLeast(40)); changed = true }
        if (listOf("plus vite", "plus rapide", "accelere", "быстре", "faster").any { norm(it) in t }) { p = p.copy(tempo = (p.tempo * 1.12).toInt().coerceAtMost(220)); changed = true }
        Regex("(\\d{2,3})\\s*(bpm)?").findAll(t).map { it.groupValues[1].toInt() }.firstOrNull { it in 40..220 }?.let {
            if ("tempo" in t || "bpm" in t || " a $it" in t || "темп" in t) { p = p.copy(tempo = it); changed = true }
        }
        val up = listOf("monte", "plus haut", "выше", "повыс").any { norm(it) in t }
        val down = listOf("descend", "plus bas", "ниже", "пониз").any { norm(it) in t }
        if (up || down) {
            val semis = if ("demi" in t || "полутон" in t) 1 else 2
            p = transpose(p, if (up) semis else -semis); changed = true
        }
        val r = read(text)
        if (r.key != null && r.key != p.key && ("en " in t || "в " in t || "тональ" in t || "tonalite" in t)) { p = transpose(p, Math.floorMod(r.key - p.key + 6, 12) - 6); changed = true }
        if (r.style != null && r.style != p.style) {
            val again = compose(read(plan.request).copy(style = r.style, key = p.key, minor = p.minor, tempo = null), plan.seed, plan.title)
            p = again.copy(request = plan.request + " · " + text); changed = true
        }
        if (listOf("en mineur", "plus triste", "минор", "грустн").any { norm(it) in t } && !p.minor ||
            listOf("en majeur", "plus joyeux", "plus gai", "мажор", "весел").any { norm(it) in t } && p.minor) {
            val minor = !p.minor
            val newKey = if (minor) Math.floorMod(p.key + 9, 12) else Math.floorMod(p.key + 3, 12)    // la relative
            p = compose(read(plan.request).copy(style = p.style, key = newKey, minor = minor, tempo = p.tempo), plan.seed, plan.title)
            changed = true
        }
        if (listOf("sans batterie", "без бараб", "без ударн").any { norm(it) in t }) { p = p.copy(parts = p.parts.map { it.copy(drums = false) }); changed = true }
        if (listOf("avec batterie", "с барабан", "с ударн").any { norm(it) in t }) { p = p.copy(parts = p.parts.map { it.copy(drums = it.type != "intro" || p.style != "ballade") }); changed = true }
        if (listOf("sans melodie", "без мелод").any { norm(it) in t }) { p = p.copy(parts = p.parts.map { it.copy(melody = false) }); changed = true }
        if (listOf("ajoute un pont", "avec un pont", "добавь бридж", "мост").any { norm(it) in t } && p.parts.none { it.type == "pont" }) {
            val i = p.parts.indexOfLast { it.type == "refrain" }.coerceAtLeast(1)
            val (_, _, bridgeG) = grids(p.style, p.minor)
            val bridge = Plan.Part("pont", "Pont", 4, bridgeG.map { degreeToChord(it, p.key, p.minor) }, 2, pad = true)
            p = p.copy(parts = p.parts.take(i) + bridge + p.parts.drop(i)); changed = true
        }
        if (listOf("autre melodie", "nouvelle melodie", "друг мелод", "нов мелод").any { norm(it) in t }) {
            p = p.copy(seed = seed, parts = p.parts.map { it.copy(notes = null) }); changed = true
        }
        if (listOf("plus court", "короче").any { norm(it) in t }) {
            val keep = p.parts.filterIndexed { i, x -> !(x.type == "couplet" && x.name.endsWith("2")) && !(x.type == "refrain" && x.name.endsWith("2")) && x.type != "solo" }
            p = p.copy(parts = keep); changed = true
        }
        return if (changed) p.copy(request = if (p.request.contains(text)) p.request else p.request + " · " + text) else null
    }

    fun transpose(p: Plan, semis: Int): Plan = p.copy(
        key = Math.floorMod(p.key + semis, 12),
        parts = p.parts.map { part ->
            part.copy(chords = part.chords.map { ChordName.transpose(it, semis, ChordName.spelling(Math.floorMod(p.key + semis, 12), p.minor)) },
                notes = part.notes?.map { it.copy(pitch = (it.pitch + semis).coerceIn(48, 96)) })
        })
}
