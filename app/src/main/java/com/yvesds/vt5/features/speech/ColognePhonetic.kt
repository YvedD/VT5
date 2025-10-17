package com.yvesds.vt5.features.speech

/**
 * Implementatie van Keulen Phonetisch algoritme, aangepast voor Nederlandse woorden.
 * Zet tekst om in een fonetische code, waardoor woorden die gelijk klinken dezelfde code krijgen.
 */
class ColognePhonetic {
    companion object {
        /**
         * Zet een woord om naar een fonetische code volgens het Keulen Phonetisch algoritme
         * met aanpassingen voor het Nederlands.
         */
        fun encode(input: String): String {
            if (input.isEmpty()) return ""

            val text = input.lowercase()
                .replace(Regex("[^a-zäöüß\\s]"), "")
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss")
                .replace("ij", "y") // Nederlands
                .replace("ie", "i") // Nederlands
                .replace("ee", "e") // Nederlands
                .replace("eu", "u") // Nederlands
                .replace("oo", "o") // Nederlands
                .replace("uu", "u") // Nederlands
                .replace("ui", "u") // Nederlands
                .replace("oe", "u") // Nederlands

            val buffer = StringBuilder()
            val len = text.length

            // Gebruik '0' als initiële waarde in plaats van '/'
            var prevCode = '0'
            for (i in 0 until len) {
                val c = text[i]
                val nextChar = if (i + 1 < len) text[i + 1] else ' '

                val code = when (c) {
                    'a', 'e', 'i', 'o', 'u', 'y' -> '0'
                    'b' -> '1'
                    'p' -> '1'
                    'd', 't' -> '2'
                    'f', 'v', 'w' -> '3'
                    'g', 'k', 'q' -> when {
                        nextChar == 'x' -> '8'
                        else -> '4'
                    }
                    'c' -> when {
                        nextChar in "aou" -> '4'
                        else -> '8'
                    }
                    'x' -> when {
                        prevCode == '4' -> '8'
                        else -> '4' // Dit was "48" wat een string is, niet een char
                    }
                    'l' -> '5'
                    'm', 'n' -> '6'
                    'r' -> '7'
                    's', 'z' -> '8'
                    'h' -> if (prevCode == '0') '0' else prevCode
                    else -> '0'
                }

                if (code != '0' && code != prevCode) {
                    buffer.append(code)
                }

                prevCode = code
            }

            return buffer.toString()
        }

        /**
         * Berekent de fonetische gelijkenis tussen twee strings
         * (0.0 = totaal verschillend, 1.0 = identiek)
         */
        fun similarity(s1: String, s2: String): Double {
            val p1 = encode(s1)
            val p2 = encode(s2)

            if (p1.isEmpty() && p2.isEmpty()) return 1.0
            if (p1.isEmpty() || p2.isEmpty()) return 0.0

            // Lengteverschil penaliseert de score
            val lenDiff = kotlin.math.abs(p1.length - p2.length)
            val lenPenalty = (lenDiff * 0.2).coerceAtMost(0.3)

            // Bereken de fonetische overeenkomst
            val minLen = kotlin.math.min(p1.length, p2.length)
            var matches = 0
            for (i in 0 until minLen) {
                if (p1[i] == p2[i]) matches++
            }

            val baseScore = matches.toDouble() / kotlin.math.max(p1.length, p2.length)

            // Eerste letter match is belangrijk
            val firstLetterMatch = p1.firstOrNull() == p2.firstOrNull()
            val firstLetterBonus = if (firstLetterMatch) 0.1 else 0.0

            return (baseScore - lenPenalty + firstLetterBonus).coerceIn(0.0, 1.0)
        }
    }
}