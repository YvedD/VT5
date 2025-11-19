package com.yvesds.vt5.features.telling

import android.app.Activity
import android.util.Log
import com.yvesds.vt5.features.speech.Candidate
import com.yvesds.vt5.features.speech.MatchResult

/**
 * TellingMatchResultHandler: Processes speech recognition match results.
 * 
 * Responsibilities:
 * - Route match results to appropriate handlers
 * - Handle auto-accept matches
 * - Handle multi-match scenarios
 * - Handle suggestion lists
 */
class TellingMatchResultHandler(
    private val activity: Activity
) {
    companion object {
        private const val TAG = "TellingMatchResultHandler"
    }

    // Callbacks for different match types
    var onAutoAccept: ((String, String, Int) -> Unit)? = null
    var onAutoAcceptWithPopup: ((String, String, Int, Boolean) -> Unit)? = null
    var onMultiMatch: ((List<MatchResult.Match>) -> Unit)? = null
    var onSuggestionList: ((List<Candidate>, Int) -> Unit)? = null
    var onNoMatch: ((String) -> Unit)? = null

    /**
     * Handle different types of match results from speech parsing.
     */
    fun handleMatchResult(result: MatchResult) {
        when (result) {
            is MatchResult.AutoAccept -> {
                handleAutoAcceptMatch(result)
            }
            is MatchResult.AutoAcceptAddPopup -> {
                handleAutoAcceptAddPopup(result)
            }
            is MatchResult.MultiMatch -> {
                handleMultiMatch(result)
            }
            is MatchResult.SuggestionList -> {
                onSuggestionList?.invoke(result.candidates, extractCountFromHypothesis(result.hypothesis))
            }
            is MatchResult.NoMatch -> {
                onNoMatch?.invoke(result.hypothesis)
            }
        }
    }

    /**
     * Handle auto-accept match (species recognized and in tiles).
     */
    private fun handleAutoAcceptMatch(result: MatchResult.AutoAccept) {
        val speciesId = result.candidate.speciesId
        val displayName = result.candidate.displayName
        val amount = result.amount
        
        onAutoAccept?.invoke(speciesId, displayName, amount)
    }

    /**
     * Handle auto-accept with popup (species recognized but not in tiles).
     */
    private fun handleAutoAcceptAddPopup(result: MatchResult.AutoAcceptAddPopup) {
        val speciesId = result.candidate.speciesId
        val displayName = result.candidate.displayName
        val amount = result.amount
        val isInTiles = result.candidate.isInTiles
        
        onAutoAcceptWithPopup?.invoke(speciesId, displayName, amount, isInTiles)
    }

    /**
     * Handle multi-match scenario (multiple species recognized).
     */
    private fun handleMultiMatch(result: MatchResult.MultiMatch) {
        onMultiMatch?.invoke(result.matches)
    }

    /**
     * Extract count from hypothesis text.
     * Simple implementation - can be enhanced with TellingLogManager if needed.
     */
    private fun extractCountFromHypothesis(hypothesis: String): Int {
        // Try to find trailing number
        val regex = Regex("\\s+(\\d+)(?:[.,]\\d+)?\$")
        val match = regex.find(hypothesis)
        return match?.groups?.get(1)?.value?.toIntOrNull() ?: 1
    }
}
