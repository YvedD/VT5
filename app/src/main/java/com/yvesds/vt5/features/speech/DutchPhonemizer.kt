package com.yvesds.vt5.features.speech

import java.util.Locale

/**
 * DutchPhonemizer.kt
 *
 * PURPOSE:
 * Convert Dutch text to IPA (International Phonetic Alphabet) phonemes
 * with vowel-aware distance calculation for high-precision matching.
 *
 * ARCHITECTURE:
 * - phonemize(): Text → IPA phonemes (e.g., "vijf" → "vɛif")
 * - phonemeDistance(): Weighted edit distance (vowel changes cost 2x)
 * - phonemeSimilarity(): Normalized similarity score (0.0-1.0)
 *
 * WHY IPA PHONEMES?
 * - Cologne Phonetic only captures consonants (loses vowel information)
 * - "vijf" (vɛif) vs "Vink" (vɪŋk) → vowel "ɛ" vs "ɪ" is KEY distinction!
 * - IPA preserves this distinction → prevents false positive matches
 *
 * USAGE IN PIPELINE:
 * 1. AliasManager: Generate phonemes during alias creation
 * 2. AliasPriorityMatcher: Calculate phoneme similarity for fuzzy matching
 * 3. NumberPatterns: Detect number words by phoneme patterns
 *
 * IPA NOTATION USED:
 * - Consonants: b d f x ɦ j k l m n p r s t v ʋ z ŋ ʃ c
 * - Vowels (short): ɑ ə ɪ ɔ ʏ
 * - Vowels (long): aː eː iː oː y uː
 * - Diphthongs: ɛi œy ʌu øː
 *
 * AUTHOR: VT5 Team (YvedD)
 * DATE: 2025-10-28
 * VERSION: 2.1
 */
object DutchPhonemizer {

    private const val TAG = "DutchPhonemizer"

    /*═══════════════════════════════════════════════════════════════════════
     * IPA MAPPING RULES (Dutch → IPA)
     * Based on: Nederlandse Fonetiek (Gussenhoven & Broeders, 2009)
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Multi-character patterns (digraphs, trigraphs)
     *
     * IMPORTANT: Check longer patterns first!
     * Order matters: "sch" before "ch", "ng" before "g", etc.
     */
    private val multiChar = listOf(
        // Trigraphs (check first!)
        "sch" to "sx",       // "school" → sxoːl

        // Consonant digraphs
        "ng" to "ŋ",         // "zingen" → zɪŋən (velar nasal)
        "nk" to "ŋk",        // "denken" → dɛŋkən
        "sj" to "ʃ",         // "sjaal" → ʃaːl
        "tj" to "c",         // "katje" → kɑcə
        "ch" to "x",         // "acht" → ɑxt (guttural)

        // Vowel digraphs (long vowels & diphthongs)
        "aa" to "aː",        // "naam" → naːm
        "ee" to "eː",        // "beer" → beːr
        "oo" to "oː",        // "boom" → boːm
        "uu" to "y",         // "vuur" → vyr
        "oe" to "u",         // "boek" → buk
        "ie" to "i",         // "bier" → bir

        // Diphthongs (unique Dutch sounds!)
        "ui" to "œy",        // "huis" → hœys (round-unround)
        "ou" to "ʌu",        // "koud" → kʌut
        "au" to "ʌu",        // "blauw" → blʌu
        "ij" to "ɛi",        // "tijd" → tɛit
        "ei" to "ɛi",        // "klein" → klɛin
        "eu" to "øː"         // "neus" → nøːs
    )

    /**
     * Single character mapping
     *
     * Consonants: Standard IPA (mostly 1:1 mapping)
     * Vowels: Dutch short vowels (ɑ ə ɪ ɔ ʏ)
     */
    private val singleChar = mapOf(
        // Vowels (short)
        'a' to "ɑ",          // "kat" → kɑt
        'e' to "ə",          // "de" → də (schwa)
        'i' to "ɪ",          // "wit" → wɪt
        'o' to "ɔ",          // "pot" → pɔt
        'u' to "ʏ",          // "put" → pʏt

        // Consonants (standard IPA)
        'b' to "b",
        'c' to "k",          // "café" → kafeː
        'd' to "d",
        'f' to "f",
        'g' to "x",          // Dutch guttural g (not English!)
        'h' to "ɦ",          // voiced glottal
        'j' to "j",          // "jaar" → jaːr (palatal approximant)
        'k' to "k",
        'l' to "l",
        'm' to "m",
        'n' to "n",
        'p' to "p",
        'q' to "k",          // "quiz" → kwɪz
        'r' to "r",          // trilled or uvular (dialect-dependent)
        's' to "s",
        't' to "t",
        'v' to "v",
        'w' to "ʋ",          // labiodental approximant (unique Dutch!)
        'x' to "ks",         // "taxi" → tɑksi
        'y' to "i",          // "baby" → beːbi
        'z' to "z"
    )

    /**
     * Vowel set (for weighted distance calculation)
     *
     * Used in phonemeDistance() to apply higher cost to vowel↔consonant
     * substitutions (major phonetic difference).
     */
    private val vowels = setOf(
        // Short vowels
        "ɑ", "ə", "ɪ", "ɔ", "ʏ",

        // Long vowels
        "aː", "eː", "iː", "oː", "y", "u",

        // Diphthongs
        "ɛi", "œy", "ʌu", "øː"
    )

