package com.yvesds.vt5.features.speech

/**
 * Result sealed hierarchy returned by AliasPriorityMatcher.match(...)
 *
 * - AutoAccept: top candidate is clearly best and should be automatically accepted (increment count)
 * - AutoAcceptAddPopup: candidate is recognized but not present in tiles -> ask user to add to tiles
 * - SuggestionList: ambiguous result -> show top suggestions for user selection
 * - NoMatch: no sufficiently good candidate -> treat as raw (user may add alias)
 */
sealed class MatchResult {
    abstract val hypothesis: String
    abstract val source: String?

    data class AutoAccept(
        val candidate: Candidate,
        override val hypothesis: String,
        override val source: String? = null
    ) : MatchResult()

    data class AutoAcceptAddPopup(
        val candidate: Candidate,
        override val hypothesis: String,
        override val source: String? = null
    ) : MatchResult()

    data class SuggestionList(
        val candidates: List<Candidate>,
        override val hypothesis: String,
        override val source: String? = null
    ) : MatchResult()

    data class NoMatch(
        override val hypothesis: String,
        override val source: String? = null
    ) : MatchResult()
}