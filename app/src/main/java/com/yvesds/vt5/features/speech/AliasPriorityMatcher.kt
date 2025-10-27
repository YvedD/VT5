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
 * OPTIMIZED: Timing logs rond hotspots, verlaagde thresholds voor meer accuracy (SUGGEST_THRESHOLD naar 0.40).
 */
object AliasPriorityMatcher {
    private const val TAG = "AliasPriorityMatcher"

    // Score thresholds / params - TUNED for better accuracy in field
    private const val AUTO_ACCEPT_THRESHOLD = 0.70
    private const val SUGGEST_THRESHOLD = 0.40  // lowered for more suggestions
    private const val AUTO_ACCEPT_MARGIN = 0.12

    // Weights for combined score
    private const val W_TEXT = 0.45
    private const val W_PHON = 0.30
    private const val W_PRIOR = 0.25

    // Prior components
    private const val PRIOR_RECENT = 0.25
    private const val PRIOR_TILES = 0.25
    private const val PRIOR_SITE = 0.15

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

        // 1-4 exact checks
        val exactCanonicalTiles = findExactCanonicalInSet(normalized, matchContext.tilesSpeciesIds, matchContext)
        if (exactCanonicalTiles != null) {
            val t1 = System.nanoTime()
            Log.d(TAG, "match: early exact, timeMs=${(t1 - t0) / 1_000_000}")
            return@withContext MatchResult.AutoAccept(exactCanonicalTiles, hyp, "exact_canonical_tiles")
        }

        val exactCanonicalSite = findExactCanonicalInSet(normalized, matchContext.siteAllowedIds, matchContext)
        if (exactCanonicalSite != null && !matchContext.tilesSpeciesIds.contains(exactCanonicalSite.speciesId)) {
            val t1 = System.nanoTime()
            Log.d(TAG, "match: early exact site, timeMs=${(t1 - t0) / 1_000_000}")
            return@withContext MatchResult.AutoAcceptAddPopup(exactCanonicalSite, hyp, "exact_canonical_site")
        }

        val exactAliasTiles = findExactAliasInSet(normalized, matchContext.tilesSpeciesIds, context, saf)
        if (exactAliasTiles != null) {
            val t1 = System.nanoTime()
            Log.d(TAG, "match: early exact alias tiles, timeMs=${(t1 - t0) / 1_000_000}")
            return@withContext MatchResult.AutoAccept(exactAliasTiles, hyp, "exact_alias_tiles")
        }

        val exactAliasSite = findExactAliasInSet(normalized, matchContext.siteAllowedIds, context, saf)
        if (exactAliasSite != null && !matchContext.tilesSpeciesIds.contains(exactAliasSite.speciesId)) {
            val t1 = System.nanoTime()
            Log.d(TAG, "match: early exact alias site, timeMs=${(t1 - t0) / 1_000_000}")
            return@withContext MatchResult.AutoAcceptAddPopup(exactAliasSite, hyp, "exact_alias_site")
        }

        // 5-8 fuzzy
        val fuzzyCandidates = findFuzzyCandidates(normalized, matchContext, context, saf)
        if (fuzzyCandidates.isEmpty()) {
            val t1 = System.nanoTime()
            Log.d(TAG, "match: no fuzzy candidates, timeMs=${(t1 - t0) / 1_000_000}")
            return@withContext MatchResult.NoMatch(hyp, "no-candidates")
        }

        val sorted = fuzzyCandidates.sortedByDescending { it.score }
        val top = sorted.first()
        val second = sorted.getOrNull(1)
        val margin = if (second != null) top.score - second.score else Double.POSITIVE_INFINITY

        if (top.score >= AUTO_ACCEPT_THRESHOLD && margin >= AUTO_ACCEPT_MARGIN) {
            val t1 = System.nanoTime()
            Log.d(TAG, "match: fuzzy auto tiles, timeMs=${(t1 - t0) / 1_000_000}")
            return@withContext if (top.isInTiles) MatchResult.AutoAccept(top, hyp, "fuzzy_auto_tiles")
            else MatchResult.AutoAcceptAddPopup(top, hyp, "fuzzy_auto_site")
        }

        if (top.score >= AUTO_ACCEPT_THRESHOLD) {
            val t1 = System.nanoTime()
            Log.d(TAG, "match: fuzzy close, timeMs=${(t1 - t0) / 1_000_000}")
            return@withContext MatchResult.SuggestionList(sorted.take(5), hyp, "fuzzy_close")
        }

        if (top.score >= SUGGEST_THRESHOLD) {
            val t1 = System.nanoTime()
            Log.d(TAG, "match: fuzzy suggestions, timeMs=${(t1 - t0) / 1_000_000}")
            return@withContext MatchResult.SuggestionList(sorted.take(5), hyp, "fuzzy_suggestions")
        }

        val t1 = System.nanoTime()
        Log.d(TAG, "match: no match, timeMs=${(t1 - t0) / 1_000_000}")
        return@withContext MatchResult.NoMatch(hyp, "low_score")
    }

    // helpers (as in earlier implementation)...
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
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val allowDist = when {
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
        for ((rec, _) in records) {
            if (rec.speciesid !in speciesIds) continue
            val recNorm = rec.norm
            val lev = levenshteinDistance(normalized, recNorm)
            val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }
            val allowDist = when {
                tokens.size == 1 -> 2
                tokens.size == 2 -> 3
                else -> 4
            }
            if (lev > allowDist) continue
            val textSim = 1.0 - (lev.toDouble() / max(normalized.length, recNorm.length).toDouble())
            val phonSim = runCatching { ColognePhonetic.similarity(normalized, recNorm) }.getOrDefault(0.0)
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