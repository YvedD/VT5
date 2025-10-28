package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/**
 * AliasPriorityMatcher.kt
 *
 * PURPOSE:
 * 9-step priority cascade matcher with hybrid scoring (Text + Cologne + Phonemes).
 * Includes multi-token parsing for queries like "blauwe kiekendief vijf".
 *
 * ARCHITECTURE:
 * Priority Cascade (1-9):
 * 1. Exact canonical in tiles (instant accept)
 * 2. Exact canonical in site (add popup)
 * 3. Exact alias in tiles (instant accept)
 * 4. Exact alias in site (add popup)
 * 5. Fuzzy canonical in tiles (scored)
 * 6. Fuzzy canonical in site (scored)
 * 7. Fuzzy alias in tiles (scored)
 * 8. Fuzzy alias in site (scored)
 * 9. No match (raw log, user can tap to add alias)
 *
 * NEW FEATURES (v2.1):
 * - Multi-token sliding window (1-6 tokens)
 * - Dutch number word parsing ("vijf" → 5)
 * - Number word filtering (prevents "vijf" matching "Vink")
 * - Hybrid scoring: Text (45%) + Cologne (30%) + Phonemes (25%)
 * - MultiMatch support (e.g., "aalscholver 5 boertjes 3")
 *
 * SCORING WEIGHTS:
 * - W_TEXT = 0.45 (Levenshtein similarity)
 * - W_PHON = 0.30 (Cologne phonetic similarity)
 * - W_PRIOR = 0.25 (Context prior: tiles/site/recents)
 *
 * USAGE:
 * ```kotlin
 * val result = AliasPriorityMatcher.match(
 *     hypothesis = "blauwe kiekendief vijf",
 *     matchContext = buildMatchContext(),
 *     context = context,
 *     saf = saf
 * )
 *
 * when (result) {
 *     is MatchResult.AutoAccept ->
 *         updateCount(result.candidate.speciesId, result.amount)
 *     is MatchResult.MultiMatch ->
 *         result.matches.forEach { updateCount(it.candidate.speciesId, it.amount) }
 *     // ... etc
 * }
 * ```
 *
 * AUTHOR: VT5 Team (YvedD)
 * DATE: 2025-10-28
 * VERSION: 2.1
 */
object AliasPriorityMatcher {
    private const val TAG = "AliasPriorityMatcher"

    /*═══════════════════════════════════════════════════════════════════════
     * SCORING PARAMETERS
     *═══════════════════════════════════════════════════════════════════════*/

    // Score thresholds
    private const val AUTO_ACCEPT_THRESHOLD = 0.70   // Auto-accept if score >= 0.70
    private const val SUGGEST_THRESHOLD = 0.40       // Show suggestions if score >= 0.40
    private const val AUTO_ACCEPT_MARGIN = 0.12      // Top must beat 2nd by 0.12 for auto-accept

    // Hybrid scoring weights (sum = 1.0)
    private const val W_TEXT = 0.45   // Levenshtein text similarity
    private const val W_PHON = 0.30   // Cologne phonetic similarity
    private const val W_PRIOR = 0.25  // Context prior (tiles/site/recents)

    // Prior scoring components
    private const val PRIOR_RECENT = 0.25  // Recently used species
    private const val PRIOR_TILES = 0.25   // In current tiles
    private const val PRIOR_SITE = 0.15    // Allowed at current site

    /*═══════════════════════════════════════════════════════════════════════
     * DUTCH NUMBER WORD PARSING (integrated with NumberPatterns)
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Check if token is a number (digit or Dutch word)
     */
    private fun isNumberToken(token: String): Boolean {
        return token.toIntOrNull() != null || NumberPatterns.parseNumberWord(token) != null
    }

    /**
     * Parse token as number (digit or Dutch word)
     *
     * Examples:
     * - "5" → 5
     * - "vijf" → 5
     * - "vogel" → null
     */
    private fun parseAmountToken(token: String): Int? {
        return token.toIntOrNull() ?: NumberPatterns.parseNumberWord(token)
    }

