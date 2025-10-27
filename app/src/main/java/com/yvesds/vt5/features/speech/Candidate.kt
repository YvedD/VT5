package com.yvesds.vt5.features.speech

/**
 * Candidate representation returned by AliasPriorityMatcher.
 *
 * - speciesId: canonical species identifier
 * - displayName: human readable name (canonical / species name)
 * - score: combined ranking score used to sort candidates
 * - isInTiles: whether the species is currently present in tiles (helps UI decide AutoAccept path)
 * - source: short tag describing where this candidate originates (e.g. "tiles-exact","site-exact","fuzzy")
 */
data class Candidate(
    val speciesId: String,
    val displayName: String,
    val score: Double,
    val isInTiles: Boolean,
    val source: String
)