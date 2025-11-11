package com.yvesds.vt5.features.speech

import com.yvesds.vt5.features.alias.AliasRecord
import com.yvesds.vt5.utils.TextUtils

/**
 * NumberPatterns.kt
 *
 * Hard-coded Dutch number words (0-100) and fast phonetic/cologne filters.
 * Provides robust parsing and tolerant phoneme checks to reduce false positives
 * where ASR outputs a number-word and fuzzy matching could otherwise match a species alias.
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
        // include numeric strings as fallback keys ("21", "22", ...)
        i.toString() to i
    }

    // -------------------------------------------------
    // Layer 2: Cologne code fast-match patterns (set)
    // -------------------------------------------------
    private val numberCologneCodes: Set<String> = setOf(
        "65", "07", "06", "2", "27", "37", "35", "08", "086", "042", "064", "26",
        "26424", "47424", "37424", "35424", "08424", "042424", "064424", "06272"
    )

    // -------------------------------------------------
    // Layer 3: IPA phoneme patterns (small snippets)
    // -------------------------------------------------
    private val numberPhonemePatterns: Set<String> = setOf(
        "vɛif", "eːn", "tweː", "driː", "viːr", "zɛs", "zeːvən", "ɑxt", "neːɣən", "tiːn",
        "ɛlf", "twaːlf", "dərtiɣ", "veːrtiɣ", "vɛiftiɣ", "zɛstiɣ", "hɔndərt"
    )

    // PUBLIC API ----------------------------------------------------------------

    fun parseNumberWord(word: String): Int? {
        // Use centralized normalization so "één", punctuation, extra spaces etc. are treated consistently
        val normalized = TextUtils.normalizeLowerNoDiacritics(word)
        if (normalized.isBlank()) return null

        // direct map lookup
        numberWords[normalized]?.let { return it }

        // handle bare digits possibly embedded in punctuation (e.g., "3," -> "3")
        val digitsOnly = normalized.replace("[^0-9]".toRegex(), "")
        if (digitsOnly.isNotBlank()) {
            digitsOnly.toIntOrNull()?.let { return it }
        }

        return null
    }

    fun isNumberWord(text: String): Boolean {
        val normalized = TextUtils.normalizeLowerNoDiacritics(text)
        if (normalized.isBlank()) return false
        if (numberWords.containsKey(normalized)) return true
        val digitsOnly = normalized.replace("[^0-9]".toRegex(), "")
        return digitsOnly.isNotBlank() && digitsOnly.toIntOrNull() != null
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
     * Predicate: is this AliasRecord likely a number (so we should ignore it for species matching)?
     * Lightweight, avoids allocating lists; used in tight loops.
     */
    fun isNumberCandidate(rec: AliasRecord): Boolean {
        // Check textual alias first (alias field is raw alias string)
        if (isNumberWord(rec.alias)) return true
        // cologne code
        if (!rec.cologne.isNullOrBlank() && isNumberCologne(rec.cologne)) return true
        // phoneme patterns
        if (!rec.phonemes.isNullOrBlank() && isNumberPhoneme(rec.phonemes)) return true
        return false
    }

    /**
     * Filter out candidate alias records that appear to be number words.
     * Kept for compatibility; internally uses isNumberCandidate.
     */
    fun filterNumberCandidates(candidates: List<AliasRecord>): List<AliasRecord> {
        return candidates.filter { rec -> !isNumberCandidate(rec) }
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