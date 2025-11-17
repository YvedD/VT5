package com.yvesds.vt5.features.speech.helpers

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.speech.AliasMatcher
import com.yvesds.vt5.features.speech.AliasPriorityMatcher
import com.yvesds.vt5.features.speech.Candidate
import com.yvesds.vt5.features.speech.MatchContext
import com.yvesds.vt5.features.speech.MatchResult
import com.yvesds.vt5.utils.TextUtils
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * HeavyPathMatcher
 *
 * Performs heavy fuzzy matching using AliasPriorityMatcher with phonetic algorithms.
 * Combines ASR confidence with matcher scores for optimal results.
 *
 * Features:
 * - Fuzzy matching with phonetic algorithms (Cologne, Double Metaphone, Beider-Morse)
 * - ASR + matcher score combination
 * - Timeout handling (300ms inline, fallback to pending queue)
 * - Quick exact match for remaining hypotheses
 * - Combined score ranking
 */
class HeavyPathMatcher(
    private val context: Context,
    private val saf: SaFStorageHelper
) {
    companion object {
        private const val TAG = "HeavyPathMatcher"
        private const val INLINE_TIMEOUT_MS = 300L
        private const val FALLBACK_TIMEOUT_MS = 250L
        const val DEFAULT_ASR_WEIGHT = 0.4
    }

    /**
     * Try heavy matching with inline timeout
     * @return MatchResult if completed within timeout, null if timed out (should enqueue to pending)
     */
    suspend fun tryHeavyMatchInline(
        hypothesis: String,
        asrConfidence: Float,
        matchContext: MatchContext,
        asrWeight: Double = DEFAULT_ASR_WEIGHT
    ): HeavyMatchResult? {
        coroutineContext.ensureActive()

        val normalized = TextUtils.normalizeLowerNoDiacritics(hypothesis)
        if (normalized.isBlank()) return null

        val maybeResult = withTimeoutOrNull(INLINE_TIMEOUT_MS) {
            AliasPriorityMatcher.match(normalized, matchContext, context, saf)
        }

        return if (maybeResult != null) {
            val matcherScore = extractMatcherScore(maybeResult)
            val asrConf = asrConfidence.toDouble().coerceIn(0.0, 1.0)
            val combined = calculateCombinedScore(asrConf, matcherScore, asrWeight)

            HeavyMatchResult.Completed(maybeResult, combined)
        } else {
            HeavyMatchResult.TimedOut
        }
    }

    /**
     * Try inline fallback match with shorter timeout
     * Used when pending buffer is full
     */
    suspend fun tryInlineFallback(
        hypothesis: String,
        matchContext: MatchContext
    ): MatchResult? {
        val normalized = TextUtils.normalizeLowerNoDiacritics(hypothesis)
        if (normalized.isBlank()) return null

        return withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
            AliasPriorityMatcher.match(normalized, matchContext, context, saf)
        }
    }

    /**
     * Try quick exact match for lower-priority hypotheses
     * Useful for remaining hypotheses beyond heavy-path count
     */
    suspend fun tryQuickExactMatch(
        hypothesis: String,
        matchContext: MatchContext
    ): MatchResult? {
        try {
            val normalized = TextUtils.normalizeLowerNoDiacritics(hypothesis)
            if (normalized.isBlank()) return null

            val records = AliasMatcher.findExact(normalized, context, saf)
            if (records.isEmpty()) return null

            val speciesSet = records.map { it.speciesid }.toSet()
            val chosenSpeciesId = when {
                speciesSet.size == 1 -> speciesSet.first()
                else -> speciesSet.firstOrNull { it in matchContext.tilesSpeciesIds }
            } ?: return null

            val displayName = matchContext.speciesById[chosenSpeciesId]?.first ?: hypothesis
            val isInTiles = chosenSpeciesId in matchContext.tilesSpeciesIds

            val candidate = Candidate(
                speciesId = chosenSpeciesId,
                displayName = displayName,
                score = 0.9,
                isInTiles = isInTiles,
                source = "quick_exact"
            )

            return MatchResult.AutoAccept(candidate, hypothesis, "quick_exact", 1)
        } catch (ex: Exception) {
            Log.w(TAG, "Quick exact match error for '$hypothesis': ${ex.message}", ex)
            return null
        }
    }

    /**
     * Calculate combined score from ASR confidence and matcher score
     */
    fun calculateCombinedScore(
        asrConfidence: Double,
        matcherScore: Double,
        asrWeight: Double
    ): Double {
        return asrWeight * asrConfidence + (1.0 - asrWeight) * matcherScore
    }

    /**
     * Extract matcher score from MatchResult
     */
    private fun extractMatcherScore(result: MatchResult): Double {
        return when (result) {
            is MatchResult.AutoAccept -> result.candidate.score
            is MatchResult.AutoAcceptAddPopup -> result.candidate.score
            is MatchResult.SuggestionList -> result.candidates.firstOrNull()?.score ?: 0.0
            is MatchResult.MultiMatch -> result.matches.firstOrNull()?.candidate?.score ?: 0.0
            is MatchResult.NoMatch -> 0.0
            else -> 0.0
        }
    }

    /**
     * Result of heavy match attempt
     */
    sealed class HeavyMatchResult {
        data class Completed(val matchResult: MatchResult, val combinedScore: Double) : HeavyMatchResult()
        object TimedOut : HeavyMatchResult()
    }
}
