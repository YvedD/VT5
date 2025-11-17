package com.yvesds.vt5.features.speech.helpers

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.speech.AliasMatcher
import com.yvesds.vt5.features.speech.Candidate
import com.yvesds.vt5.features.speech.MatchContext
import com.yvesds.vt5.features.speech.MatchResult
import com.yvesds.vt5.utils.TextUtils

/**
 * FastPathMatcher
 *
 * Performs fast exact matching for high-confidence ASR hypotheses.
 * Uses AliasMatcher.findExact for O(1) lookup of canonical names, tile names, and aliases.
 *
 * Features:
 * - Fast exact match lookup (< 5ms typical)
 * - Confidence threshold filtering (0.99 for site matches)
 * - Tile priority matching
 * - Species disambiguation for multi-match scenarios
 * - Count extraction from trailing integers
 */
class FastPathMatcher(
    private val context: Context,
    private val saf: SaFStorageHelper
) {
    companion object {
        private const val TAG = "FastPathMatcher"
        private const val FAST_ASR_CONF_THRESHOLD = 0.99
        private const val SLOW_LOOKUP_THRESHOLD_MS = 100L
    }

    /**
     * Try fast-path matching for a single hypothesis
     * @return MatchResult.AutoAccept if matched, null otherwise
     */
    fun tryFastMatch(
        hypothesis: String,
        asrConfidence: Float,
        matchContext: MatchContext
    ): MatchResult? {
        try {
            val (nameOnly, extractedCount) = TextUtils.parseTrailingInteger(hypothesis)
            val normalized = TextUtils.normalizeLowerNoDiacritics(nameOnly)

            if (normalized.isBlank()) return null

            // Fast exact lookup
            val t0 = System.currentTimeMillis()
            val records = AliasMatcher.findExact(normalized, context, saf)
            val t1 = System.currentTimeMillis()

            if (t1 - t0 > SLOW_LOOKUP_THRESHOLD_MS) {
                Log.d(TAG, "Slow findExact for '$normalized' timeMs=${t1 - t0}")
            }

            if (records.isEmpty()) return null

            // Disambiguate if multiple species match
            val speciesSet = records.map { it.speciesid }.toSet()
            val chosenSpeciesId = disambiguateSpecies(speciesSet, matchContext) ?: return null

            // Validate match against context
            val amount = extractedCount ?: 1
            val asrConf = asrConfidence.toDouble().coerceIn(0.0, 1.0)

            if (!validateMatch(chosenSpeciesId, asrConf, matchContext)) {
                return null
            }

            // Build successful match result
            val isInTiles = chosenSpeciesId in matchContext.tilesSpeciesIds
            val displayName = matchContext.speciesById[chosenSpeciesId]?.first ?: nameOnly
            val source = if (isInTiles) "fast_tiles" else "fast_site"

            val candidate = Candidate(
                speciesId = chosenSpeciesId,
                displayName = displayName,
                score = 1.0,
                isInTiles = isInTiles,
                source = source
            )

            Log.d(TAG, "FASTPATH accept: species=$chosenSpeciesId source=$source amount=$amount hyp='$hypothesis' asrConf=$asrConf")

            return MatchResult.AutoAccept(candidate, hypothesis, "fastpath", amount)
        } catch (ex: Exception) {
            Log.w(TAG, "Fast-path error for '$hypothesis': ${ex.message}", ex)
            return null
        }
    }

    /**
     * Disambiguate species when multiple matches found
     * Priority: Single match > Tile match > First match
     */
    private fun disambiguateSpecies(
        speciesSet: Set<String>,
        matchContext: MatchContext
    ): String? {
        return when {
            speciesSet.isEmpty() -> null
            speciesSet.size == 1 -> speciesSet.first()
            else -> {
                // Prefer species in tiles
                val inTiles = speciesSet.firstOrNull { it in matchContext.tilesSpeciesIds }
                inTiles ?: speciesSet.first() // Fallback to first match
            }
        }
    }

    /**
     * Validate if match should be auto-accepted
     * Rules:
     * - Always accept if in tiles
     * - Accept site-allowed if ASR confidence >= threshold
     */
    private fun validateMatch(
        speciesId: String,
        asrConfidence: Double,
        matchContext: MatchContext
    ): Boolean {
        val isInTiles = speciesId in matchContext.tilesSpeciesIds
        val isSiteAllowed = speciesId in matchContext.siteAllowedIds

        return isInTiles || (isSiteAllowed && asrConfidence >= FAST_ASR_CONF_THRESHOLD)
    }
}
