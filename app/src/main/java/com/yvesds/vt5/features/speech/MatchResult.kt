package com.yvesds.vt5.features.speech

/**
 * Result sealed hierarchy returned by AliasPriorityMatcher.match(...)
 *
 * UPDATED: Added amount support for extracted counts from queries like "aalscholver 5 boertjes 3"
 * - AutoAccept: top candidate is clearly best and should be automatically accepted (increment count by amount)
 * - AutoAcceptAddPopup: candidate is recognized but not present in tiles -> ask user to add to tiles
 * - SuggestionList: ambiguous result -> show top suggestions for user selection
 * - NoMatch: no sufficiently good candidate -> treat as raw (user may add alias)
 * - MultiMatch: NEW - multiple species recognized in single query (e.g., "aalscholver 5 boertjes 3")
 */
sealed class MatchResult {
    abstract val hypothesis: String
    abstract val source: String?

    data class AutoAccept(
        val candidate: Candidate,
        override val hypothesis: String,
        override val source: String? = null,
        val amount: Int = 1  // NEW: extracted amount from query
    ) : MatchResult()

    data class AutoAcceptAddPopup(
        val candidate: Candidate,
        override val hypothesis: String,
        override val source: String? = null,
        val amount: Int = 1  // NEW: extracted amount from query
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

    /**
     * NEW: Multi-species match (e.g., "aalscholver 5 boertjes 3" -> Aalscholver 5 + Boerenzwaluw 3)
     * Returned when query contains multiple recognizable species with amounts.
     */
    data class MultiMatch(
        val matches: List<MatchWithAmount>,
        override val hypothesis: String,
        override val source: String? = null
    ) : MatchResult()

    /**
     * Helper data class for multi-match entries.
     */
    data class MatchWithAmount(
        val candidate: Candidate,
        val amount: Int,
        val source: String
    )
}