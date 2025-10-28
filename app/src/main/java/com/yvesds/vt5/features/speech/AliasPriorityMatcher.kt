package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * AliasPriorityMatcher (updated)
 *
 * - Priority cascade preserved (exact canonical/alias in tiles/site, fuzzy in tiles/site)
 * - Fuzzy scoring uses hybrid weights across:
 *     - text similarity (normalized Levenshtein)
 *     - cologne phonetic similarity
 *     - phoneme similarity (when available)
 * - No usage of minhash/simhash/ngrams/dmetapho/beidermorse
 */

object AliasPriorityMatcher {
    private const val TAG = "AliasPriorityMatcher"

    private const val AUTO_ACCEPT_THRESHOLD = 0.70
    private const val SUGGEST_THRESHOLD = 0.40
    private const val AUTO_ACCEPT_MARGIN = 0.12

    // Scoring weights (sum <= 1)
    private const val W_TEXT = 0.45
    private const val W_COLOGNE = 0.35
    private const val W_PHONEME = 0.20

    private const val PRIOR_RECENT = 0.25
    private const val PRIOR_TILES = 0.25
    private const val PRIOR_SITE = 0.15

    private fun isNumberToken(token: String): Boolean {
        return token.toIntOrNull() != null || NumberPatterns.parseNumberWord(token) != null
    }

    private fun parseAmountToken(token: String): Int? {
        return token.toIntOrNull() ?: NumberPatterns.parseNumberWord(token)
    }

    suspend fun match(
        hypothesis: String,
        matchContext: MatchContext,
        context: Context,
        saf: SaFStorageHelper
    ): MatchResult = withContext(Dispatchers.Default) {
        val hyp = hypothesis.trim()
        if (hyp.isBlank()) return@withContext MatchResult.NoMatch(hyp, "empty")

        AliasMatcher.ensureLoaded(context, saf)

        val normalized = normalizeLowerNoDiacritics(hyp)
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (tokens.isEmpty()) return@withContext MatchResult.NoMatch(hyp, "empty-after-norm")

        val matches = mutableListOf<MatchResult.MatchWithAmount>()
        var i = 0
        while (i < tokens.size) {
            val maxWindow = minOf(6, tokens.size - i)
            var matched = false

            // exact matching first (canonical/alias in tiles/site)
            for (w in maxWindow downTo 1) {
                val window = tokens.subList(i, i + w)
                if (window.all { isNumberToken(it) }) { i++; matched = true; break }
                if (window.any { NumberPatterns.isNumberWord(it) }) continue
                val phrase = window.joinToString(" ")
                val exact = tryExactMatch(phrase, matchContext, context, saf)
                if (exact != null) {
                    val nextIndex = i + w
                    var amount = 1
                    if (nextIndex < tokens.size) {
                        parseAmountToken(tokens[nextIndex])?.let { amt ->
                            amount = amt; i = nextIndex + 1
                        } ?: run { i = nextIndex }
                    } else i = nextIndex

                    matches += MatchResult.MatchWithAmount(exact.first, amount, exact.second)
                    matched = true; break
                }
            }

            if (!matched) {
                // fuzzy matching
                for (w in maxWindow downTo 1) {
                    val window = tokens.subList(i, i + w)
                    if (window.all { isNumberToken(it) }) { i++; matched = true; break }
                    if (window.any { NumberPatterns.isNumberWord(it) }) continue
                    val phrase = window.joinToString(" ")

                    val fuzzy = tryFuzzyMatch(phrase, matchContext, context, saf)
                    if (fuzzy != null) {
                        val nextIndex = i + w
                        var amount = 1
                        if (nextIndex < tokens.size) {
                            parseAmountToken(tokens[nextIndex])?.let { amt ->
                                amount = amt; i = nextIndex + 1
                            } ?: run { i = nextIndex }
                        } else i = nextIndex

                        matches += MatchResult.MatchWithAmount(fuzzy.first, amount, fuzzy.second)
                        matched = true; break
                    }
                }
            }

            if (!matched) i++
        }

        if (matches.isEmpty()) return@withContext MatchResult.NoMatch(hyp, "no-candidates")
        if (matches.size > 1) return@withContext MatchResult.MultiMatch(matches, hyp, "multi-species")

        val match = matches.first()
        return@withContext if (match.candidate.isInTiles) {
            MatchResult.AutoAccept(match.candidate, hyp, match.source, match.amount)
        } else {
            MatchResult.AutoAcceptAddPopup(match.candidate, hyp, match.source, match.amount)
        }
    }

