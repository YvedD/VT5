package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/**
 * AliasPriorityMatcher
 *
 * Implements the exact 9-step priority cascade from the opdracht:
 *
 * 1. Exact canonical in tiles → AutoAccept + count
 * 2. Exact canonical in site_species → AutoAccept + popup "Toevoegen aan telbord?"
 * 3. Exact alias in tiles (local alias) → AutoAccept + count
 * 4. Exact alias in site_species (global alias) → AutoAccept + popup
 * 5. Fuzzy canonical in tiles → score; if ≥ 0.70 && margin ≥ 0.12 → AutoAccept (popup if not in tiles)
 * 6. Fuzzy alias in tiles → same scoring
 * 7. Fuzzy canonical in site_allowed (global) → same scoring
 * 8. Fuzzy alias in site_allowed (global) → same scoring
 * 9. NoMatch → SuggestionList (top 3–5)
 *
 * Fuzzy scoring:
 * - textSim = 1 - (levenshtein / maxLen) with token-based distance adjustment
 * - phonSim = 1.0 if exact phonetic match else 0.0
 * - prior = 0.25*(in recents) + 0.25*(in tiles) + 0.15*(in site) max 0.6
 * - combined_score = 0.45*textSim + 0.30*phonSim + 0.25*prior
 *
 * Auto-accept rules:
 * - score ≥ 0.70 && margin ≥ 0.12 → AutoAccept
 * - 0.45 ≤ score < 0.70 → Suggestion
 * - score < 0.45 → NoMatch
 */
object AliasPriorityMatcher {
    private const val TAG = "AliasPriorityMatcher"

    // Scoring weights
    private const val WEIGHT_TEXT = 0.45
    private const val WEIGHT_PHON = 0.30
    private const val WEIGHT_PRIOR = 0.25

    // Prior weights
    private const val PRIOR_RECENT = 0.25
    private const val PRIOR_TILES = 0.25
    private const val PRIOR_SITE = 0.15

    // Thresholds
    private const val THRESHOLD_AUTO_ACCEPT = 0.70
    private const val THRESHOLD_MARGIN = 0.12
    private const val THRESHOLD_SUGGESTION = 0.45

    /**
     * Match a single hypothesis against the alias index with full context.
     * Returns MatchResult with priority cascade logic applied.
     */
    suspend fun match(
        hypothesis: String,
        context: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): MatchResult = withContext(Dispatchers.Default) {
        val normalized = normalizeLowerNoDiacritics(hypothesis)
        if (normalized.isBlank()) {
            return@withContext MatchResult.NoMatch(hypothesis)
        }

        // Ensure alias index loaded
        AliasMatcher.ensureLoaded(appContext, saf)

        // Step 1: Exact canonical in tiles
        val exactCanonicalTiles = findExactCanonicalInSet(normalized, context.tilesSpeciesIds, context)
        if (exactCanonicalTiles != null) {
            return@withContext MatchResult.AutoAccept(
                candidate = exactCanonicalTiles,
                hypothesis = hypothesis,
                source = "exact_canonical_tiles"
            )
        }

        // Step 2: Exact canonical in site_species
        val exactCanonicalSite = findExactCanonicalInSet(normalized, context.siteAllowedIds, context)
        if (exactCanonicalSite != null && exactCanonicalSite.speciesId !in context.tilesSpeciesIds) {
            return@withContext MatchResult.AutoAcceptAddPopup(
                candidate = exactCanonicalSite,
                hypothesis = hypothesis,
                source = "exact_canonical_site"
            )
        }

        // Step 3: Exact alias in tiles (via alias index)
        val exactAliasTiles = findExactAliasInSet(normalized, context.tilesSpeciesIds, appContext, saf)
        if (exactAliasTiles != null) {
            return@withContext MatchResult.AutoAccept(
                candidate = exactAliasTiles,
                hypothesis = hypothesis,
                source = "exact_alias_tiles"
            )
        }

        // Step 4: Exact alias in site_species
        val exactAliasSite = findExactAliasInSet(normalized, context.siteAllowedIds, appContext, saf)
        if (exactAliasSite != null && exactAliasSite.speciesId !in context.tilesSpeciesIds) {
            return@withContext MatchResult.AutoAcceptAddPopup(
                candidate = exactAliasSite,
                hypothesis = hypothesis,
                source = "exact_alias_site"
            )
        }

        // Steps 5–8: Fuzzy matching (tiles → site, canonical → alias)
        val fuzzyCandidates = findFuzzyCandidates(normalized, context, appContext, saf)

        if (fuzzyCandidates.isEmpty()) {
            return@withContext MatchResult.NoMatch(hypothesis)
        }

        // Sort by score descending
        val sorted = fuzzyCandidates.sortedByDescending { it.score }
        val top = sorted.first()
        val margin = if (sorted.size >= 2) top.score - sorted[1].score else 1.0

        when {
            // Auto-accept if high score + margin
            top.score >= THRESHOLD_AUTO_ACCEPT && margin >= THRESHOLD_MARGIN -> {
                if (top.speciesId in context.tilesSpeciesIds) {
                    MatchResult.AutoAccept(top, hypothesis, "fuzzy_auto_tiles")
                } else {
                    MatchResult.AutoAcceptAddPopup(top, hypothesis, "fuzzy_auto_site")
                }
            }
            // Suggestion list if medium score
            top.score >= THRESHOLD_SUGGESTION -> {
                MatchResult.SuggestionList(sorted.take(5), hypothesis, "fuzzy_suggestions")
            }
            // No match if low score
            else -> MatchResult.NoMatch(hypothesis)
        }
    }

