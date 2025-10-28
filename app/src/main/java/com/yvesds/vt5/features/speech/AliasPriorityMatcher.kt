package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/**
 * AliasPriorityMatcher - 9-step priority cascade with scoring.
 *
 * UPDATED: Multi-token parsing with sliding window + Dutch number word support.
 * - Splits query into tokens
 * - Tries sliding windows of 1-6 tokens for matching
 * - Skips numbers (digits + Dutch words like "vijf") during matching
 * - Extracts numbers (digits + Dutch words) for amounts
 * - Filters number words from fuzzy matching to prevent false positives (e.g., "vijf" → "Vink")
 * - Returns list of matches with amounts for multi-species queries
 *
 * Example: "blauwe kiekendief vijf" ->
 *   - Match "blauwe kiekendief" -> Blauwe Kiekendief, amount=5 (parsed from "vijf")
 *
 * Example: "aalscholver 5 boertjes 3" ->
 *   - Match "aalscholver" -> Aalscholver, amount=5
 *   - Match "boertjes" -> Boerenzwaluw, amount=3
 *   - Return MultiMatch([Aalscholver:5, Boerenzwaluw:3])
 */
object AliasPriorityMatcher {
    private const val TAG = "AliasPriorityMatcher"

    // Score thresholds / params
    private const val AUTO_ACCEPT_THRESHOLD = 0.70
    private const val SUGGEST_THRESHOLD = 0.40
    private const val AUTO_ACCEPT_MARGIN = 0.12

    // Weights for combined score
    private const val W_TEXT = 0.45
    private const val W_PHON = 0.30
    private const val W_PRIOR = 0.25

    // Prior components
    private const val PRIOR_RECENT = 0.25
    private const val PRIOR_TILES = 0.25
    private const val PRIOR_SITE = 0.15

    // Dutch number words blacklist (to prevent "vijf" matching "Vink", etc.)
    private val NUMBER_WORDS = setOf(
        "nul", "een", "één", "eén", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht", "negen",
        "tien", "elf", "twaalf", "dertien", "veertien", "vijftien", "zestien", "zeventien", "achttien", "negentien",
        "twintig", "eenentwintig", "tweeëntwintig", "drieëntwintig", "vierentwintig", "vijfentwintig",
        "dertig", "veertig", "vijftig", "zestig", "zeventig", "tachtig", "negentig", "honderd"
    )

    /**
     * Parse Dutch number words to integers.
     */
    private fun parseNumberWord(word: String): Int? {
        return when (word.lowercase(Locale.getDefault())) {
            "nul", "zero" -> 0
            "een", "één", "eén" -> 1
            "twee" -> 2
            "drie" -> 3
            "vier" -> 4
            "vijf" -> 5
            "zes" -> 6
            "zeven" -> 7
            "acht" -> 8
            "negen" -> 9
            "tien" -> 10
            "elf" -> 11
            "twaalf" -> 12
            "dertien" -> 13
            "veertien" -> 14
            "vijftien" -> 15
            "zestien" -> 16
            "zeventien" -> 17
            "achttien" -> 18
            "negentien" -> 19
            "twintig" -> 20
            "eenentwintig" -> 21
            "tweeëntwintig", "tweeentwintig" -> 22
            "drieëntwintig", "drieentwintig" -> 23
            "vierentwintig" -> 24
            "vijfentwintig" -> 25
            "dertig" -> 30
            "veertig" -> 40
            "vijftig" -> 50
            "zestig" -> 60
            "zeventig" -> 70
            "tachtig" -> 80
            "negentig" -> 90
            "honderd" -> 100
            else -> null
        }
    }

    /**
     * Check if a token is a number (digit or Dutch word).
     */
    private fun isNumberToken(token: String): Boolean {
        return token.toIntOrNull() != null || parseNumberWord(token) != null
    }

    /**
     * Parse a token as a number (digit or Dutch word).
     */
    private fun parseAmountToken(token: String): Int? {
        return token.toIntOrNull() ?: parseNumberWord(token)
    }

