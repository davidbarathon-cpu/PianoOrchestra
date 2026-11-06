package com.pianoorchestra.library

import com.pianoorchestra.player.Json

/**
 * Le carnet d'une chanson de répétition, écrit dans Riffmaker pour Bermet : la construction du morceau
 * (les parties et leurs mesures), SES accords mesure par mesure, SES indications, et la carte des tempos
 * qui permet de suivre le son du groupe (le WAV) mesure par mesure.
 */
class Carnet(
    val title: String,
    val list: String,
    val key: String,
    val tempo: Double,
    val signature: String,
    /** Mesures de baguettes avant la mesure 1 (dans le WAV). */
    val countIn: Int,
    val sections: List<Section>,
    /** Durée de chaque mesure en noires (décompte compris), dans l'ordre. */
    private val barQuarters: List<Double>,
    /** Changements de tempo : (noire depuis le début du WAV, BPM). */
    private val tempos: List<Pair<Double, Double>>,
    /** Le nom du fichier du son du groupe et de la partition, s'ils ont été envoyés. */
    val audioFile: String?,
    val scoreFile: String?,
) {
    class Section(val name: String, val type: String, val start: Int, val bars: Int, val chords: List<String>, val notes: List<String>) {
        val end: Int get() = start + bars
    }

    val totalBars: Int get() = sections.lastOrNull()?.end ?: 0

    /** Début (en noires depuis le début du son) de chaque mesure, décompte compris ; un de plus pour la fin. */
    private val barStarts: DoubleArray = DoubleArray(barQuarters.size + 1).also { a ->
        for (k in barQuarters.indices) a[k + 1] = a[k] + barQuarters[k]
    }

    /** Noires → secondes, avec les changements de tempo. */
    fun seconds(quarters: Double): Double {
        var s = 0.0
        var prevQ = 0.0
        var bpm = tempos.firstOrNull()?.second ?: tempo
        for ((q, nv) in tempos.drop(1)) {
            if (q >= quarters) break
            s += (q - prevQ) * 60.0 / bpm
            prevQ = q; bpm = nv
        }
        return s + (quarters - prevQ) * 60.0 / bpm
    }

    /** Secondes → noires. */
    fun quarters(seconds: Double): Double {
        var s = 0.0
        var prevQ = 0.0
        var bpm = tempos.firstOrNull()?.second ?: tempo
        for ((q, nv) in tempos.drop(1)) {
            val segment = (q - prevQ) * 60.0 / bpm
            if (s + segment > seconds) break
            s += segment
            prevQ = q; bpm = nv
        }
        return prevQ + (seconds - s) * bpm / 60.0
    }

    /** Où on en est dans le morceau : mesure (0 = première mesure jouée, négatif pendant le décompte) et fraction. */
    fun barAt(seconds: Double): Double {
        val q = quarters(seconds.coerceAtLeast(0.0))
        if (barQuarters.isEmpty()) return q / 4.0 - countIn
        var k = 0
        while (k < barQuarters.size && barStarts[k + 1] <= q) k++
        if (k >= barQuarters.size) {
            val last = barQuarters.last()
            return (barQuarters.size + (q - barStarts[barQuarters.size]) / last) - countIn
        }
        return k + (q - barStarts[k]) / barQuarters[k] - countIn
    }

    /** Le début (en secondes dans le son) de la mesure [bar] (0 = première mesure jouée). */
    fun secondsAtBar(bar: Int): Double {
        val k = (bar + countIn).coerceAtLeast(0)
        val q = if (k < barStarts.size) barStarts[k] else barStarts.last() + (k - barQuarters.size) * (barQuarters.lastOrNull() ?: 4.0)
        return seconds(q)
    }

    fun sectionAt(bar: Int): Section? = sections.lastOrNull { bar >= it.start }?.takeIf { bar < it.end }

    companion object {
        const val SUFFIX = ".carnet.json"

        fun parse(text: String): Carnet {
            val m = Json.parse(text) as? Map<*, *> ?: error("carnet illisible")
            require(m["format"] == "piano-orchestra-carnet") { "ce n'est pas un carnet" }
            fun num(v: Any?, d: Double) = (v as? Number)?.toDouble() ?: d
            fun strs(v: Any?) = (v as? List<*>)?.map { it?.toString() ?: "" } ?: emptyList()
            val sections = (m["sections"] as? List<*>).orEmpty().mapNotNull { x ->
                val s = x as? Map<*, *> ?: return@mapNotNull null
                val bars = num(s["mesures"], 1.0).toInt().coerceAtLeast(1)
                Section(
                    s["nom"]?.toString() ?: "",
                    s["type"]?.toString() ?: "",
                    num(s["debut"], 0.0).toInt(),
                    bars,
                    strs(s["accords"]).let { (it + List(bars) { "" }).take(bars) },
                    strs(s["notes"]).filter { it.isNotBlank() },
                )
            }
            val tempo = num(m["tempo"], 120.0)
            val carte = m["carte"] as? Map<*, *>
            val mesures = (carte?.get("mesures") as? List<*>).orEmpty().mapNotNull { (it as? List<*>)?.firstOrNull()?.let { q -> num(q, 4.0) } }
            val tempos = (carte?.get("tempos") as? List<*>).orEmpty().mapNotNull { t ->
                val l = t as? List<*> ?: return@mapNotNull null
                num(l.getOrNull(0), 0.0) to num(l.getOrNull(1), tempo)
            }.ifEmpty { listOf(0.0 to tempo) }
            val countIn = num(m["decompte"], 0.0).toInt()
            val total = sections.lastOrNull()?.end ?: 0
            val tpm = num(m["temps_par_mesure"], 4.0)
            val bars = mesures.ifEmpty { List(countIn + total) { tpm } }
            return Carnet(
                m["titre"]?.toString() ?: "", m["liste"]?.toString() ?: "", m["ton"]?.toString() ?: "", tempo,
                m["signature"]?.toString() ?: "4/4", countIn, sections, bars, tempos,
                m["son"]?.toString()?.takeIf { it.isNotBlank() }, m["partition"]?.toString()?.takeIf { it.isNotBlank() },
            )
        }
    }
}