    /**
     * Find exact canonical match in a given species set.
     */
    private fun findExactCanonicalInSet(
        normalized: String,
        speciesIds: Set<String>,
        context: MatchContext
    ): Candidate? {
        for (sid in speciesIds) {
            val (canonical, tilename) = context.speciesById[sid] ?: continue
            val canonNorm = normalizeLowerNoDiacritics(canonical)
            if (canonNorm == normalized) {
                val prior = computePrior(sid, context)
                return Candidate(
                    speciesId = sid,
                    displayName = tilename ?: canonical,
                    aliasText = canonical,
                    score = 1.0,
                    textSim = 1.0,
                    phonSim = 1.0,
                    prior = prior,
                    isInTiles = sid in context.tilesSpeciesIds
                )
            }
        }
        return null
    }

    /**
     * Find exact alias match in a given species set (via alias index).
     */
    private suspend fun findExactAliasInSet(
        normalized: String,
        speciesIds: Set<String>,
        appContext: Context,
        saf: SaFStorageHelper
    ): Candidate? {
        val records = AliasMatcher.findExact(normalized, appContext, saf)
        for (record in records) {
            if (record.speciesid in speciesIds) {
                val (canonical, tilename) = speciesIds
                    .firstOrNull { it == record.speciesid }
                    ?.let { sid ->
                        // Lookup via AliasMatcher's loaded index or fallback
                        record.canonical to record.tilename
                    } ?: continue

                return Candidate(
                    speciesId = record.speciesid,
                    displayName = tilename ?: canonical,
                    aliasText = record.alias,
                    score = 1.0,
                    textSim = 1.0,
                    phonSim = 1.0,
                    prior = computePrior(record.speciesid, MatchContext.empty()), // context not needed for exact
                    isInTiles = record.speciesid in speciesIds
                )
            }
        }
        return null
    }

    /**
     * Find fuzzy candidates across tiles + site, canonical + alias.
     * Priority order: tiles canonical → tiles alias → site canonical → site alias.
     */
    private suspend fun findFuzzyCandidates(
        normalized: String,
        context: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()

        // Step 5: Fuzzy canonical in tiles
        candidates += fuzzyCanonicalInSet(normalized, context.tilesSpeciesIds, context)

        // Step 6: Fuzzy alias in tiles
        candidates += fuzzyAliasInSet(normalized, context.tilesSpeciesIds, context, appContext, saf)

        // Step 7: Fuzzy canonical in site_allowed
        val siteOnly = context.siteAllowedIds - context.tilesSpeciesIds
        candidates += fuzzyCanonicalInSet(normalized, siteOnly, context)

        // Step 8: Fuzzy alias in site_allowed
        candidates += fuzzyAliasInSet(normalized, siteOnly, context, appContext, saf)

        return candidates.distinctBy { it.speciesId }
    }