    /*═══════════════════════════════════════════════════════════════════════
     * MAIN MATCH FUNCTION (Multi-token with sliding window)
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Match hypothesis against aliases with priority cascade
     *
     * NEW (v2.1): Multi-token sliding window support
     *
     * Input: "blauwe kiekendief vijf"
     * Tokens: ["blauwe", "kiekendief", "vijf"]
     *
     * Sliding window (6 → 1 tokens):
     * 1. Try ["blauwe", "kiekendief", "vijf"] (3 tokens) → has number, skip
     * 2. Try ["blauwe", "kiekendief"] (2 tokens) → MATCH! Blauwe Kiekendief
     * 3. Next token "vijf" → parseAmountToken("vijf") = 5
     *
     * Result: MatchResult.AutoAccept(Blauwe Kiekendief, amount=5)
     *
     * @param hypothesis Raw ASR hypothesis (normalized)
     * @param matchContext Context with tiles/site/recents
     * @param context Application context
     * @param saf SAF helper
     * @return MatchResult (AutoAccept, MultiMatch, SuggestionList, or NoMatch)
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

        // Ensure alias index loaded
        try {
            AliasMatcher.ensureLoaded(context, saf)
        } catch (ex: Exception) {
            Log.w(TAG, "Alias index ensureLoaded failed: ${ex.message}", ex)
        }

        // Normalize and tokenize
        val normalized = normalizeLowerNoDiacritics(hyp)
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            return@withContext MatchResult.NoMatch(hyp, "empty-after-norm")
        }

        /*───────────────────────────────────────────────────────────────────
         * MULTI-TOKEN SLIDING WINDOW MATCHING
         *
         * Algorithm:
         * 1. Iterate through tokens (i = 0 to size-1)
         * 2. For each position, try windows of size 6 → 1 (longest first)
         * 3. Skip windows containing ONLY numbers
         * 4. Skip windows containing ANY number words (prevents "vijf"→"Vink")
         * 5. Try exact match → fuzzy match
         * 6. If match found, extract amount from next token
         * 7. Continue to next unmatched position
         *───────────────────────────────────────────────────────────────────*/

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

                // Skip if window contains ANY number words (prevents "vijf" → "Vink")
                val hasNumberWord = windowTokens.any { NumberPatterns.isNumberWord(it) }
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
                    val hasNumberWord = windowTokens.any { NumberPatterns.isNumberWord(it) }
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

        /*───────────────────────────────────────────────────────────────────
         * CONVERT MATCHES TO MATCHRESULT
         *───────────────────────────────────────────────────────────────────*/

        if (matches.isEmpty()) {
            return@withContext MatchResult.NoMatch(hyp, "no-candidates")
        }

        // If we have multiple matches, return as MultiMatch (NEW in v2.1!)
        if (matches.size > 1) {
            return@withContext MatchResult.MultiMatch(matches, hyp, "multi-species")
        }

