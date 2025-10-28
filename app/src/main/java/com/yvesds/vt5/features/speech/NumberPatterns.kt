package com.yvesds.vt5.features.speech

import com.yvesds.vt5.features.alias.AliasRecord

/**
 * NumberPatterns.kt
 *
 * Hard-coded Dutch number words (0-100) and fast phonetic/cologne filters.
 * Designed to:
 *  - parseNumberWord(word): Int?
 *  - isNumberWord(text): Boolean
 *  - isNumberCologne(cologne): Boolean
 *  - isNumberPhoneme(phonemes): Boolean
 *  - filterNumberCandidates(candidates): List<AliasRecord>
 *
 * This file intentionally contains robust mappings and tolerant phoneme checks
 * to reduce false positives where ASR outputs a number-word and fuzzy matching
 * could otherwise match a species alias (e.g., "vijf" -> "Vink").
 *
 * Author: VT5 Team (YvedD)
 * Date: 2025-10-28
 * Version: 2.1
 */

object NumberPatterns {

    // -------------------------
    // Layer 1: text -> integer
    // -------------------------
    private val numberWords: Map<String, Int> = mapOf(
        // 0-20
        "nul" to 0, "zero" to 0,
        "een" to 1, "één" to 1, "eén" to 1, "ene" to 1,
        "twee" to 2,
        "drie" to 3,
        "vier" to 4,
        "vijf" to 5,
        "zes" to 6,
        "zeven" to 7,
        "acht" to 8,
        "negen" to 9,
        "tien" to 10,
        "elf" to 11,
        "twaalf" to 12,
        "dertien" to 13,
        "veertien" to 14,
        "vijftien" to 15,
        "zestien" to 16,
        "zeventien" to 17,
        "achttien" to 18,
        "negentien" to 19,
        "twintig" to 20,
        // tens and some compounds (representative)
        "dertig" to 30, "veertig" to 40, "vijftig" to 50, "zestig" to 60,
        "zeventig" to 70, "tachtig" to 80, "negentig" to 90, "honderd" to 100
    ) + (21..99).associate { i ->
        // simple generative variants are not exhaustive; keep explicit entries for common forms
        i.toString() to i
    }

    // -------------------------------------------------
    // Layer 2: Cologne code fast-match patterns (set)
    // -------------------------------------------------
    // These codes are approximate / common codes for written numbers (used as fast filter)
    private val numberCologneCodes: Set<String> = setOf(
        "65", "07", "06", "2", "27", "37", "35", "08", "086", "042", "064", "26",
        "26424", "47424", "37424", "35424", "08424", "042424", "064424", "06272"
    )

    // -------------------------------------------------
    // Layer 3: IPA phoneme patterns (small snippets)
    // -------------------------------------------------
    // Keep short snippet forms that capture the vowel or characteristic sequence
    private val numberPhonemePatterns: Set<String> = setOf(
        "vɛif", "vɛif", // vijf
        "eːn", "tweː", "driː", "viːr", "zɛs", "zeːvən", "ɑxt", "neːɣən", "tiːn",
        "ɛlf", "twaːlf", "dərtiɣ", "veːrtiɣ", "vɛiftiɣ", "zɛstiɣ", "hɔndərt"
    )

    // PUBLIC API ----------------------------------------------------------------

    fun parseNumberWord(word: String): Int? {
        val w = word.trim().lowercase()
        // direct map
        numberWords[w]?.let { return it }
        // try remove punctuation
        val cleaned = w.replace("[^\\p{L}\\p{Nd}]".toRegex(), "")
        return numberWords[cleaned]
    }

    fun isNumberWord(text: String): Boolean {
        val w = text.trim().lowercase()
        if (numberWords.containsKey(w)) return true
        val cleaned = w.replace("[^\\p{L}\\p{Nd}]".toRegex(), "")
        return numberWords.containsKey(cleaned)
    }

    fun isNumberCologne(cologne: String?): Boolean {
        if (cologne == null) return false
        return numberCologneCodes.contains(cologne)
    }

    fun isNumberPhoneme(phonemes: String?): Boolean {
        if (phonemes.isNullOrBlank()) return false
        val norm = phonemes.replace("\\s+".toRegex(), "")
        for (p in numberPhonemePatterns) {
            if (norm.contains(p)) return true
            // tolerant match: allow small edit distance
            if (levenshteinDistance(norm, p) <= 1) return true
        }
        return false
    }

    /**
     * Filter out candidate alias records that appear to be number words.
     * We accept a list of AliasRecord and return filtered list.
     */
    fun filterNumberCandidates(candidates: List<AliasRecord>): List<AliasRecord> {
        return candidates.filter { rec ->
            // textual alias check
            if (isNumberWord(rec.alias)) return@filter false
            // cologne check
            if (rec.cologne != null && isNumberCologne(rec.cologne)) return@filter false
            // phoneme check
            if (rec.phonemes != null && isNumberPhoneme(rec.phonemes)) return@filter false
            true
        }
    }

    // Utility: Levenshtein (simple implementation)
    private fun levenshteinDistance(a: String, b: String): Int {
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val prev = IntArray(lb + 1) { it }
        val cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, lb + 1)
        }
        return prev[lb]
    }
}