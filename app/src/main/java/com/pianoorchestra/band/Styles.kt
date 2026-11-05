package com.pianoorchestra.band

/**
 * Les styles du « groupe qui suit » : batterie, basse, accompagnement et nappe, écrits en grilles
 * de doubles croches (16 pas par mesure à 4 temps, 12 pas à 3 temps).
 * Batterie : 'X' fort, 'x' normal, 'o' léger, '.' rien.
 * Basse : (pas, degré, durée) — R fondamentale, 3 tierce, 5 quinte, 8 octave, A fondamentale/quinte en alternance,
 * C note de passage (demi-ton sous la fondamentale).
 */
class Style(
    val id: String,
    val beats: Int,
    val bpm: Int,
    val swing: Boolean,
    val drums: Array<Map<Int, String>>,          // variation A puis B
    val bass: Array<List<Triple<Int, Char, Int>>>,
    val bassProgram: Int,
    val comp: Array<List<Pair<Int, Int>>>,       // (pas, durée)
    val compProgram: Int,
    val compMode: CompMode,
    val padProgram: Int,                         // -1 : pas de nappe
) {
    val steps: Int get() = beats * 4

    enum class CompMode { CHORD, ARPEGGIO, POWER }

    companion object {
        const val KICK = 36; const val SNARE = 38; const val RIM = 37; const val HAT = 42; const val PEDAL_HAT = 44
        const val OPEN_HAT = 46; const val RIDE = 51; const val CRASH = 49

        private fun eighths(n: Int = 16) = (0 until n step 2).map { it to 2 }

        val ALL = listOf(
            Style(
                "pop", 4, 104, false,
                arrayOf(
                    mapOf(KICK to "x.....x.x.......", SNARE to "....x.......x...", HAT to "x.x.x.x.x.x.x.x."),
                    mapOf(KICK to "x.....x.x.x.....", SNARE to "....x.......x..o", HAT to "x.xxx.x.x.xxx.x.", OPEN_HAT to "..............x."),
                ),
                arrayOf(
                    listOf(Triple(0, 'R', 6), Triple(6, 'R', 2), Triple(8, 'R', 4), Triple(12, '5', 2), Triple(14, 'R', 2)),
                    (0 until 16 step 2).map { Triple(it, if (it == 14) '5' else 'R', 2) },
                ),
                33,
                arrayOf(listOf(0 to 4, 6 to 2, 8 to 4, 14 to 2), listOf(0 to 2, 3 to 2, 6 to 2, 8 to 2, 11 to 2, 14 to 2)),
                0, CompMode.CHORD, 49,
            ),
            Style(
                "ballade", 4, 72, false,
                arrayOf(
                    mapOf(KICK to "x.......x.......", RIM to "....x.......x...", HAT to "o.o.o.o.o.o.o.o."),
                    mapOf(KICK to "x.....x.x.......", SNARE to "....x.......x...", RIDE to "x.x.x.x.x.x.x.x."),
                ),
                arrayOf(
                    listOf(Triple(0, 'R', 8), Triple(8, '5', 6), Triple(14, 'R', 2)),
                    listOf(Triple(0, 'R', 6), Triple(6, 'R', 2), Triple(8, '5', 4), Triple(12, '8', 4)),
                ),
                33,
                arrayOf(eighths(), eighths()),
                0, CompMode.ARPEGGIO, 48,
            ),
            Style(
                "rock", 4, 120, false,
                arrayOf(
                    mapOf(KICK to "x.....x.x.x.....", SNARE to "....X.......X...", HAT to "x.x.x.x.x.x.x.x."),
                    mapOf(KICK to "x.x...x.x.x...x.", SNARE to "....X.......X...", OPEN_HAT to "x.x.x.x.x.x.x.x."),
                ),
                arrayOf(
                    (0 until 16 step 2).map { Triple(it, 'R', 2) },
                    (0 until 16 step 2).map { Triple(it, if (it == 12) '8' else 'R', 2) },
                ),
                34,
                arrayOf(eighths(), eighths()),
                29, CompMode.POWER, -1,
            ),
            Style(
                "valse", 3, 132, false,
                arrayOf(
                    mapOf(KICK to "x...........", HAT to "....x...x..."),
                    mapOf(KICK to "x...........", SNARE to "....o...o...", RIDE to "x...x...x..."),
                ),
                arrayOf(listOf(Triple(0, 'A', 4)), listOf(Triple(0, 'A', 4), Triple(8, '3', 4))),
                32,
                arrayOf(listOf(4 to 3, 8 to 3), listOf(4 to 3, 8 to 3)),
                0, CompMode.CHORD, 48,
            ),
            Style(
                "bossa", 4, 132, false,
                arrayOf(
                    mapOf(KICK to "x.....x.x.....x.", RIM to "x..x..x...x..x..", HAT to "oooooooooooooooo"),
                    mapOf(KICK to "x.....x.x.....x.", RIM to "x..x..x...x..x..", RIDE to "x.x.x.x.x.x.x.x."),
                ),
                arrayOf(
                    listOf(Triple(0, 'R', 5), Triple(6, '5', 2), Triple(8, '5', 5), Triple(14, 'R', 2)),
                    listOf(Triple(0, 'R', 5), Triple(6, '5', 2), Triple(8, '5', 5), Triple(14, 'R', 2)),
                ),
                32,
                arrayOf(listOf(0 to 2, 3 to 2, 6 to 2, 10 to 2, 12 to 2), listOf(0 to 2, 3 to 2, 6 to 2, 8 to 2, 10 to 2, 13 to 2)),
                24, CompMode.CHORD, -1,
            ),
            Style(
                "jazz", 4, 120, true,
                arrayOf(
                    mapOf(RIDE to "x...x.x.x...x.x.", PEDAL_HAT to "....x.......x...", KICK to "o...o...o...o..."),
                    mapOf(RIDE to "x...x.x.x...x.x.", PEDAL_HAT to "....x.......x...", KICK to "o...o...o...o...", SNARE to "..........o...o."),
                ),
                arrayOf(
                    listOf(Triple(0, 'R', 4), Triple(4, '3', 4), Triple(8, '5', 4), Triple(12, 'C', 4)),
                    listOf(Triple(0, 'R', 4), Triple(4, '5', 4), Triple(8, '8', 4), Triple(12, 'C', 4)),
                ),
                32,
                arrayOf(listOf(0 to 3, 6 to 2), listOf(2 to 2, 6 to 2, 10 to 2, 14 to 2)),
                0, CompMode.CHORD, -1,
            ),
        )

        fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ALL[0]
    }
}
