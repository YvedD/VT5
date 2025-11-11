package com.yvesds.vt5.features.speech

import java.util.Locale
import java.util.Collections
import java.util.LinkedHashMap

/**
 * DutchPhonemizer.kt (optimized)
 *
 * - Keeps the original public API (phonemize / phonemeDistance / phonemeSimilarity).
 * - Uses an internal bounded LRU cache for phonemize results to avoid repeated work.
 * - Avoids creating intermediate substrings in phonemize by scanning with indices.
 * - Uses a memory-efficient dynamic programming implementation for phonemeDistance
 *   (two-row Levenshtein) to reduce allocation pressure.
 *
 * Performance notes:
 * - phonemizeCached reduces repeated phonemization of identical queries (typical in matching).
 * - phonemeDistance uses O(min(n,m)) extra memory instead of O(n*m).
 * - multiChar patterns are evaluated in length‑descending order (longest first).
 */
object DutchPhonemizer {

    private const val TAG = "DutchPhonemizer"

    // LRU phonemize cache: synchronized access
    private const val PHONEMIZE_CACHE_SIZE = 2000
    private val phonemizeCache: MutableMap<String, String> =
        Collections.synchronizedMap(object : LinkedHashMap<String, String>(PHONEMIZE_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
                return size > PHONEMIZE_CACHE_SIZE
            }
        })

    /* Multi-character patterns (digraphs/trigraphs)
     * NOTE: kept as list of pairs for readability; we sort by key length descending on init.
     */
    private val multiCharRaw = listOf(
        "sch" to "sx",       // "school" → sxoːl
        "ng" to "ŋ",         // "zingen" → zɪŋən
        "nk" to "ŋk",        // "denken" → dɛŋkən
        "sj" to "ʃ",         // "sjaal" → ʃaːl
        "tj" to "c",         // "katje" → kɑcə
        "ch" to "x",         // "acht" → ɑxt

        // Vowel digraphs
        "aa" to "aː",
        "ee" to "eː",
        "oo" to "oː",
        "uu" to "y",
        "oe" to "u",
        "ie" to "i",

        // Diphthongs
        "ui" to "œy",
        "ou" to "ʌu",
        "au" to "ʌu",
        "ij" to "ɛi",
        "ei" to "ɛi",
        "eu" to "øː"
    )

    // Sorted by pattern length descending to ensure longest matches first
    private val multiChar: List<Pair<String, String>> = multiCharRaw.sortedByDescending { it.first.length }

    // Single character map
    private val singleChar = mapOf(
        'a' to "ɑ",
        'e' to "ə",
        'i' to "ɪ",
        'o' to "ɔ",
        'u' to "ʏ",

        'b' to "b",
        'c' to "k",
        'd' to "d",
        'f' to "f",
        'g' to "x",
        'h' to "ɦ",
        'j' to "j",
        'k' to "k",
        'l' to "l",
        'm' to "m",
        'n' to "n",
        'p' to "p",
        'q' to "k",
        'r' to "r",
        's' to "s",
        't' to "t",
        'v' to "v",
        'w' to "ʋ",
        'x' to "ks",
        'y' to "i",
        'z' to "z"
    )

    // Vowels set (used for weighted substitution cost)
    private val vowels = setOf(
        "ɑ", "ə", "ɪ", "ɔ", "ʏ",
        "aː", "eː", "iː", "oː", "y", "u",
        "ɛi", "œy", "ʌu", "øː"
    )

    /**
     * Public: phonemize with internal caching.
     * Keeps signature identical to original phonemize.
     */
    fun phonemize(text: String): String {
        // Use cache key based on normalized input (lowercase, trimmed)
        if (text.isBlank()) return ""
        val normalized = text.trim().lowercase(Locale.getDefault())
        // Fast path cache lookup
        phonemizeCache[normalized]?.let { return it }

        // Compute and store under lock
        val computed = phonemizeUncached(normalized)
        phonemizeCache[normalized] = computed
        return computed
    }

    /**
     * Core phonemize implementation that avoids creating substrings for the remainder.
     * Returns a space-separated phoneme string.
     */
    private fun phonemizeUncached(normalizedLower: String): String {
        if (normalizedLower.isBlank()) return ""
        val s = normalizedLower
        val len = s.length
        val sb = StringBuilder(len * 2) // heuristic capacity

        var pos = 0
        var firstToken = true
        while (pos < len) {
            val ch = s[pos]
            if (ch.isWhitespace()) {
                pos++
                continue
            }

            var matched = false
            // Try multi-character patterns (ordered longest-first)
            for ((pattern, ipa) in multiChar) {
                val patLen = pattern.length
                if (pos + patLen <= len && s.regionMatches(pos, pattern, 0, patLen, ignoreCase = false)) {
                    if (!firstToken) sb.append(' ')
                    sb.append(ipa)
                    firstToken = false
                    pos += patLen
                    matched = true
                    break
                }
            }
            if (matched) continue

            // Single character fallback
            val ipa = singleChar[ch] ?: ch.toString()
            if (!firstToken) sb.append(' ')
            sb.append(ipa)
            firstToken = false
            pos++
        }

        return sb.toString()
    }

    /**
     * Tokenize a phoneme string into an Array<String> of tokens (space-separated).
     * Lightweight helper used by distance/similarity. Avoids repeated allocation
     * where possible by returning a new array only when needed.
     */
    private fun tokenizePhonemes(phonemes: String): Array<String> {
        if (phonemes.isBlank()) return emptyArray()
        // split on spaces and filter blanks
        val parts = phonemes.split(' ')
        val filtered = ArrayList<String>(parts.size)
        for (p in parts) if (p.isNotBlank()) filtered.add(p)
        return filtered.toTypedArray()
    }

    /**
     * Compute weighted phoneme distance using Levenshtein with vowel weighting.
     * Memory-efficient two-row DP implementation (O(min(n,m)) extra memory).
     */
    fun phonemeDistance(phonemes1: String, phonemes2: String): Int {
        val p1 = tokenizePhonemes(phonemes1)
        val p2 = tokenizePhonemes(phonemes2)

        val n = p1.size
        val m = p2.size
        if (n == 0) return m
        if (m == 0) return n

        // To minimize memory, ensure m <= n (swap if needed)
        var a = p1
        var b = p2
        var swapped = false
        if (m > n) {
            a = p2
            b = p1
            swapped = true
        }
        val na = a.size
        val nb = b.size

        // prev and cur rows
        var prev = IntArray(nb + 1) { it } // prev[j] = j
        var cur = IntArray(nb + 1)

        for (i in 1..na) {
            cur[0] = i
            val ai = a[i - 1]
            val isVowelAi = ai in vowels
            for (j in 1..nb) {
                val bj = b[j - 1]
                if (ai == bj) {
                    cur[j] = prev[j - 1]
                } else {
                    val isVowelBj = bj in vowels
                    val substitutionCost = if (isVowelAi != isVowelBj) 2 else 1
                    val deletion = prev[j] + 1
                    val insertion = cur[j - 1] + 1
                    val substitution = prev[j - 1] + substitutionCost
                    var min = deletion
                    if (insertion < min) min = insertion
                    if (substitution < min) min = substitution
                    cur[j] = min
                }
            }
            // swap rows
            val tmp = prev
            prev = cur
            cur = tmp
        }

        return prev[nb]
    }

    /**
     * Normalized similarity: 1.0 - (distance / max_len)
     */
    fun phonemeSimilarity(phonemes1: String, phonemes2: String): Double {
        val distance = phonemeDistance(phonemes1, phonemes2)
        val maxLen = maxOf(
            tokenizePhonemes(phonemes1).size,
            tokenizePhonemes(phonemes2).size
        )
        if (maxLen == 0) return 1.0
        return 1.0 - (distance.toDouble() / maxLen.toDouble())
    }

    /* Optional helpers for direct cached access from other code (non-breaking additions) */

    /**
     * Phonemize using internal cache without modifying the public phonemize signature.
     * Useful for callers that want to avoid redundant normalization themselves.
     */
    fun phonemizeCached(rawText: String): String {
        return phonemize(rawText)
    }
}