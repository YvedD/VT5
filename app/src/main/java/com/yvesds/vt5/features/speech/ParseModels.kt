@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.speech

import kotlinx.serialization.Serializable

/**
 * Models used by parser, matcher and logging.
 */

@Serializable
data class CandidateScore(
    val aliasId: String,
    val speciesId: String,
    val aliasText: String,
    val score: Double,
    val matchType: String // "exact" | "fuzzy"
)

@Serializable
data class ParsedItem(
    val rawPhrase: String,
    val normalized: String,
    val amount: Int,
    val chosenAliasId: String?,
    val chosenSpeciesId: String?,
    val chosenAliasText: String?,
    val score: Double,
    val candidates: List<CandidateScore>
)

@Serializable
data class ParseResult(
    val success: Boolean,
    val rawInput: String,
    val items: List<ParsedItem>,
    val message: String? = null
)

@Serializable
data class ParseLogEntry(
    val timestampIso: String,
    val rawInput: String,
    val parseResult: ParseResult,
    val partials: List<String> = emptyList() // ordered partials from ASR (may be empty)
)