        // Single match: use existing logic
        val match = matches.first()
        return@withContext if (match.candidate.isInTiles) {
            MatchResult.AutoAccept(match.candidate, hyp, match.source, match.amount)
        } else {
            MatchResult.AutoAcceptAddPopup(match.candidate, hyp, match.source, match.amount)
        }
    }

    /*═══════════════════════════════════════════════════════════════════════
     * EXACT MATCH (Steps 1-4)
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Try exact match (canonical or alias in tiles/site)
     *
     * Steps:
     * 1. Exact canonical in tiles → instant accept
     * 2. Exact canonical in site (not tiles) → add popup
     * 3. Exact alias in tiles → instant accept
     * 4. Exact alias in site (not tiles) → add popup
     *
     * @return Pair(Candidate, source) or null if no exact match
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

    /*═══════════════════════════════════════════════════════════════════════
     * FUZZY MATCH (Steps 5-8) with HYBRID SCORING
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Try fuzzy match with hybrid scoring
     *
     * NEW (v2.1): Hybrid scoring with Cologne + Phonemes
     *
     * Scoring Formula:
     * score = W_TEXT × textSim + W_PHON × cologneSim + W_PHONEME × phonemeSim + W_PRIOR × prior
     *
     * Where:
     * - textSim: Levenshtein similarity (0.0-1.0)
     * - cologneSim: Cologne phonetic similarity (0.0-1.0)
     * - phonemeSim: IPA phoneme similarity (0.0-1.0, if available)
     * - prior: Context prior (tiles/site/recents)
     *
     * @return Pair(Candidate, source) or null if score too low
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

    /*═══════════════════════════════════════════════════════════════════════
     * HELPER METHODS
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Find exact canonical match in species set
     */
    private fun findExactCanonicalInSet(
        normalized: String,
        speciesIds: Set<String>,
        ctx: MatchContext
    ): Candidate? {
        for (sid in speciesIds) {
            val (canonical, tilename) = ctx.speciesById[sid] ?: continue
            val canonNorm = normalizeLowerNoDiacritics(canonical)
            if (canonNorm == normalized) {
                return Candidate(sid, tilename ?: canonical, 1.0, sid in ctx.tilesSpeciesIds, "canonical-exact")
            }
        }
        return null
    }

    /**
     * Find exact alias match in species set
     */
    private suspend fun findExactAliasInSet(
        normalized: String,
        speciesIds: Set<String>,
        appContext: Context,
        saf: SaFStorageHelper
    ): Candidate? {
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

    /**
     * Find fuzzy candidates (steps 5-8) with NUMBER FILTERING
     */
    private suspend fun findFuzzyCandidates(
        normalized: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): List<Candidate> {
        val accum = mutableListOf<Candidate>()

        // Fuzzy canonical in tiles
        accum += fuzzyCanonicalInSet(normalized, ctx.tilesSpeciesIds, ctx)

        // Fuzzy alias in tiles (with number filtering!)
        accum += fuzzyAliasInSet(normalized, ctx.tilesSpeciesIds, ctx, appContext, saf)

        // Fuzzy canonical in site (not tiles)
        val siteOnly = ctx.siteAllowedIds - ctx.tilesSpeciesIds
        accum += fuzzyCanonicalInSet(normalized, siteOnly, ctx)

        // Fuzzy alias in site (with number filtering!)
        accum += fuzzyAliasInSet(normalized, siteOnly, ctx, appContext, saf)

        // Dedupe: keep best score per species
        val byId = linkedMapOf<String, Candidate>()
        for (c in accum) {
            val ex = byId[c.speciesId]
            if (ex == null || c.score > ex.score) byId[c.speciesId] = c
        }

        return byId.values.toList()
    }

    /**
     * Fuzzy canonical matching (no phonemes, simple Cologne + Levenshtein)
     */
    private fun fuzzyCanonicalInSet(
        normalized: String,
        speciesIds: Set<String>,
        ctx: MatchContext
    ): List<Candidate> {
        // Strip numbers from query for matching
        val tokens = normalized.split("\\s+".toRegex())
            .filter { it.isNotBlank() && !isNumberToken(it) }

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

    /**
     * Fuzzy alias matching with HYBRID SCORING (Text + Cologne + Phonemes)
     *
     * NEW (v2.1): Number filtering + phoneme scoring
     */
    private suspend fun fuzzyAliasInSet(
        normalized: String,
        speciesIds: Set<String>,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): List<Candidate> {
        // Get fuzzy candidates from AliasMatcher
        val records = AliasMatcher.findFuzzyCandidates(normalized, appContext, saf, topN = 50, threshold = 0.0)

        // FILTER OUT NUMBER WORDS (prevents "vijf" → "Vink"!)
        val filteredRecords = NumberPatterns.filterNumberCandidates(records.map { it.first })

        if (filteredRecords.isEmpty()) return emptyList()

        val out = mutableListOf<Candidate>()

        // Strip numbers from query for matching
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

        for (rec in filteredRecords) {
            if (rec.speciesid !in speciesIds) continue

            val recNorm = rec.norm

            // Layer 1: Text similarity (Levenshtein)
            val lev = levenshteinDistance(normalizedNoNumbers, recNorm)
            if (lev > allowDist) continue

            val textSim = 1.0 - (lev.toDouble() / max(normalizedNoNumbers.length, recNorm.length).toDouble())

            // Layer 2: Cologne phonetic similarity
            val cologneSim = runCatching {
                ColognePhonetic.similarity(normalizedNoNumbers, recNorm)
            }.getOrDefault(0.0)

            // Layer 3: IPA phoneme similarity (if available) - NEW!
            val phonemeSim = if (rec.phonemes != null) {
                val queryPhonemes = DutchPhonemizer.phonemize(normalizedNoNumbers)
                DutchPhonemizer.phonemeSimilarity(queryPhonemes, rec.phonemes)
            } else 0.0

            // Hybrid scoring with phonemes
            val prior = computePrior(rec.speciesid, ctx)

            val score = if (rec.phonemes != null) {
                // All 3 layers available
                (W_TEXT * textSim + W_PHON * cologneSim + 0.25 * phonemeSim + W_PRIOR * prior * 0.75).coerceIn(0.0, 1.0)
            } else {
                // Fallback: text + cologne only
                (W_TEXT * textSim + W_PHON * cologneSim + W_PRIOR * prior).coerceIn(0.0, 1.0)
            }

            val (canonical, tilename) = ctx.speciesById[rec.speciesid] ?: (rec.canonical to rec.tilename)
            out += Candidate(rec.speciesid, tilename ?: canonical, score, rec.speciesid in ctx.tilesSpeciesIds, "fuzzy-alias-hybrid")
        }

        return out
    }

    /**
     * Compute context prior (tiles/site/recents boost)
     */
    private fun computePrior(speciesId: String, ctx: MatchContext): Double {
        var prior = 0.0
        if (speciesId in ctx.recentIds) prior += PRIOR_RECENT
        if (speciesId in ctx.tilesSpeciesIds) prior += PRIOR_TILES
        if (speciesId in ctx.siteAllowedIds) prior += PRIOR_SITE
        return prior.coerceAtMost(0.6)
    }

    /**
     * Levenshtein distance (standard edit distance)
     */
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

    /**
     * Normalize text (lowercase, no diacritics, single spaces)
     */
    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}