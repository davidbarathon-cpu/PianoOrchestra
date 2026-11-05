package com.pianoorchestra.ui

/**
 * Les mots de la musique dans la langue de Bermet.
 * Accords : en français do ré mi (« Rém », « Sol7 ») ; en russe, la notation par lettres (« Dm », « G7 »),
 * celle qu'utilisent les musiciens russes pour les accords.
 */
object MusicText {

    private val FR_ROOTS = mapOf('C' to "Do", 'D' to "Ré", 'E' to "Mi", 'F' to "Fa", 'G' to "Sol", 'A' to "La", 'B' to "Si")

    fun chord(name: String, lang: String): String {
        if (name.isBlank()) return name
        val slash = name.indexOf('/')
        val main = if (slash > 0) name.substring(0, slash) else name
        val bass = if (slash > 0) name.substring(slash + 1) else null
        val out = root(main, lang) + (bass?.let { "/" + root(it, lang) } ?: "")
        return out
    }

    private fun root(s: String, lang: String): String {
        if (s.isEmpty()) return s
        val letter = s[0].uppercaseChar()
        var rest = s.substring(1)
        var acc = ""
        if (rest.startsWith("#")) { acc = "♯"; rest = rest.substring(1) }
        else if (rest.startsWith("b")) { acc = "♭"; rest = rest.substring(1) }
        val r = if (lang == "fr") FR_ROOTS[letter] ?: letter.toString() else letter.toString()
        return r + acc + rest
    }

    private val RU_SECTIONS = listOf(
        "intro" to "Вступление", "couplet" to "Куплет", "pré-refrain" to "Предприпев", "refrain" to "Припев",
        "pont" to "Бридж", "solo" to "Соло", "interlude" to "Проигрыш", "outro" to "Концовка", "fin" to "Конец",
        "thème" to "Тема", "break" to "Брейк",
    )

    /** « Couplet 1 » → « Куплет 1 » en russe ; inchangé en français. */
    fun section(name: String, lang: String): String {
        if (lang != "ru") return name
        val low = name.lowercase()
        for ((fr, ru) in RU_SECTIONS) if (low.startsWith(fr)) return ru + name.substring(fr.length)
        return name
    }

    fun time(s: Double): String {
        val t = s.toInt().coerceAtLeast(0)
        return "%d:%02d".format(t / 60, t % 60)
    }
}
