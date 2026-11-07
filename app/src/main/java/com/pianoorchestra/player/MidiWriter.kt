package com.pianoorchestra.player

import java.io.ByteArrayOutputStream

/**
 * Écrit un fichier MIDI standard (format 1) : ce que Bermet a joué, seul ou avec l'orchestre.
 * Le fichier se relit dans l'app, s'ouvre dans Riffmaker et dans n'importe quel logiciel de musique.
 */
object MidiWriter {

    /** Un événement à [time] secondes. */
    class Event(val time: Double, val status: Int, val d1: Int, val d2: Int)

    /** Une piste : nom, programme (son), canal, événements. */
    class Track(val name: String, val channel: Int, val program: Int, val events: List<Event>)

    private const val TPQ = 480

    /** [bpm] et [beatsPerBar] : pour que les mesures tombent juste (compositions avec le groupe). */
    fun write(tracks: List<Track>, title: String, bpm: Double = 120.0, beatsPerBar: Int = 4,
              markers: List<Pair<Double, String>> = emptyList()): ByteArray {
        val BPM = bpm.coerceIn(20.0, 300.0)
        val usPerBeat = (60_000_000.0 / BPM).toInt()
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        be32(out, 6); be16(out, 1); be16(out, tracks.size + 1); be16(out, TPQ)

        // piste de conduite : titre et tempo
        val conductor = ByteArrayOutputStream()
        meta(conductor, 0, 0x03, title.toByteArray(Charsets.UTF_8))
        meta(conductor, 0, 0x51, byteArrayOf((usPerBeat shr 16).toByte(), (usPerBeat shr 8).toByte(), usPerBeat.toByte()))
        meta(conductor, 0, 0x58, byteArrayOf(beatsPerBar.coerceIn(1, 16).toByte(), 2, 24, 8))
        // les parties du morceau (Intro, Couplet 1…) : des marqueurs, que l'app et Riffmaker relisent
        var lastM = 0L
        for ((t, name) in markers.sortedBy { it.first }) {
            val tick = (t.coerceAtLeast(0.0) * TPQ * BPM / 60.0).toLong()
            meta(conductor, tick - lastM, 0x06, name.toByteArray(Charsets.UTF_8))
            lastM = tick
        }
        meta(conductor, 0, 0x2F, ByteArray(0))
        chunk(out, conductor.toByteArray())

        for (t in tracks) {
            val tr = ByteArrayOutputStream()
            meta(tr, 0, 0x03, t.name.toByteArray(Charsets.UTF_8))
            varLen(tr, 0); tr.write(0xC0 or t.channel); tr.write(t.program and 0x7F)
            var last = 0L
            for (e in t.events.sortedBy { it.time }) {
                val tick = (e.time.coerceAtLeast(0.0) * TPQ * BPM / 60.0).toLong()
                varLen(tr, tick - last)
                last = tick
                val kind = e.status and 0xF0
                tr.write(kind or t.channel)
                tr.write(e.d1 and 0x7F)
                if (kind != 0xC0 && kind != 0xD0) tr.write(e.d2 and 0x7F)
            }
            meta(tr, 0, 0x2F, ByteArray(0))
            chunk(out, tr.toByteArray())
        }
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, data: ByteArray) {
        out.write("MTrk".toByteArray()); be32(out, data.size); out.write(data)
    }

    private fun meta(o: ByteArrayOutputStream, delta: Long, type: Int, data: ByteArray) {
        varLen(o, delta); o.write(0xFF); o.write(type); varLen(o, data.size.toLong()); o.write(data)
    }

    private fun varLen(o: ByteArrayOutputStream, v: Long) {
        var value = v.coerceAtLeast(0)
        var buffer = value and 0x7F
        while (true) {
            value = value shr 7
            if (value == 0L) break
            buffer = (buffer shl 8) or ((value and 0x7F) or 0x80)
        }
        while (true) {
            o.write((buffer and 0xFF).toInt())
            if (buffer and 0x80 != 0L) buffer = buffer shr 8 else break
        }
    }

    private fun be32(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 24); o.write((v shr 16) and 0xFF); o.write((v shr 8) and 0xFF); o.write(v and 0xFF) }
    private fun be16(o: ByteArrayOutputStream, v: Int) { o.write((v shr 8) and 0xFF); o.write(v and 0xFF) }
}
