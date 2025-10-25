@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.speech

import kotlinx.serialization.Serializable

/**
 * MatchContext: context voor ASR matching met tiles, site allowed species en recents.
 *
 * Deze context wordt doorgegeven aan AliasPriorityMatcher om:
 * - Priority cascade toe te passen (tiles → site)
 * - Prior scoring te berekenen (recents + local + site membership)
 * - Correcte UI flows te bepalen (auto-accept vs popup)
 */
@Serializable
data class MatchContext(
    /**
     * Species IDs currently in the user's tiles (local selection).
     * Used for:
     * - Priority steps 1, 3, 5, 6 (exact/fuzzy canonical/alias in tiles)
     * - Prior scoring: +0.25 if candidate in tiles
     */
    val tilesSpeciesIds: Set<String>,

    /**
     * Species IDs allowed for the current telpost (from site_species.json).
     * Used for:
     * - Priority steps 2, 4, 7, 8 (exact/fuzzy canonical/alias in site_allowed)
     * - Prior scoring: +0.15 if candidate in site_allowed
     */
    val siteAllowedIds: Set<String>,

    /**
     * Species IDs recently used (from RecentSpeciesStore).
     * Used for:
     * - Prior scoring: +0.25 if candidate in recents
     */
    val recentIds: Set<String>,

    /**
     * Full species data: soortId → (canonical name, tilename/key).
     * Used for lookup and display.
     */
    val speciesById: Map<String, Pair<String, String?>>
) {
    companion object {
        /**
         * Create empty context (for tests or fallback scenarios).
         */
        fun empty(): MatchContext = MatchContext(
            tilesSpeciesIds = emptySet(),
            siteAllowedIds = emptySet(),
            recentIds = emptySet(),
            speciesById = emptyMap()
        )
    }
}