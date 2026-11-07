package com.pianoorchestra.compose

import com.pianoorchestra.player.Json

/**
 * Le plan d'un morceau composé sur la tablette : style, tonalité, tempo, et les parties (intro, couplets,
 * refrains, pont, fin) avec leurs accords mesure par mesure et, s'il y en a une, la mélodie.
 * Il est écrit par le moteur de la tablette ou par l'IA gratuite en ligne (Mistral ou Groq), puis joué par l'arrangeur.
 * Il est gardé à côté du MIDI (Titre.plan.json) : l'app y lit la grille, Riffmaker aussi.
 */
data class Plan(
    val title: String,
    val style: String,
    val tempo: Int,
    /** 0 = do … 11 = si */
    val key: Int,
    val minor: Boolean,
    val parts: List<Part>,
    /** Qui l'a écrit : « tablette », « Mistral » ou « Groq ». */
    val source: String = "tablette",
    /** La demande de départ, et ce qui a été demandé ensuite. */
    val request: String = "",
    val seed: Long = 0,
    /** Instrument de la mélodie (programme General MIDI). */
    val melodyProgram: Int = 73,
) {
    data class Part(
        val type: String,
        val name: String,
        val bars: Int,
        /** Un nom par mesure ; deux accords dans une mesure : « C G ». */
        val chords: List<String>,
        /** 1 calme, 2 normal, 3 fort. */
        val energy: Int = 2,
        val drums: Boolean = true,
        val bass: Boolean = true,
        val comp: Boolean = true,
        val pad: Boolean = false,
        val melody: Boolean = true,
        /** La mélodie écrite note par note (IA en ligne), sinon la tablette l'invente. */
        val notes: List<Note>? = null,
    )

    /** Une note de mélodie : mesure (dans la partie, depuis 0), temps (depuis 0, peut être 1.5), durée en temps, hauteur MIDI. */
    data class Note(val bar: Int, val beat: Double, val length: Double, val pitch: Int)

    val totalBars: Int get() = parts.sumOf { it.bars }

    fun keyName(lang: String): String {
        val fr = arrayOf("do", "do♯", "ré", "mi♭", "mi", "fa", "fa♯", "sol", "la♭", "la", "si♭", "si")
        val ru = arrayOf("до", "до-диез", "ре", "ми-бемоль", "ми", "фа", "фа-диез", "соль", "ля-бемоль", "ля", "си-бемоль", "си")
        return if (lang == "ru") "${ru[key]} ${if (minor) "минор" else "мажор"}" else "${fr[key]} ${if (minor) "mineur" else "majeur"}"
    }

    /** Le plan.json : la grille au format de Riffmaker (sections, mesures, accords) et tout le plan pour le modifier. */
    fun toJson(): String = buildString {
        append("{\"titre\":").append(q(title)).append(",\"tempo\":").append(tempo).append(",\"style\":").append(q(style))
        append(",\"tonique\":").append(key).append(",\"mineur\":").append(minor).append(",\"source\":").append(q(source))
        append(",\"demande\":").append(q(request)).append(",\"graine\":").append(seed).append(",\"son_melodie\":").append(melodyProgram)
        append(",\"sections\":[")
        parts.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"type\":").append(q(p.type)).append(",\"nom\":").append(q(p.name)).append(",\"mesures\":").append(p.bars)
            append(",\"accords\":[").append(p.chords.joinToString(",") { q(it) }).append(']')
            append(",\"energie\":").append(p.energy).append(",\"batterie\":").append(p.drums).append(",\"basse\":").append(p.bass)
            append(",\"accompagnement\":").append(p.comp).append(",\"nappe\":").append(p.pad).append(",\"melodie\":").append(p.melody)
            p.notes?.let { ns ->
                append(",\"notes\":[").append(ns.joinToString(",") { "[${it.bar},${it.beat},${it.length},${it.pitch}]" }).append(']')
            }
            append('}')
        }
        append("]}")
    }

    companion object {
        val TYPES = listOf("intro", "couplet", "prerefrain", "refrain", "pont", "solo", "interlude", "outro")

        private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

        /** Lire un plan (le nôtre, ou celui de l'IA en ligne : mêmes clés). Les valeurs folles sont ramenées au raisonnable. */
        fun fromJson(text: String): Plan = fromMap(Json.parse(text) as? Map<*, *> ?: error("plan illisible"))

        fun fromMap(m: Map<*, *>): Plan {
            fun num(v: Any?, d: Double) = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull() ?: d
            fun bool(v: Any?, d: Boolean) = v as? Boolean ?: d
            val style = (m["style"] as? String)?.lowercase()?.let { s -> STYLE_IDS.firstOrNull { s.startsWith(it) } } ?: "pop"
            val parts = (m["sections"] as? List<*>).orEmpty().mapNotNull { x ->
                val s = x as? Map<*, *> ?: return@mapNotNull null
                val bars = num(s["mesures"], 8.0).toInt().coerceIn(1, 32)
                val chords = (s["accords"] as? List<*>).orEmpty().map { it?.toString()?.trim() ?: "" }
                val full = if (chords.isEmpty()) List(bars) { "" } else List(bars) { chords[it % chords.size] }
                val type = (s["type"] as? String)?.lowercase()?.let { t -> TYPES.firstOrNull { t.startsWith(it.take(4)) } } ?: "couplet"
                val notes = (s["notes"] as? List<*>)?.mapNotNull { n ->
                    val l = n as? List<*> ?: return@mapNotNull null
                    if (l.size < 4) return@mapNotNull null
                    Note(num(l[0], 0.0).toInt().coerceIn(0, bars - 1), num(l[1], 0.0).coerceIn(0.0, 11.5),
                        num(l[2], 1.0).coerceIn(0.125, 8.0), num(l[3], 72.0).toInt().coerceIn(48, 96))
                }?.takeIf { it.isNotEmpty() }
                Part(type, (s["nom"] as? String)?.takeIf { it.isNotBlank() } ?: type.replaceFirstChar { it.uppercase() }, bars, full,
                    num(s["energie"], 2.0).toInt().coerceIn(1, 3), bool(s["batterie"], true), bool(s["basse"], true),
                    bool(s["accompagnement"], true), bool(s["nappe"], false), bool(s["melodie"], true), notes)
            }.take(24)
            require(parts.isNotEmpty()) { "plan sans parties" }
            return Plan(
                (m["titre"] as? String)?.takeIf { it.isNotBlank() }?.take(80) ?: "Ma composition",
                style, num(m["tempo"], 100.0).toInt().coerceIn(40, 220),
                Math.floorMod(num(m["tonique"], 0.0).toInt(), 12), bool(m["mineur"], false), parts,
                (m["source"] as? String) ?: "tablette", (m["demande"] as? String) ?: "",
                num(m["graine"], 0.0).toLong(), num(m["son_melodie"], 73.0).toInt().coerceIn(0, 127),
            )
        }

        val STYLE_IDS = listOf("pop", "ballade", "rock", "valse", "bossa", "jazz")
    }
}
