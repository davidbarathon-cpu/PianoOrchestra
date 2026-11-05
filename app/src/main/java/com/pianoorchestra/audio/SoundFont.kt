package com.pianoorchestra.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Lecteur de SoundFont 2 (.sf2), écrit pour Piano Orchestra.
 *
 * Inspiré du lecteur de Riffmaker (soundfont.py) : presets, instruments, zones (tessiture et force),
 * échantillons en boucle, accord, enveloppe de volume, atténuation, panoramique, classe exclusive.
 * Les filtres et les modulateurs ne sont pas gérés : ce n'est pas nécessaire pour un bon rendu.
 */
class SoundFont(data: ByteArray) {

    /** Une zone prête à jouer : un échantillon, sur une plage de notes et de forces. */
    class Zone(
        val keyLo: Int, val keyHi: Int, val velLo: Int, val velHi: Int,
        val start: Int, val end: Int, val loopStart: Int, val loopEnd: Int,
        val sampleRate: Int, val rootKey: Int, val scaleTuning: Float, val tuneCents: Float,
        val loopMode: Int,
        val attack: Float, val hold: Float, val decay: Float, val sustain: Float, val release: Float,
        val gain: Float, val pan: Float, val exclusiveClass: Int,
    )

    private class Preset(val name: String, val program: Int, val bank: Int, val bag: Int)

    /** Tous les échantillons de la banque (16 bits). */
    val samples: ShortArray

    private val presets = ArrayList<Preset>()
    private val pbag: IntArray
    private val pgenOp: IntArray
    private val pgenAmt: IntArray
    private val instBag = ArrayList<Int>()
    private val ibag: IntArray
    private val igenOp: IntArray
    private val igenAmt: IntArray
    private val sStart: IntArray
    private val sEnd: IntArray
    private val sLoopStart: IntArray
    private val sLoopEnd: IntArray
    private val sRate: IntArray
    private val sPitch: IntArray
    private val sCorrection: IntArray
    private val presetIndex = HashMap<Int, Int>()
    private val zoneCache = HashMap<Int, List<Zone>>()

    init {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(data.size > 12 && tag(data, 0) == "RIFF" && tag(data, 8) == "sfbk") { "Pas une SoundFont" }
        val chunks = HashMap<String, IntArray>()       // "sdta/smpl" -> [position, taille]
        var i = 12
        while (i + 8 <= data.size) {
            val id = tag(data, i)
            val size = buf.getInt(i + 4)
            if (id == "LIST") {
                val type = tag(data, i + 8)
                var j = i + 12
                val endList = min(data.size, i + 8 + size)
                while (j + 8 <= endList) {
                    val id2 = tag(data, j)
                    val size2 = buf.getInt(j + 4)
                    chunks["$type/$id2"] = intArrayOf(j + 8, size2)
                    j += 8 + size2 + (size2 and 1)
                }
            }
            i += 8 + size + (size and 1)
        }

        val smpl = chunks["sdta/smpl"] ?: error("smpl manquant")
        samples = ShortArray(smpl[1] / 2)
        ByteBuffer.wrap(data, smpl[0], smpl[1]).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        fun chunk(name: String) = chunks["pdta/$name"] ?: error("$name manquant")

        val phdr = chunk("phdr")
        for (k in 0 until phdr[1] / 38) {
            val p = phdr[0] + k * 38
            presets.add(Preset(cString(data, p, 20), u16(buf, p + 20), u16(buf, p + 22), u16(buf, p + 24)))
        }
        pbag = readBags(buf, chunk("pbag"))
        val pg = readGens(buf, chunk("pgen")); pgenOp = pg.first; pgenAmt = pg.second
        val inst = chunk("inst")
        for (k in 0 until inst[1] / 22) instBag.add(u16(buf, inst[0] + k * 22 + 20))
        ibag = readBags(buf, chunk("ibag"))
        val ig = readGens(buf, chunk("igen")); igenOp = ig.first; igenAmt = ig.second

        val shdr = chunk("shdr")
        val n = shdr[1] / 46
        sStart = IntArray(n); sEnd = IntArray(n); sLoopStart = IntArray(n); sLoopEnd = IntArray(n)
        sRate = IntArray(n); sPitch = IntArray(n); sCorrection = IntArray(n)
        for (k in 0 until n) {
            val p = shdr[0] + k * 46
            sStart[k] = buf.getInt(p + 20); sEnd[k] = buf.getInt(p + 24)
            sLoopStart[k] = buf.getInt(p + 28); sLoopEnd[k] = buf.getInt(p + 32)
            sRate[k] = buf.getInt(p + 36); sPitch[k] = data[p + 40].toInt() and 0xFF
            sCorrection[k] = data[p + 41].toInt()
        }
        for (k in 0 until presets.size - 1) {                 // le dernier preset est le terminateur « EOP »
            presetIndex[key(presets[k].bank, presets[k].program)] = k
        }
    }

