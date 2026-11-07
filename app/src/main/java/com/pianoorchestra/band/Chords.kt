package com.pianoorchestra.band

/** Un accord reconnu : sa fondamentale (0 = do) et ses intervalles. */
class Chord(val root: Int, val intervals: IntArray, val suffix: String) {
    /** Nom en notation anglaise (Dm, G7, Bbmaj7…), traduit à l'affichage. */
    val name: String get() = NAMES[root] + suffix
    val isMinor: Boolean get() = 3 in intervals && 4 !in intervals

    override fun equals(other: Any?) = other is Chord && other.root == root && other.suffix == suffix
    override fun hashCode() = root * 31 + suffix.hashCode()

    companion object {
        val NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    }
}

/**
 * Reconnaît l'accord joué, comme un clavier arrangeur : accords complets ou incomplets,
 * renversements, et une seule note = accord majeur de cette note.
 */
object ChordDetector {

    private class Template(val suffix: String, val intervals: IntArray)

    // du plus courant au plus rare : en cas d'égalité, le premier gagne
    private val TEMPLATES = listOf(
        Template("", intArrayOf(0, 4, 7)),
        Template("m", intArrayOf(0, 3, 7)),
        Template("7", intArrayOf(0, 4, 7, 10)),
        Template("m7", intArrayOf(0, 3, 7, 10)),
        Template("maj7", intArrayOf(0, 4, 7, 11)),
        Template("6", intArrayOf(0, 4, 7, 9)),
        Template("m6", intArrayOf(0, 3, 7, 9)),
        Template("sus4", intArrayOf(0, 5, 7)),
        Template("sus2", intArrayOf(0, 2, 7)),
        Template("dim", intArrayOf(0, 3, 6)),
        Template("dim7", intArrayOf(0, 3, 6, 9)),
        Template("m7b5", intArrayOf(0, 3, 6, 10)),
        Template("aug", intArrayOf(0, 4, 8)),
        Template("add9", intArrayOf(0, 2, 4, 7)),
        Template("madd9", intArrayOf(0, 2, 3, 7)),
        Template("7sus4", intArrayOf(0, 5, 7, 10)),
        Template("9", intArrayOf(0, 2, 4, 7, 10)),
        Template("maj9", intArrayOf(0, 2, 4, 7, 11)),
        Template("m9", intArrayOf(0, 2, 3, 7, 10)),
        // sans quinte
        Template("7", intArrayOf(0, 4, 10)),
        Template("m7", intArrayOf(0, 3, 10)),
        Template("maj7", intArrayOf(0, 4, 11)),
        Template("5", intArrayOf(0, 7)),
    )

    /** [notes] : les notes MIDI tenues. Renvoie null si rien n'est tenu. */
    fun detect(notes: Collection<Int>): Chord? {
        if (notes.isEmpty()) return null
        val lowest = notes.min()
        val pcs = notes.map { it % 12 }.toSortedSet()
        if (pcs.size == 1) return chord(lowest % 12, TEMPLATES[0])
        if (pcs.size == 2) {
            val a = pcs.first(); val b = pcs.last()
            val d = (b - a + 12) % 12
            // deux notes : tierce ou quinte au-dessus de la plus grave, sinon majeur de la plus grave
            val root = lowest % 12
            val i = (setOf(a, b) - root).firstOrNull()?.let { (it - root + 12) % 12 } ?: 0
            return when (i) {
                3 -> chord(root, TEMPLATES[1])
                4 -> chord(root, TEMPLATES[0])
                7 -> chord(root, TEMPLATES.last())
                else -> if (d == 5 || d == 7) chord(if (d == 7) a else b, TEMPLATES.last()) else chord(root, TEMPLATES[0])
            }
        }
        // essai de chaque note comme fondamentale ; la plus grave est préférée à égalité
        var best: Chord? = null
        var bestScore = Int.MIN_VALUE
        val roots = listOf(lowest % 12) + pcs.filter { it != lowest % 12 }
        for (r in roots) {
            val iv = pcs.map { (it - r + 12) % 12 }.toSet()
            for ((ti, t) in TEMPLATES.withIndex()) {
                val ts = t.intervals.toSet()
                val base = when {
                    iv == ts -> 1000 - ti
                    ts.containsAll(iv) && iv.contains(0) -> 500 - ti - (ts.size - iv.size) * 50
                    iv.containsAll(ts) && ts.size >= 3 -> 300 - ti - (iv.size - ts.size) * 60
                    else -> continue
                }
                val score = base - (if (r == lowest % 12) 0 else 5)
                if (score > bestScore) { bestScore = score; best = chord(r, t) }
            }
        }
        return best ?: chord(lowest % 12, TEMPLATES[0])
    }

