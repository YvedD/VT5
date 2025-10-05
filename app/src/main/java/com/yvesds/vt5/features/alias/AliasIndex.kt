/*
 * VT5 - AliasIndex datastructuren
 *
 * Doel:
 *  - Seriële datastructuur voor precomputed alias-index (JSON/CBOR).
 *  - Alles in lowercase behalve tilename (UI label).
 */

package com.yvesds.vt5.features.alias

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AliasIndex(
    @SerialName("json")
    val json: List<AliasRecord>
)

@Serializable
data class AliasRecord(
    val aliasid: String,            // per soort, oplopend "1", "2", ...
    val speciesid: String,          // lowercase string, compat met andere jsons
    val canonical: String,          // lowercase canonical
    val tilename: String? = null,   // UI-tegel (mag hoofdletters bevatten)
    val alias: String,              // alias zoals ingevoerd, lowercase

    // --- Precomputed velden ---
    val norm: String,               // genormaliseerd (lowercase, diacritics weg, tok. cleanup)

    // Fonetiek (Apache Commons Codec)
    val cologne: String? = null,
    val dmetapho: List<String>? = null,
    val beidermorse: List<String>? = null,

    // Lichte NL phonemizer (ruwe IPA-approx.)
    val phonemes: String? = null,

    // N-gram settings
    val ngrams: Map<String, String>, // bijv. {"q":"3"}

    // Fuzzy signatures
    val minhash64: List<Long>,       // K x 64-bit minima (unsigned vergeleken)
    val simhash64: String,           // hex "0x..." (64-bit)

    // Ranking
    val weight: Double = 1.0
)