    /**
     * Fuzzy canonical matching in a species set.
     */
    private fun fuzzyCanonicalInSet(
        normalized: String,
        speciesIds: Set<String>,
        context: MatchContext
    ): List<Candidate> {
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val allowDist = when {
            tokens.size == 1 -> 2
            tokens.size == 2 -> 3
            else -> 4
        }

        return speciesIds.mapNotNull { sid ->
            val (canonical, tilename) = context.speciesById[sid] ?: return@mapNotNull null
            val canonNorm = normalizeLowerNoDiacritics(canonical)

            val lev = levenshteinDistance(normalized, canonNorm)
            if (lev > allowDist) return@mapNotNull null

            val textSim = 1.0 - (lev.toDouble() / max(normalized.length, canonNorm.length).toDouble())
            val phonSim = phoneticSimilarity(normalized, canonNorm)
            val prior = computePrior(sid, context)
            val score = WEIGHT_TEXT * textSim + WEIGHT_PHON * phonSim + WEIGHT_PRIOR * prior

            Candidate(
                speciesId = sid,
                displayName = tilename ?: canonical,
                aliasText = canonical,
                score = score,
                textSim = textSim,
                phonSim = phonSim,
                prior = prior,
                isInTiles = sid in context.tilesSpeciesIds
            )
        }
    }

    /**
     * Fuzzy alias matching in a species set (via alias index).
     */
    private suspend fun fuzzyAliasInSet(
        normalized: String,
        speciesIds: Set<String>,
        context: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): List<Candidate> {
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val allowDist = when {
            tokens.size == 1 -> 2
            tokens.size == 2 -> 3
            else -> 4
        }

        val fuzzyRecords = AliasMatcher.findFuzzyCandidates(normalized, appContext, saf, topN = 20, threshold = 0.0)

        return fuzzyRecords.mapNotNull { (record, _) ->
            if (record.speciesid !in speciesIds) return@mapNotNull null

            val lev = levenshteinDistance(normalized, record.norm)
            if (lev > allowDist) return@mapNotNull null

            val textSim = 1.0 - (lev.toDouble() / max(normalized.length, record.norm.length).toDouble())
            val phonSim = phoneticSimilarity(normalized, record.norm)
            val prior = computePrior(record.speciesid, context)
            val score = WEIGHT_TEXT * textSim + WEIGHT_PHON * phonSim + WEIGHT_PRIOR * prior

            val (canonical, tilename) = context.speciesById[record.speciesid] ?: (record.canonical to record.tilename)

            Candidate(
                speciesId = record.speciesid,
                displayName = tilename ?: canonical,
                aliasText = record.alias,
                score = score,
                textSim = textSim,
                phonSim = phonSim,
                prior = prior,
                isInTiles = record.speciesid in context.tilesSpeciesIds
            )
        }
    }

    /**
     * Compute prior score: 0.25*(in recents) + 0.25*(in tiles) + 0.15*(in site) max 0.6.
     */
    private fun computePrior(speciesId: String, context: MatchContext): Double {
        var prior = 0.0
        if (speciesId in context.recentIds) prior += PRIOR_RECENT
        if (speciesId in context.tilesSpeciesIds) prior += PRIOR_TILES
        if (speciesId in context.siteAllowedIds) prior += PRIOR_SITE
        return prior.coerceAtMost(0.6)
    }

    /**
     * Phonetic similarity: 1.0 if exact phonetic code match, else 0.0.
     */
    private fun phoneticSimilarity(s1: String, s2: String): Double {
        val p1 = ColognePhonetic.encode(s1)
        val p2 = ColognePhonetic.encode(s2)
        return if (p1 == p2) 1.0 else 0.0
    }

    /**
     * Levenshtein distance implementation.
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
     * Normalization (matching PrecomputeAliasIndex).
     */
    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}

/**
 * Candidate: scored candidate for a match.
 */
data class Candidate(
    val speciesId: String,
    val displayName: String,
    val aliasText: String,
    val score: Double,
    val textSim: Double,
    val phonSim: Double,
    val prior: Double,
    val isInTiles: Boolean
)

/**
 * MatchResult: sealed class for match outcomes.
 */
sealed class MatchResult {
    abstract val hypothesis: String

    /**
     * Auto-accept: species in tiles, increment count directly.
     */
    data class AutoAccept(
        val candidate: Candidate,
        override val hypothesis: String,
        val source: String
    ) : MatchResult()

    /**
     * Auto-accept + popup: species not in tiles, show "Toevoegen aan telbord?" popup.
     */
    data class AutoAcceptAddPopup(
        val candidate: Candidate,
        override val hypothesis: String,
        val source: String
    ) : MatchResult()

    /**
     * Suggestion list: show top 3–5 candidates in bottom-sheet.
     */
    data class SuggestionList(
        val candidates: List<Candidate>,
        override val hypothesis: String,
        val source: String
    ) : MatchResult()

    /**
     * No match: show in speech log, user can tap to add alias.
     */
    data class NoMatch(
        override val hypothesis: String
    ) : MatchResult()
}