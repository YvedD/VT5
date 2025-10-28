package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * AliasPriorityMatcher (fixed)
 *
 * - Priority cascade preserved (exact canonical/alias in tiles/site, fuzzy in tiles/site)
 * - Fuzzy scoring uses hybrid weights across:
 *     - text similarity (normalized Levenshtein)
 *     - cologne phonetic similarity
 *     - phoneme similarity (when available)
 * - Uses AliasMatcher shortlist generation
 * - Candidate objects now always include a `source` field; helper findExact* functions
 *   set candidate.source appropriately so there is no missing-parameter error.
 */

object AliasPriorityMatcher {
    private const val TAG = "AliasPriorityMatcher"

    private const val SUGGEST_THRESHOLD = 0.40

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

        // canonical in tiles
        findExactCanonicalInSet(normalized, ctx.tilesSpeciesIds, "exact_canonical_tiles", ctx, appContext, saf)?.let {
            return it to "exact_canonical_tiles"
        }

        // canonical in site
        findExactCanonicalInSet(normalized, ctx.siteAllowedIds, "exact_canonical_site", ctx, appContext, saf)?.let { cand ->
            if (!ctx.tilesSpeciesIds.contains(cand.speciesId)) return cand to "exact_canonical_site"
        }

        // alias in tiles
        findExactAliasInSet(normalized, ctx.tilesSpeciesIds, "exact_alias_tiles", ctx, appContext, saf)?.let {
            return it to "exact_alias_tiles"
        }

        // alias in site
        findExactAliasInSet(normalized, ctx.siteAllowedIds, "exact_alias_site", ctx, appContext, saf)?.let { cand ->
            if (!ctx.tilesSpeciesIds.contains(cand.speciesId)) return cand to "exact_alias_site"
        }

        return null
    }

    // Return Candidate? if phrase exactly matches canonical name in the allowed set
    private suspend fun findExactCanonicalInSet(
        normalized: String,
        allowed: Set<String>,
        sourceTag: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): Candidate? = withContext(Dispatchers.Default) {
        val nameMap = ctx.speciesById
        for (sid in allowed) {
            val info = nameMap[sid] ?: continue
            val canon = info.first
            if (normalizeLowerNoDiacritics(canon) == normalized) {
                return@withContext Candidate(
                    speciesId = sid,
                    displayName = canon,
                    score = 1.0,
                    isInTiles = sid in ctx.tilesSpeciesIds,
                    source = sourceTag
                )
            }
        }
        return@withContext null
    }

    // Return Candidate? if phrase exactly matches any alias in allowed set (consult AliasMatcher)
    private suspend fun findExactAliasInSet(
        normalized: String,
        allowed: Set<String>,
        sourceTag: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): Candidate? = withContext(Dispatchers.Default) {
        val records = AliasMatcher.findExact(normalized, appContext, saf)
        if (records.isEmpty()) return@withContext null
        for (r in records) {
            if (r.speciesid in allowed) {
                val display = r.canonical
                return@withContext Candidate(
                    speciesId = r.speciesid,
                    displayName = display,
                    score = 1.0,
                    isInTiles = r.speciesid in ctx.tilesSpeciesIds,
                    source = sourceTag
                )
            }
        }
        return@withContext null
    }

    private suspend fun tryFuzzyMatch(
        phrase: String,
        ctx: MatchContext,
        appContext: Context,
        saf: SaFStorageHelper
    ): Pair<Candidate, String>? {
        val normalized = normalizeLowerNoDiacritics(phrase)
        val shortlist = AliasMatcher.findFuzzyCandidates(normalized, appContext, saf, topN = 50, threshold = 0.0)
        if (shortlist.isEmpty()) return null

        // compute hybrid score for each candidate and pick top
        val scored = shortlist.mapNotNull { pair ->
            val rec = pair.first
            val textSim = if (rec.norm.isNotBlank()) normalizedLevenshteinRatio(normalized, rec.norm) else 0.0
            val cologneSim = runCatching { ColognePhonetic.similarity(normalized, rec.norm) }.getOrDefault(0.0)
            val phonemeSim = if (!rec.phonemes.isNullOrBlank()) {
                val qPh = runCatching { DutchPhonemizer.phonemize(normalized) }.getOrDefault("")
                runCatching { DutchPhonemizer.phonemeSimilarity(qPh, rec.phonemes) }.getOrDefault(0.0)
            } else 0.0

            val prior = computePrior(rec.speciesid, ctx)
            val score = (W_TEXT * textSim + W_COLOGNE * cologneSim + W_PHONEME * phonemeSim + prior * 0.0).coerceIn(0.0, 1.0)
            Triple(rec, score, prior)
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return null
        val top = scored.first()
        if (top.second >= SUGGEST_THRESHOLD) {
            val candidate = Candidate(
                speciesId = top.first.speciesid,
                displayName = top.first.canonical,
                score = top.second,
                isInTiles = top.first.speciesid in ctx.tilesSpeciesIds,
                source = if (top.first.speciesid in ctx.tilesSpeciesIds) "fuzzy_tiles" else "fuzzy_site"
            )
            val source = candidate.source
            return candidate to source
        }
        return null
    }

    private fun computePrior(speciesId: String, ctx: MatchContext): Double {
        var prior = 0.0
        if (speciesId in ctx.recentIds) prior += PRIOR_RECENT
        if (speciesId in ctx.tilesSpeciesIds) prior += PRIOR_TILES
        if (speciesId in ctx.siteAllowedIds) prior += PRIOR_SITE
        return prior.coerceAtMost(0.6)
    }

    private fun normalizedLevenshteinRatio(s1: String, s2: String): Double {
        val d = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (d.toDouble() / maxLen.toDouble())
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
        val lower = input.lowercase()
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}