    private fun chord(root: Int, t: Template) = Chord(root, t.intervals, t.suffix)
}

/** Lire un nom d'accord écrit (« Am7 », « F#m », « Bb », « C/E », « Sol7 »…) : fondamentale, intervalles, basse. */
object ChordName {
    private val SUFFIXES: List<Pair<String, IntArray>> = listOf(
        "maj9" to intArrayOf(0, 4, 7, 11, 14), "maj7" to intArrayOf(0, 4, 7, 11), "m7b5" to intArrayOf(0, 3, 6, 10),
        "madd9" to intArrayOf(0, 3, 7, 14), "add9" to intArrayOf(0, 4, 7, 14), "7sus4" to intArrayOf(0, 5, 7, 10),
        "sus4" to intArrayOf(0, 5, 7), "sus2" to intArrayOf(0, 2, 7), "dim7" to intArrayOf(0, 3, 6, 9),
        "dim" to intArrayOf(0, 3, 6), "aug" to intArrayOf(0, 4, 8), "m9" to intArrayOf(0, 3, 7, 10, 14),
        "m7" to intArrayOf(0, 3, 7, 10), "m6" to intArrayOf(0, 3, 7, 9), "mM7" to intArrayOf(0, 3, 7, 11),
        "m" to intArrayOf(0, 3, 7), "9" to intArrayOf(0, 4, 7, 10, 14), "7" to intArrayOf(0, 4, 7, 10),
        "6" to intArrayOf(0, 4, 7, 9), "5" to intArrayOf(0, 7), "" to intArrayOf(0, 4, 7),
    )
    private val LETTERS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11, 'H' to 11)
    private val SOLFEGE = listOf("do" to 0, "ré" to 2, "re" to 2, "mi" to 4, "fa" to 5, "sol" to 7, "la" to 9, "si" to 11)

    /** La fondamentale d'un nom (lettre ou do-ré-mi) et ce qui reste après. */
    fun root(s: String): Pair<Int, String>? {
        if (s.isEmpty()) return null
        var pc: Int
        var rest: String
        val low = s.lowercase()
        val sol = SOLFEGE.firstOrNull { low.startsWith(it.first) }
        if (sol != null && (s.length == sol.first.length || !s[sol.first.length].isLetter() || s[sol.first.length] == 'm')) {
            pc = sol.second; rest = s.substring(sol.first.length)
        } else {
            pc = LETTERS[s[0].uppercaseChar()] ?: return null
            rest = s.substring(1)
        }
        when {
            rest.startsWith("#") || rest.startsWith("♯") -> { pc += 1; rest = rest.substring(1) }
            rest.startsWith("b") || rest.startsWith("♭") -> { pc -= 1; rest = rest.substring(1) }
        }
        return Math.floorMod(pc, 12) to rest
    }

    /** Renvoie (accord, basse) ou null si le nom ne se lit pas. */
    fun parse(name: String): Pair<Chord, Int?>? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val slash = n.indexOf('/')
        val main = if (slash > 0) n.substring(0, slash) else n
        val bass = if (slash > 0) root(n.substring(slash + 1))?.first else null
        val (pc, rest0) = root(main) ?: return null
        val rest = rest0.replace("min", "m").replace("maj", "maj").replace("M7", "maj7").replace("°", "dim").replace("+", "aug")
        val suffix = SUFFIXES.firstOrNull { rest == it.first } ?: SUFFIXES.firstOrNull { rest.startsWith(it.first) && it.first.isNotEmpty() }
            ?: SUFFIXES.last()
        return Chord(pc, suffix.second.map { it % 12 }.distinct().toIntArray(), suffix.first) to bass
    }

    private val SHARPS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLATS = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** Les noms de notes de la tonalité : dièses en sol, ré, la, mi, si ; bémols en fa, si♭, mi♭, la♭, ré♭, sol♭. */
    fun spelling(key: Int, minor: Boolean): Array<String> {
        val major = Math.floorMod(if (minor) key + 3 else key, 12)
        return when (major) {
            7, 2, 9, 4, 11 -> SHARPS
            5, 10, 3, 8, 1, 6 -> FLATS
            else -> Chord.NAMES
        }
    }

    /** Transposer un nom d'accord de [semis] demi-tons (C/E +2 → D/F#). */
    fun transpose(name: String, semis: Int, names: Array<String> = Chord.NAMES): String {
        if (semis == 0 || name.isBlank()) return name
        return name.split(' ').joinToString(" ") { one ->
            val slash = one.indexOf('/')
            fun t(part: String): String {
                val (pc, rest) = root(part) ?: return part
                return names[Math.floorMod(pc + semis, 12)] + rest
            }
            if (slash > 0) t(one.substring(0, slash)) + "/" + t(one.substring(slash + 1)) else t(one)
        }
    }
}
