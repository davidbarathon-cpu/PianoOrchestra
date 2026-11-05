package com.pianoorchestra.player

import java.nio.ByteBuffer

/** Une partie du morceau : une piste MIDI sur un canal (batterie, basse, mélodie…). */
class Part(
    val index: Int,
    val name: String,
    val channel: Int,
    val program: Int,
    val noteCount: Int,
) {
    val isDrums: Boolean get() = channel == 9
}

/** Un événement à jouer : à [time] secondes, pour la partie [part]. */
class SongEvent(val time: Double, val part: Int, val status: Int, val d1: Int, val d2: Int)

class Song(
    val parts: List<Part>,
    /** Triés par temps. */
    val events: List<SongEvent>,
    val duration: Double,
    /** (temps en secondes, durée d'un temps en secondes), triés. */
    private val tempos: List<Pair<Double, Double>>,
    val beatsPerBar: Int,
    val name: String,
) {
    /** Le tempo au début, en battements par minute. */
    val bpm: Int get() = (60.0 / beatSecondsAt(0.0)).toInt()

    fun beatSecondsAt(time: Double): Double {
        var b = tempos.firstOrNull()?.second ?: 0.5
        for ((t, s) in tempos) { if (t <= time) b = s else break }
        return b
    }

    /** L'index du premier événement à [time] ou après. */
    fun indexAt(time: Double): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (events[mid].time < time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Lit un fichier MIDI standard (format 0 ou 1). Lève une exception si ce n'en est pas un. */
        fun parse(data: ByteArray, name: String): Song {
            val b = ByteBuffer.wrap(data)
            require(data.size >= 14 && String(data, 0, 4, Charsets.US_ASCII) == "MThd") { "Pas un fichier MIDI" }
            val headerLen = b.getInt(4)
            val ntrks = b.getShort(10).toInt() and 0xFFFF
            val division = b.getShort(12).toInt() and 0xFFFF
            require(division and 0x8000 == 0) { "Format de temps SMPTE non géré" }
            val tpq = division.coerceAtLeast(1)

            class Raw(val tick: Long, val track: Int, val status: Int, val d1: Int, val d2: Int, val order: Int)
            val raws = ArrayList<Raw>()
            val tempoTicks = ArrayList<Pair<Long, Int>>()     // (tick, microsecondes par noire)
            val trackNames = HashMap<Int, String>()
            var beatsPerBar = 4
            var order = 0

            var p = 8 + headerLen
            var track = 0
            while (track < ntrks && p + 8 <= data.size) {
                val id = String(data, p, 4, Charsets.US_ASCII)
                val len = b.getInt(p + 4)
                val start = p + 8
                val end = minOf(data.size, start + len)
                if (id == "MTrk") {
                    var q = start
                    var tick = 0L
                    var running = 0
                    fun varLen(): Long {
                        var v = 0L
                        while (q < end) {
                            val c = data[q++].toInt() and 0xFF
                            v = (v shl 7) or (c and 0x7F).toLong()
                            if (c and 0x80 == 0) break
                        }
                        return v
                    }
                    while (q < end) {
                        tick += varLen()
                        if (q >= end) break
                        var st = data[q].toInt() and 0xFF
                        if (st and 0x80 != 0) q++ else st = running
                        when {
                            st == 0xFF -> {
                                if (q >= end) break
                                val type = data[q++].toInt() and 0xFF
                                val l = varLen().toInt()
                                val ms = q
                                q += l
                                if (q > end) break
                                when (type) {
                                    0x51 -> if (l >= 3) tempoTicks.add(tick to (((data[ms].toInt() and 0xFF) shl 16) or
                                        ((data[ms + 1].toInt() and 0xFF) shl 8) or (data[ms + 2].toInt() and 0xFF)))
                                    0x03 -> if (!trackNames.containsKey(track)) trackNames[track] =
                                        String(data, ms, l, Charsets.UTF_8).trim()
                                    0x58 -> if (l >= 1 && tick == 0L) beatsPerBar = (data[ms].toInt() and 0xFF).coerceIn(1, 12)
                                    0x2F -> q = end
                                }
                            }
                            st == 0xF0 || st == 0xF7 -> {
                                val l = varLen().toInt()
                                q += l
                            }
                            st and 0x80 != 0 -> {
                                running = st
                                val n = if ((st and 0xF0) == 0xC0 || (st and 0xF0) == 0xD0) 1 else 2
                                if (q + n > end) break
                                val a = data[q].toInt() and 0x7F
                                val c = if (n == 2) data[q + 1].toInt() and 0x7F else 0
                                q += n
                                raws.add(Raw(tick, track, st, a, c, order++))
                            }
                            else -> q++                        // octet inattendu : on avance
                        }
                    }
                    track++
                }
                p = start + len
            }

            // tempo : ticks -> secondes
            tempoTicks.sortBy { it.first }
            if (tempoTicks.isEmpty() || tempoTicks[0].first > 0) tempoTicks.add(0, 0L to 500000)
            val tempoSec = ArrayList<Pair<Double, Double>>()
            val segStartSec = DoubleArray(tempoTicks.size)
            for (k in tempoTicks.indices) {
                if (k > 0) {
                    val dt = tempoTicks[k].first - tempoTicks[k - 1].first
                    segStartSec[k] = segStartSec[k - 1] + dt * tempoTicks[k - 1].second / 1e6 / tpq
                }
                tempoSec.add(segStartSec[k] to tempoTicks[k].second / 1e6)
            }
            fun seconds(tick: Long): Double {
                var k = 0
                while (k + 1 < tempoTicks.size && tempoTicks[k + 1].first <= tick) k++
                return segStartSec[k] + (tick - tempoTicks[k].first) * tempoTicks[k].second / 1e6 / tpq
            }

            // parties : (piste, canal)
            val partKey = HashMap<Int, Int>()
            val parts = ArrayList<Part>()
            val programs = HashMap<Int, Int>()
            val counts = HashMap<Int, Int>()
            raws.sortWith(compareBy<Raw> { it.tick }.thenBy { it.order })
            for (r in raws) {
                val ch = r.status and 0x0F
                val key = r.track * 16 + ch
                if ((r.status and 0xF0) == 0xC0 && !programs.containsKey(key)) programs[key] = r.d1
                if ((r.status and 0xF0) == 0x90 && r.d2 > 0) counts[key] = (counts[key] ?: 0) + 1
            }
            val events = ArrayList<SongEvent>(raws.size)
            var duration = 0.0
            for (r in raws) {
                val ch = r.status and 0x0F
                val key = r.track * 16 + ch
                val pi = partKey.getOrPut(key) {
                    parts.add(Part(parts.size, trackNames[r.track] ?: "", ch, programs[key] ?: 0, counts[key] ?: 0))
                    parts.size - 1
                }
                val t = seconds(r.tick)
                events.add(SongEvent(t, pi, r.status, r.d1, r.d2))
                if (t > duration) duration = t
            }
            return Song(parts, events, duration, tempoSec, beatsPerBar, name)
        }
    }
}