    /** Les noms des presets (pour le débogage). */
    fun presetName(bank: Int, program: Int): String? = presetIndex[key(bank, program)]?.let { presets[it].name }

    /** Les zones d'un preset (banque 128 = batterie). Gardées en cache. */
    @Synchronized
    fun zones(bank: Int, program: Int): List<Zone> {
        val k = key(bank, program)
        zoneCache[k]?.let { return it }
        val idx = presetIndex[k]
            ?: (if (bank == 128) presetIndex[key(128, 0)] else presetIndex[key(0, program)])
            ?: presetIndex.values.firstOrNull()
            ?: return emptyList()
        val result = buildZones(idx)
        zoneCache[k] = result
        return result
    }

    private fun buildZones(presetIdx: Int): List<Zone> {
        val out = ArrayList<Zone>()
        val b0 = presets[presetIdx].bag
        val b1 = presets[presetIdx + 1].bag
        var presetGlobal: HashMap<Int, Int>? = null
        for (b in b0 until b1) {
            val pz = gens(pgenOp, pgenAmt, pbag, b)
            if (!pz.containsKey(GEN_INSTRUMENT)) {
                if (b == b0) presetGlobal = pz
                continue
            }
            val pzm = HashMap<Int, Int>()
            presetGlobal?.let { pzm.putAll(it) }
            pzm.putAll(pz)
            val inst = pzm[GEN_INSTRUMENT]!!
            if (inst + 1 >= instBag.size) continue
            val ib0 = instBag[inst]
            val ib1 = instBag[inst + 1]
            var instGlobal: HashMap<Int, Int>? = null
            for (ib in ib0 until ib1) {
                val iz = gens(igenOp, igenAmt, ibag, ib)
                if (!iz.containsKey(GEN_SAMPLE)) {
                    if (ib == ib0) instGlobal = iz
                    continue
                }
                val g = HashMap<Int, Int>()
                instGlobal?.let { g.putAll(it) }
                g.putAll(iz)
                // le preset s'ajoute aux valeurs de l'instrument ; les plages se croisent
                for ((op, v) in pzm) {
                    when (op) {
                        GEN_INSTRUMENT, GEN_SAMPLE, GEN_MODES, GEN_ROOT, GEN_EXCLUSIVE -> {}
                        GEN_KEYRANGE, GEN_VELRANGE -> {
                            val a = g[op] ?: 0x7F00
                            val lo = max(a and 0xFF, v and 0xFF)
                            val hi = min((a shr 8) and 0xFF, (v shr 8) and 0xFF)
                            g[op] = lo or (hi shl 8)
                        }
                        in 0..4, 12, 45, 50 -> {}            // décalages d'adresses : niveau instrument seulement
                        else -> g[op] = (g[op] ?: defaultOf(op)) + v
                    }
                }
                makeZone(g)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun makeZone(g: Map<Int, Int>): Zone? {
        val s = g[GEN_SAMPLE] ?: return null
        if (s < 0 || s >= sStart.size) return null
        val kr = g[GEN_KEYRANGE] ?: 0x7F00
        val vr = g[GEN_VELRANGE] ?: 0x7F00
        val last = samples.size - 2
        val start = (sStart[s] + (g[0] ?: 0) + 32768 * (g[4] ?: 0)).coerceIn(0, last)
        val end = (sEnd[s] + (g[1] ?: 0) + 32768 * (g[12] ?: 0)).coerceIn(start + 1, last)
        val loopStart = (sLoopStart[s] + (g[2] ?: 0) + 32768 * (g[45] ?: 0)).coerceIn(start, end)
        val loopEnd = (sLoopEnd[s] + (g[3] ?: 0) + 32768 * (g[50] ?: 0)).coerceIn(loopStart, end)
        val rootOverride = g[GEN_ROOT] ?: -1
        val root = if (rootOverride in 0..127) rootOverride else sPitch[s].let { if (it > 127) 60 else it }
        val tune = (g[GEN_COARSE] ?: 0) * 100f + (g[GEN_FINE] ?: 0) + sCorrection[s]
        val sustainCb = (g[GEN_SUSTAIN] ?: 0).coerceIn(0, 1440)
        val attenCb = (g[GEN_ATTEN] ?: 0).coerceIn(0, 1440)
        return Zone(
            keyLo = kr and 0xFF, keyHi = (kr shr 8) and 0xFF,
            velLo = vr and 0xFF, velHi = (vr shr 8) and 0xFF,
            start = start, end = end, loopStart = loopStart, loopEnd = loopEnd,
            sampleRate = if (sRate[s] > 0) sRate[s] else 44100,
            rootKey = root,
            scaleTuning = (g[GEN_SCALE] ?: 100) / 100f,
            tuneCents = tune,
            loopMode = (g[GEN_MODES] ?: 0) and 3,
            attack = timecents(g[GEN_ATTACK], 0.001f, 10f),
            hold = timecents(g[GEN_HOLD], 0f, 10f),
            decay = timecents(g[GEN_DECAY], 0.001f, 20f),
            sustain = 10.0.pow(-sustainCb / 200.0).toFloat(),
            release = timecents(g[GEN_RELEASE], 0.01f, 5f),
            // facteur 0,4 : la convention des lecteurs courants (FluidSynth) pour l'atténuation
            gain = 10.0.pow(-0.4 * attenCb / 200.0).toFloat(),
            pan = ((g[GEN_PAN] ?: 0).coerceIn(-500, 500)) / 500f,
            exclusiveClass = g[GEN_EXCLUSIVE] ?: 0,
        )
    }

    private fun timecents(v: Int?, minSec: Float, maxSec: Float): Float {
        val tc = v ?: -12000
        val sec = if (tc <= -12000) 0f else 2.0.pow(tc / 1200.0).toFloat()
        return sec.coerceIn(minSec, maxSec)
    }

    private fun defaultOf(op: Int): Int = when (op) {
        GEN_ATTACK, GEN_HOLD, GEN_DECAY, GEN_RELEASE, 33 -> -12000
        GEN_SCALE -> 100
        else -> 0
    }

    private fun gens(ops: IntArray, amts: IntArray, bags: IntArray, bag: Int): HashMap<Int, Int> {
        val res = HashMap<Int, Int>()
        if (bag + 1 >= bags.size) return res
        for (k in bags[bag] until min(bags[bag + 1], ops.size)) res[ops[k]] = amts[k]
        return res
    }

    companion object {
        const val GEN_PAN = 17
        const val GEN_ATTACK = 34
        const val GEN_HOLD = 35
        const val GEN_DECAY = 36
        const val GEN_SUSTAIN = 37
        const val GEN_RELEASE = 38
        const val GEN_INSTRUMENT = 41
        const val GEN_KEYRANGE = 43
        const val GEN_VELRANGE = 44
        const val GEN_ATTEN = 48
        const val GEN_COARSE = 51
        const val GEN_FINE = 52
        const val GEN_SAMPLE = 53
        const val GEN_MODES = 54
        const val GEN_SCALE = 56
        const val GEN_EXCLUSIVE = 57
        const val GEN_ROOT = 58

        private fun key(bank: Int, program: Int) = bank * 1000 + program
        private fun tag(d: ByteArray, p: Int) = String(d, p, 4, Charsets.US_ASCII)
        private fun u16(b: ByteBuffer, p: Int) = b.getShort(p).toInt() and 0xFFFF
        private fun cString(d: ByteArray, p: Int, len: Int): String {
            var n = 0
            while (n < len && d[p + n] != 0.toByte()) n++
            return String(d, p, n, Charsets.ISO_8859_1)
        }

        private fun readBags(b: ByteBuffer, c: IntArray): IntArray =
            IntArray(c[1] / 4) { k -> u16(b, c[0] + k * 4) }

        /** Opérateurs et valeurs ; les plages (43, 44) sont gardées en « bas | haut << 8 ». */
        private fun readGens(b: ByteBuffer, c: IntArray): Pair<IntArray, IntArray> {
            val n = c[1] / 4
            val ops = IntArray(n)
            val amts = IntArray(n)
            for (k in 0 until n) {
                val p = c[0] + k * 4
                ops[k] = u16(b, p)
                amts[k] = if (ops[k] == GEN_KEYRANGE || ops[k] == GEN_VELRANGE) u16(b, p + 2)
                          else b.getShort(p + 2).toInt()
            }
            return ops to amts
        }
    }
}
