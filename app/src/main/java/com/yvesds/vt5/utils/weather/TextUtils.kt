package com.yvesds.vt5.utils

import java.util.Locale
import java.util.regex.Pattern

/**
 * Small text utilities centralising normalization and simple parsing helpers.
 *
 * - normalizeLowerNoDiacritics: lowercase, remove diacritics, keep letters/digits and single spaces.
 * - parseTrailingInteger: returns Pair<nameOnly, parsedInt?> where parsedInt is null if none.
 * - isFilterWord: tests common "system prompts" (e.g. "luisteren").
 *
 * Keep this file tiny and dependency-free so it can be reused everywhere.
 */

object TextUtils {
    // words we want to filter from partials / ASR streaming
    val FILTER_WORDS: Set<String> = setOf("luisteren", "luisteren...", "luister")

    private val TRAILING_NUMBER: Pattern = Pattern.compile("^(.*?)(?:\\s+(\\d+)(?:[.,]\\d+)?)?$")

    /**
     * Normalize to lowercase, remove diacritics and non-alphanumeric characters,
     * and collapse whitespace to single spaces.
     *
     * Matches previous PrecomputeAliasIndex.normalizeLowerNoDiacritics behaviour.
     */
    fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }

    /**
     * Parse trailing integer from a text like "aalscholver 3" -> ("aalscholver", 3)
     * If no trailing integer is present returns (originalTrimmed, null).
     */
    fun parseTrailingInteger(text: String): Pair<String, Int?> {
        val t = text.trim()
        if (t.isEmpty()) return Pair("", null)
        val m = TRAILING_NUMBER.matcher(t)
        return if (m.matches()) {
            val nameOnly = m.group(1)?.trim().orEmpty()
            val num = try {
                m.group(2)?.toInt()
            } catch (ex: Exception) {
                null
            }
            Pair(nameOnly, num)
        } else {
            Pair(t, null)
        }
    }

    /**
     * Convenience alias used by callers to check filter words after normalization.
     */
    fun isFilterWord(text: String): Boolean {
        val normalized = normalizeLowerNoDiacritics(text)
        return FILTER_WORDS.contains(normalized)
    }
}