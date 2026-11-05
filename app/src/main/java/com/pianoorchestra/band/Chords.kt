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
