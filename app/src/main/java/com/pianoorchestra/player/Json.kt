package com.pianoorchestra.player

/**
 * Un petit lecteur JSON (objets, tableaux, textes, nombres, vrai/faux/null), sans dépendance :
 * il sert à lire les plan.json de Riffmaker, aussi dans les tests sur ordinateur.
 */
object Json {
    fun parse(text: String): Any? = Reader(text).run { val v = value(); v }

    private class Reader(val s: String) {
        var i = 0

        fun value(): Any? {
            ws()
            if (i >= s.length) return null
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("JSON : caractère inattendu '$c' en $i")
            }
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws(); i++                       // ':'
                m[k] = value()
                ws()
                if (s[i] == ',') { i++; continue }
                i++                             // '}'
                return m
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                if (s[i] == ',') { i++; continue }
                i++                             // ']'
                return l
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++                                 // '"'
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