    /**
     * Main match function - NOW with multi-token sliding window + Dutch number support.
     * Returns a MatchResult which may contain MULTIPLE species with amounts.
     */
    suspend fun match(
        hypothesis: String,
        matchContext: MatchContext,
        context: Context,
        saf: SaFStorageHelper
    ): MatchResult = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        val hyp = hypothesis.trim()
        if (hyp.isBlank()) return@withContext MatchResult.NoMatch(hyp, "empty")

        try {
            AliasMatcher.ensureLoaded(context, saf)
        } catch (ex: Exception) {
            Log.w(TAG, "Alias index ensureLoaded failed: ${ex.message}", ex)
        }

        val normalized = normalizeLowerNoDiacritics(hyp)
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            return@withContext MatchResult.NoMatch(hyp, "empty-after-norm")
        }

        // NEW: Multi-token sliding window matching (like old parseSpoken logic)
        val matches = mutableListOf<MatchResult.MatchWithAmount>()
        var i = 0

        while (i < tokens.size) {
            val maxWindow = minOf(6, tokens.size - i)
            var matched = false

            // Try sliding window from longest to shortest
            for (w in maxWindow downTo 1) {
                val windowTokens = tokens.subList(i, i + w)

                // Skip if window contains ONLY numbers
                if (windowTokens.all { isNumberToken(it) }) {
                    i++
                    matched = true
                    break
                }

                // Skip if window contains ANY number words (prevents "vijf" → "Vink" matching)
                val hasNumberWord = windowTokens.any { it.lowercase() in NUMBER_WORDS }
                if (hasNumberWord) {
                    continue
                }

                val phrase = windowTokens.joinToString(" ")

                // Try exact match first (steps 1-4)
                val exactMatch = tryExactMatch(phrase, matchContext, context, saf)
                if (exactMatch != null) {
                    // Extract amount from next token (if it's a number)
                    val nextIndex = i + w
                    var amount = 1
                    if (nextIndex < tokens.size) {
                        val maybeNum = parseAmountToken(tokens[nextIndex])
                        if (maybeNum != null) {
                            amount = maybeNum
                            i = nextIndex + 1
                        } else {
                            i = nextIndex
                        }
                    } else {
                        i = nextIndex
                    }

                    matches += MatchResult.MatchWithAmount(exactMatch.first, amount, exactMatch.second)
                    matched = true
                    break
                }
            }

            if (!matched) {
                // Try fuzzy match (steps 5-8)
                for (w in maxWindow downTo 1) {
                    val windowTokens = tokens.subList(i, i + w)

                    // Skip if window contains ONLY numbers
                    if (windowTokens.all { isNumberToken(it) }) {
                        i++
                        matched = true
                        break
                    }

                    // Skip if window contains ANY number words
                    val hasNumberWord = windowTokens.any { it.lowercase() in NUMBER_WORDS }
                    if (hasNumberWord) {
                        continue
                    }

                    val phrase = windowTokens.joinToString(" ")

                    val fuzzyMatch = tryFuzzyMatch(phrase, matchContext, context, saf)
                    if (fuzzyMatch != null) {
                        val nextIndex = i + w
                        var amount = 1
                        if (nextIndex < tokens.size) {
                            val maybeNum = parseAmountToken(tokens[nextIndex])
                            if (maybeNum != null) {
                                amount = maybeNum
                                i = nextIndex + 1
                            } else {
                                i = nextIndex
                            }
                        } else {
                            i = nextIndex
                        }

                        matches += MatchResult.MatchWithAmount(fuzzyMatch.first, amount, fuzzyMatch.second)
                        matched = true
                        break
                    }
                }
            }

            if (!matched) {
                // Skip this token and continue
                i++
            }
        }

        val t1 = System.nanoTime()
        Log.d(TAG, "match: found ${matches.size} matches, timeMs=${(t1 - t0) / 1_000_000}")

        // Convert matches to MatchResult
        if (matches.isEmpty()) {
            return@withContext MatchResult.NoMatch(hyp, "no-candidates")
        }

        // If we have multiple matches, return as MultiMatch (NEW result type)
        if (matches.size > 1) {
            return@withContext MatchResult.MultiMatch(matches, hyp, "multi-species")
        }

        // Single match - use existing logic
        val match = matches.first()
        return@withContext if (match.candidate.isInTiles) {
            MatchResult.AutoAccept(match.candidate, hyp, match.source, match.amount)
        } else {
            MatchResult.AutoAcceptAddPopup(match.candidate, hyp, match.source, match.amount)
        }
    }

    /**
     * Try exact match (steps 1-4: canonical/alias in tiles/site).
     * Returns Pair(Candidate, source) or null.
     */
    private suspend fun tryExactMatch(
        phrase: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): Pair<Candidate, String>? {
        val normalized = normalizeLowerNoDiacritics(phrase)

        // 1. Exact canonical in tiles
        val exactCanonicalTiles = findExactCanonicalInSet(normalized, ctx.tilesSpeciesIds, ctx)
        if (exactCanonicalTiles != null) {
            return exactCanonicalTiles to "exact_canonical_tiles"
        }

        // 2. Exact canonical in site (not in tiles)
        val exactCanonicalSite = findExactCanonicalInSet(normalized, ctx.siteAllowedIds, ctx)
        if (exactCanonicalSite != null && !ctx.tilesSpeciesIds.contains(exactCanonicalSite.speciesId)) {
            return exactCanonicalSite to "exact_canonical_site"
        }

        // 3. Exact alias in tiles
        val exactAliasTiles = findExactAliasInSet(normalized, ctx.tilesSpeciesIds, appContext, saf)
        if (exactAliasTiles != null) {
            return exactAliasTiles to "exact_alias_tiles"
        }

        // 4. Exact alias in site (not in tiles)
        val exactAliasSite = findExactAliasInSet(normalized, ctx.siteAllowedIds, appContext, saf)
        if (exactAliasSite != null && !ctx.tilesSpeciesIds.contains(exactAliasSite.speciesId)) {
            return exactAliasSite to "exact_alias_site"
        }

        return null
    }

    /**
     * Try fuzzy match (steps 5-8: fuzzy canonical/alias in tiles/site).
     * Returns Pair(Candidate, source) or null.
     */
    private suspend fun tryFuzzyMatch(
        phrase: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): Pair<Candidate, String>? {
        val normalized = normalizeLowerNoDiacritics(phrase)

        val fuzzyCandidates = findFuzzyCandidates(normalized, ctx, appContext, saf)
        if (fuzzyCandidates.isEmpty()) {
            return null
        }

        val sorted = fuzzyCandidates.sortedByDescending { it.score }
        val top = sorted.first()

        // Accept if score is good enough
        if (top.score >= SUGGEST_THRESHOLD) {
            val source = if (top.isInTiles) "fuzzy_tiles" else "fuzzy_site"
            return top to source
        }

        return null
    }

    // ========== Helper methods (mostly unchanged) ==========

    private fun findExactCanonicalInSet(normalized: String, speciesIds: Set<String>, ctx: MatchContext): Candidate? {
        for (sid in speciesIds) {
            val (canonical, tilename) = ctx.speciesById[sid] ?: continue
            val canonNorm = normalizeLowerNoDiacritics(canonical)
            if (canonNorm == normalized) {
                return Candidate(sid, tilename ?: canonical, 1.0, sid in ctx.tilesSpeciesIds, "canonical-exact")
            }
        }
        return null
    }

    private suspend fun findExactAliasInSet(normalized: String, speciesIds: Set<String>, appContext: Context, saf: SaFStorageHelper): Candidate? {
        val records = AliasMatcher.findExact(normalized, appContext, saf)
        for (r in records) {
            if (r.speciesid in speciesIds) {
                val canonical = r.canonical
                val tilename = r.tilename
                return Candidate(r.speciesid, tilename ?: canonical, 1.0, r.speciesid in speciesIds, "alias-exact")
            }
        }
        return null
    }

    private suspend fun findFuzzyCandidates(normalized: String, ctx: MatchContext, appContext: Context, saf: SaFStorageHelper): List<Candidate> {
        val accum = mutableListOf<Candidate>()
        accum += fuzzyCanonicalInSet(normalized, ctx.tilesSpeciesIds, ctx)
        accum += fuzzyAliasInSet(normalized, ctx.tilesSpeciesIds, ctx, appContext, saf)
        val siteOnly = ctx.siteAllowedIds - ctx.tilesSpeciesIds
        accum += fuzzyCanonicalInSet(normalized, siteOnly, ctx)
        accum += fuzzyAliasInSet(normalized, siteOnly, ctx, appContext, saf)

        // dedupe keep best
        val byId = linkedMapOf<String, Candidate>()
        for (c in accum) {
            val ex = byId[c.speciesId]
            if (ex == null || c.score > ex.score) byId[c.speciesId] = c
        }
        return byId.values.toList()
    }

    private fun fuzzyCanonicalInSet(normalized: String, speciesIds: Set<String>, ctx: MatchContext): List<Candidate> {
        // FIXED: Strip numbers (digits + Dutch words) from tokens for distance calculation
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() && !isNumberToken(it) }
        val allowDist = when {
            tokens.isEmpty() -> 2
            tokens.size == 1 -> 2
            tokens.size == 2 -> 3
            else -> 4
        }
        val out = mutableListOf<Candidate>()
        for (sid in speciesIds) {
            val (canonical, tilename) = ctx.speciesById[sid] ?: continue
            val canonNorm = normalizeLowerNoDiacritics(canonical)
            val lev = levenshteinDistance(normalized, canonNorm)
            if (lev > allowDist) continue
            val textSim = 1.0 - (lev.toDouble() / max(normalized.length, canonNorm.length).toDouble())
            val phonSim = runCatching { ColognePhonetic.similarity(normalized, canonNorm) }.getOrDefault(0.0)
            val prior = computePrior(sid, ctx)
            val score = (W_TEXT * textSim + W_PHON * phonSim + W_PRIOR * prior).coerceIn(0.0, 1.0)
            out += Candidate(sid, tilename ?: canonical, score, sid in ctx.tilesSpeciesIds, "fuzzy-canon")
        }
        return out
    }

    private suspend fun fuzzyAliasInSet(normalized: String, speciesIds: Set<String>, ctx: MatchContext, appContext: Context, saf: SaFStorageHelper): List<Candidate> {
        val records = AliasMatcher.findFuzzyCandidates(normalized, appContext, saf, topN = 50, threshold = 0.0)
        val out = mutableListOf<Candidate>()

        // FIXED: Strip cijfers + number words uit normalized voor Levenshtein
        val normalizedNoNumbers = normalized.split("\\s+".toRegex())
            .filter { it.isNotBlank() && !isNumberToken(it) }
            .joinToString(" ")

        val tokens = normalizedNoNumbers.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val allowDist = when {
            tokens.isEmpty() -> 2
            tokens.size == 1 -> 2
            tokens.size == 2 -> 3
            else -> 4
        }

        for ((rec, _) in records) {
            if (rec.speciesid !in speciesIds) continue
            val recNorm = rec.norm
            val lev = levenshteinDistance(normalizedNoNumbers, recNorm)
            if (lev > allowDist) continue
            val textSim = 1.0 - (lev.toDouble() / max(normalizedNoNumbers.length, recNorm.length).toDouble())
            val phonSim = runCatching { ColognePhonetic.similarity(normalizedNoNumbers, recNorm) }.getOrDefault(0.0)
            val prior = computePrior(rec.speciesid, ctx)
            val score = (W_TEXT * textSim + W_PHON * phonSim + W_PRIOR * prior).coerceIn(0.0, 1.0)
            val (canonical, tilename) = ctx.speciesById[rec.speciesid] ?: (rec.canonical to rec.tilename)
            out += Candidate(rec.speciesid, tilename ?: canonical, score, rec.speciesid in ctx.tilesSpeciesIds, "fuzzy-alias")
        }
        return out
    }

    private fun computePrior(speciesId: String, ctx: MatchContext): Double {
        var prior = 0.0
        if (speciesId in ctx.recentIds) prior += PRIOR_RECENT
        if (speciesId in ctx.tilesSpeciesIds) prior += PRIOR_TILES
        if (speciesId in ctx.siteAllowedIds) prior += PRIOR_SITE
        return prior.coerceAtMost(0.6)
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val prev = IntArray(lb + 1) { it }
        val cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            val ai = a[i - 1]
            for (j in 1..lb) {
                val cost = if (ai == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, lb + 1)
        }
        return prev[lb]
    }

    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}