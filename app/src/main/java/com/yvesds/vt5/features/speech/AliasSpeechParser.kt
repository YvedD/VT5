@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.speech.helpers.FastPathMatcher
import com.yvesds.vt5.features.speech.helpers.HeavyPathMatcher
import com.yvesds.vt5.features.speech.helpers.PendingMatchBuffer
import com.yvesds.vt5.features.speech.helpers.SpeechMatchLogger
import com.yvesds.vt5.utils.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * AliasSpeechParser (refactored with helpers)
 *
 * Orchestrates speech recognition result parsing with delegation to specialized helpers:
 * - SpeechMatchLogger: Centralized logging to SAF
 * - PendingMatchBuffer: Background queue for heavy matches
 * - FastPathMatcher: Fast exact matching for high-confidence results
 * - HeavyPathMatcher: Fuzzy phonetic matching with timeout handling
 *
 * Features preserved:
 * - Fast-path for exact matches (< 5ms)
 * - Heavy-path with phonetic algorithms (300ms-1200ms)
 * - Pending buffer for timeout cases
 * - Multi-hypothesis ASR support with scoring
 * - All canonical names, tile names, and aliases recognized
 */
class AliasSpeechParser(
    private val context: Context,
    private val saf: SaFStorageHelper
) {
    companion object {
        private const val TAG = "AliasSpeechParser"
        private const val FAST_N_HYPOTHESES = 3
        private const val HEAVY_HYP_COUNT = 3
    }

    // Helpers
    private val logger = SpeechMatchLogger(context, saf)
    private val pendingBuffer = PendingMatchBuffer(context, saf, logger)
    private val fastPathMatcher = FastPathMatcher(context, saf)
    private val heavyPathMatcher = HeavyPathMatcher(context, saf)

    init {
        // Ensure MatchLogWriter background writer started (idempotent)
        try {
            MatchLogWriter.start(context)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed starting MatchLogWriter: ${ex.message}", ex)
        }
    }

    /**
     * Register a listener to receive results for pending items
     */
    fun setPendingResultListener(listener: (id: String, result: MatchResult) -> Unit) {
        pendingBuffer.setResultListener(listener)
    }

    /**
     * Parse spoken text with context (single hypothesis)
     */
    suspend fun parseSpokenWithContext(
        rawAsr: String,
        matchContext: MatchContext,
        partials: List<String> = emptyList()
    ): MatchResult = withContext(Dispatchers.Default) {
        ensureActive()
        val t0 = System.currentTimeMillis()

        // Filter system prompts and normalize
        val rawTrim = rawAsr.trim()
        val rawLowerNoPunct = TextUtils.normalizeLowerNoDiacritics(rawTrim)

        if (rawLowerNoPunct.isBlank() || TextUtils.isFilterWord(rawLowerNoPunct)) {
            return@withContext MatchResult.NoMatch(rawAsr, "filtered-prompt")
        }

        // Filter partials
        val filteredPartials = filterPartials(partials)

        try {
            // Direct priority match (no fast-path needed for single hypothesis)
            val result = AliasPriorityMatcher.match(rawLowerNoPunct, matchContext, context, saf)
            val t1 = System.currentTimeMillis()

            Log.i(TAG, "parseSpokenWithContext: input='${rawAsr}' result=${result::class.simpleName} timeMs=${t1 - t0}")

            logger.logMatchResult(rawAsr, result, filteredPartials, asrHypotheses = null)
            return@withContext result
        } catch (ex: Exception) {
            Log.w(TAG, "parseSpokenWithContext failed: ${ex.message}", ex)
            val result = MatchResult.NoMatch(rawAsr, "exception")
            logger.logMatchResult(rawAsr, result, filteredPartials, asrHypotheses = null)
            return@withContext result
        }
    }

    /**
     * Parse spoken text with multiple ASR hypotheses
     * Combines ASR confidence with matcher scores for optimal results
     */
    suspend fun parseSpokenWithHypotheses(
        hypotheses: List<Pair<String, Float>>,
        matchContext: MatchContext,
        partials: List<String> = emptyList(),
        asrWeight: Double = HeavyPathMatcher.DEFAULT_ASR_WEIGHT
    ): MatchResult = withContext(Dispatchers.Default) {
        ensureActive()
        val t0 = System.currentTimeMillis()

        if (hypotheses.isEmpty()) {
            return@withContext MatchResult.NoMatch("", "empty-hypotheses")
        }

        // Normalize hypotheses
        val normalizedHyps = hypotheses.map { (text, conf) ->
            text.trim().lowercase(Locale.getDefault()) to conf
        }

        val filteredPartials = filterPartials(partials)

        // === FAST-PATH: Try exact match for top hypotheses ===
        for ((hyp, asrConf) in normalizedHyps.take(FAST_N_HYPOTHESES)) {
            ensureActive()
            if (hyp.isEmpty()) continue

            val fastResult = fastPathMatcher.tryFastMatch(hyp, asrConf, matchContext)
            if (fastResult != null) {
                logger.logMatchResult(hyp, fastResult, filteredPartials, asrHypotheses = hypotheses)
                return@withContext fastResult
            }
        }

        // === HEAVY-PATH: Try fuzzy match with timeout ===
        var bestCombined = Double.NEGATIVE_INFINITY
        var bestResult: MatchResult = MatchResult.NoMatch(hypotheses.first().first, "none")
        var enqueuedAny = false

        for ((hyp, asrConf) in normalizedHyps.take(HEAVY_HYP_COUNT)) {
            ensureActive()
            if (hyp.isEmpty()) continue

            val heavyResult = heavyPathMatcher.tryHeavyMatchInline(hyp, asrConf, matchContext, asrWeight)

            when (heavyResult) {
                is HeavyPathMatcher.HeavyMatchResult.Completed -> {
                    val mr = heavyResult.matchResult

                    // Immediate return for high-quality matches
                    if (mr is MatchResult.AutoAccept || mr is MatchResult.MultiMatch) {
                        logger.logMatchResult(hyp, mr, filteredPartials, asrHypotheses = hypotheses)
                        return@withContext mr
                    }

                    // Track best result
                    if (heavyResult.combinedScore > bestCombined) {
                        bestCombined = heavyResult.combinedScore
                        bestResult = mr
                    }
                }
                is HeavyPathMatcher.HeavyMatchResult.TimedOut -> {
                    // Enqueue to pending buffer
                    val pendingId = pendingBuffer.enqueuePending(hyp, asrConf, matchContext, filteredPartials)
                    if (pendingId != null) {
                        enqueuedAny = true
                        logger.logMatchResult(hyp, MatchResult.NoMatch(hyp, "queued"), filteredPartials, asrHypotheses = hypotheses)
                    } else {
                        // Buffer full - try inline fallback
                        val fallback = heavyPathMatcher.tryInlineFallback(hyp, matchContext)
                        if (fallback != null) {
                            logger.logMatchResult(hyp, fallback, filteredPartials, asrHypotheses = hypotheses)
                            return@withContext fallback
                        }
                        Log.w(TAG, "Pending buffer full and fallback failed for: '$hyp'")
                    }
                }
                null -> continue
            }
        }

        // === QUICK EXACT MATCH: Check remaining hypotheses ===
        if (bestCombined == Double.NEGATIVE_INFINITY) {
            for ((hyp, _) in normalizedHyps.drop(HEAVY_HYP_COUNT)) {
                ensureActive()
                val quickResult = heavyPathMatcher.tryQuickExactMatch(hyp, matchContext)
                if (quickResult != null) {
                    logger.logMatchResult(hyp, quickResult, filteredPartials, asrHypotheses = hypotheses)
                    return@withContext quickResult
                }
            }
        }

        // === RETURN BEST RESULT ===
        val t1 = System.currentTimeMillis()
        Log.i(TAG, "parseSpokenWithHypotheses: bestHyp='${bestResult.hypothesis}' type=${bestResult::class.simpleName} timeMs=${t1 - t0}")

        val firstHypText = hypotheses.firstOrNull()?.first ?: ""
        if (enqueuedAny && bestCombined == Double.NEGATIVE_INFINITY) {
            val result = MatchResult.NoMatch(firstHypText, "queued")
            logger.logMatchResult(firstHypText, result, filteredPartials, asrHypotheses = hypotheses)
            return@withContext result
        }

        logger.logMatchResult(firstHypText, bestResult, filteredPartials, asrHypotheses = hypotheses)
        return@withContext bestResult
    }

    /**
     * Filter out system prompts from partials list
     */
    private fun filterPartials(partials: List<String>): List<String> {
        return partials.filter { p ->
            val normalized = TextUtils.normalizeLowerNoDiacritics(p)
            !TextUtils.FILTER_WORDS.contains(normalized)
        }
    }
}