    private suspend fun tryExactMatch(
        phrase: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): Pair<Candidate, String>? {
        val normalized = normalizeLowerNoDiacritics(phrase)
        val exactCanonicalTiles = findExactCanonicalInSet(normalized, ctx.tilesSpeciesIds, ctx)
        if (exactCanonicalTiles != null) return exactCanonicalTiles to "exact_canonical_tiles"
        val exactCanonicalSite = findExactCanonicalInSet(normalized, ctx.siteAllowedIds, ctx)
        if (exactCanonicalSite != null && !ctx.tilesSpeciesIds.contains(exactCanonicalSite.speciesId)) return exactCanonicalSite to "exact_canonical_site"
        val exactAliasTiles = findExactAliasInSet(normalized, ctx.tilesSpeciesIds, appContext, saf)
        if (exactAliasTiles != null) return exactAliasTiles to "exact_alias_tiles"
        val exactAliasSite = findExactAliasInSet(normalized, ctx.siteAllowedIds, appContext, saf)
        if (exactAliasSite != null && !ctx.tilesSpeciesIds.contains(exactAliasSite.speciesId)) return exactAliasSite to "exact_alias_site"
        return null
    }

    private suspend fun tryFuzzyMatch(
        phrase: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): Pair<Candidate, String>? {
        val normalized = normalizeLowerNoDiacritics(phrase)

        val fuzzyCandidates = findFuzzyCandidates(normalized, ctx, appContext, saf)
        if (fuzzyCandidates.isEmpty()) return null

        // compute hybrid score for each candidate
        val scored = fuzzyCandidates.map { rec ->
            val textSim = rec.textSim
            val cologneSim = rec.cologneSim
            val phonemeSim = rec.phonemeSim
            val prior = computePrior(rec.record.speciesid, ctx)
            val score = (W_TEXT * textSim + W_COLOGNE * cologneSim + W_PHONEME * phonemeSim + prior * 0.0).coerceIn(0.0, 1.0)
            Triple(rec, score, prior)
        }.sortedByDescending { it.second }

        val top = scored.first()
        if (top.second >= SUGGEST_THRESHOLD) {
            val candidate = Candidate(
                speciesId = top.first.record.speciesid,
                displayName = top.first.record.canonical,
                score = top.second,
                isInTiles = top.first.record.speciesid in ctx.tilesSpeciesIds
            )
            val source = if (candidate.isInTiles) "fuzzy_tiles" else "fuzzy_site"
            return candidate to source
        }
        return null
    }

    private suspend fun findFuzzyCandidates(
        normalized: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): List<FuzzyCandidate> = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        val results = mutableListOf<FuzzyCandidate>()

        // Use AliasMatcher to get shortlist
        val shortlist = AliasMatcher.findFuzzyCandidates(normalized, appContext, saf, topN = 50, threshold = 0.0)

        for ((rec, baseScore) in shortlist) {
            val textSim = 1.0 - (levenshteinDistance(normalized, rec.norm).toDouble() / max(normalized.length, rec.norm.length).toDouble())
            val cologneSim = runCatching { ColognePhonetic.similarity(normalized, rec.norm) }.getOrDefault(0.0)
            val phonemeSim = if (!rec.phonemes.isNullOrBlank()) {
                val qPh = runCatching { DutchPhonemizer.phonemize(normalized) }.getOrDefault("")
                runCatching { DutchPhonemizer.phonemeSimilarity(qPh, rec.phonemes) }.getOrDefault(0.0)
            } else 0.0

            results += FuzzyCandidate(rec, textSim, cologneSim, phonemeSim)
        }

        results
    }

    private fun computePrior(speciesId: String, ctx: MatchContext): Double {
        var prior = 0.0
        if (speciesId in ctx.recentIds) prior += PRIOR_RECENT
        if (speciesId in ctx.tilesSpeciesIds) prior += PRIOR_TILES
        if (speciesId in ctx.siteAllowedIds) prior += PRIOR_SITE
        return prior.coerceAtMost(0.6)
    }

    private data class FuzzyCandidate(
        val record: AliasRecord,
        val textSim: Double,
        val cologneSim: Double,
        val phonemeSim: Double
    )

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
        val lower = input.lowercase()
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}