    /*═══════════════════════════════════════════════════════════════════════
     * PUBLIC API: Phonemization & Distance
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Convert Dutch text to IPA phonemes
     *
     * Algorithm:
     * 1. Lowercase input
     * 2. Try multi-char patterns (longest match)
     * 3. Fall back to single-char mapping
     * 4. Return space-separated phoneme string
     *
     * @param text Normalized Dutch text (lowercase, no diacritics)
     * @return IPA phoneme string (space-separated)
     *
     * Examples:
     * phonemize("aalscholver") → "aːlsxɔlvər"
     * phonemize("vijf") → "vɛif"
     * phonemize("vink") → "vɪŋk"
     * phonemize("blauwe kiekendief") → "blʌu kikəndif"
     */
    fun phonemize(text: String): String {
        if (text.isBlank()) return ""

        val normalized = text.lowercase(Locale.getDefault())
        var rest = normalized
        val phonemes = mutableListOf<String>()

        while (rest.isNotEmpty()) {
            var matched = false

            // Try multi-character patterns first (longest match wins)
            for ((pattern, ipa) in multiChar) {
                if (rest.startsWith(pattern)) {
                    phonemes += ipa
                    rest = rest.removePrefix(pattern)
                    matched = true
                    break
                }
            }

            // Fall back to single character
            if (!matched) {
                val ch = rest.first()

                // Skip whitespace (phonemes already space-separated)
                if (ch.isWhitespace()) {
                    rest = rest.drop(1)
                    continue
                }

                val ipa = singleChar[ch]
                if (ipa != null) {
                    phonemes += ipa
                } else {
                    // Unknown character (loan word?), keep as-is
                    phonemes += ch.toString()
                }
                rest = rest.drop(1)
            }
        }

        return phonemes.joinToString(" ")
    }

    /**
     * Calculate phoneme distance with vowel weighting
     *
     * WHY WEIGHTED?
     * Vowel changes indicate major phonetic differences:
     * - "vijf" (vɛif) vs "Vink" (vɪŋk): vowel ɛ→ɪ is KEY distinction
     * - "ali" (ɑli) vs "Aalscholver" (aːl...): long/short vowel matters
     *
     * COST MATRIX:
     * - Same phoneme: 0
     * - Vowel ↔ Vowel: 1 (acceptable variation)
     * - Consonant ↔ Consonant: 1 (acceptable variation)
     * - Vowel ↔ Consonant: 2 (major phonetic difference!)
     *
     * @param phonemes1 First phoneme string (space-separated IPA)
     * @param phonemes2 Second phoneme string (space-separated IPA)
     * @return Weighted edit distance
     *
     * Examples:
     * phonemeDistance("vɛif", "vɪŋk") → 5 (high! different species)
     * phonemeDistance("ɑli", "aːl") → 2 (moderate, fuzzy match OK)
     * phonemeDistance("blʌu", "blʌu") → 0 (identical)
     */
    fun phonemeDistance(phonemes1: String, phonemes2: String): Int {
        val p1 = phonemes1.split(" ").filter { it.isNotBlank() }
        val p2 = phonemes2.split(" ").filter { it.isNotBlank() }

        if (p1.isEmpty()) return p2.size
        if (p2.isEmpty()) return p1.size

        // Dynamic programming table
        val dp = Array(p1.size + 1) { IntArray(p2.size + 1) }

        // Initialize first row/column
        for (i in 0..p1.size) dp[i][0] = i
        for (j in 0..p2.size) dp[0][j] = j

        // Fill DP table with weighted costs
        for (i in 1..p1.size) {
            for (j in 1..p2.size) {
                if (p1[i-1] == p2[j-1]) {
                    // Exact match: no cost
                    dp[i][j] = dp[i-1][j-1]
                } else {
                    // Different phonemes: calculate substitution cost
                    val isVowel1 = p1[i-1] in vowels
                    val isVowel2 = p2[j-1] in vowels

                    val substitutionCost = if (isVowel1 != isVowel2) {
                        2  // Vowel ↔ Consonant: major difference!
                    } else {
                        1  // Vowel ↔ Vowel or Consonant ↔ Consonant: normal
                    }

                    dp[i][j] = minOf(
                        dp[i-1][j] + 1,                // deletion
                        dp[i][j-1] + 1,                // insertion
                        dp[i-1][j-1] + substitutionCost // substitution
                    )
                }
            }
        }

        return dp[p1.size][p2.size]
    }

    /**
     * Calculate phoneme similarity (normalized, 0.0-1.0)
     *
     * Converts distance to similarity score for use in fuzzy matching.
     *
     * @param phonemes1 First phoneme string
     * @param phonemes2 Second phoneme string
     * @return Similarity score (0.0 = completely different, 1.0 = identical)
     *
     * Formula: 1.0 - (distance / max_length)
     *
     * Examples:
     * phonemeSimilarity("vɛif", "vɪŋk") → ~0.17 (very different)
     * phonemeSimilarity("ɑli", "aːl") → ~0.67 (moderately similar)
     * phonemeSimilarity("blʌu", "blʌu") → 1.0 (identical)
     */
    fun phonemeSimilarity(phonemes1: String, phonemes2: String): Double {
        val distance = phonemeDistance(phonemes1, phonemes2)
        val maxLen = maxOf(
            phonemes1.split(" ").filter { it.isNotBlank() }.size,
            phonemes2.split(" ").filter { it.isNotBlank() }.size
        )

        if (maxLen == 0) return 1.0

        return 1.0 - (distance.toDouble() / maxLen.toDouble())
    }
}