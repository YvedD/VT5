@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.alias

import kotlinx.serialization.Serializable

/**
 * AliasModels.kt
 *
 * PURPOSE:
 * Data models for the hybrid alias system with Cologne + Phonemes matching.
 *
 * ARCHITECTURE:
 * - AliasMaster: Root container (persisted to aliases_master.json)
 * - SpeciesEntry: Per-species container with all aliases
 * - AliasData: Single alias with hybrid features (text, cologne, phonemes)
 * - AliasIndex: CBOR cache format (for backwards compatibility with AliasMatcher)
 * - AliasRecord: Runtime record (used by AliasMatcher in-memory cache)
 *
 * DATA FLOW:
 * 1. First install: Generate AliasMaster from species.json
 * 2. Runtime: User adds alias → AliasMaster updated
 * 3. Background: AliasMaster → AliasIndex (CBOR) → AliasMatcher cache
 *
 * FILE FORMATS:
 * - aliases_master.json: Human-readable, pretty-printed (AliasMaster)
 * - aliases_optimized.cbor.gz: Binary cache for fast loading (AliasIndex)
 *
 * STORAGE SIZE (per alias):
 * - text: ~12 bytes avg
 * - norm: ~12 bytes avg
 * - cologne: ~8 bytes avg
 * - phonemes: ~25 bytes avg
 * - source: ~20 bytes avg
 * Total: ~77 bytes (vs 250 bytes in old format with minhash/simhash!)
 *
 * AUTHOR: VT5 Team (YvedD)
 * DATE: 2025-10-28
 * VERSION: 2.1
 */

/*═══════════════════════════════════════════════════════════════════════
 * PERSISTENT STORAGE MODELS (aliases_master.json)
 *═══════════════════════════════════════════════════════════════════════*/

/**
 * AliasMaster - Root container for all aliases
 *
 * Persisted to: SAF Documents/VT5/binaries/aliases_master.json
 * Format: JSON (pretty-printed for human readability)
 *
 * Contains:
 * - Seed aliases (from species.json canonical/tilename)
 * - User field training aliases (added during sessions)
 *
 * Update frequency: Batched (every 5 additions or 30s timeout)
 */
@Serializable
data class AliasMaster(
    /**
     * Schema version for future migrations
     * Current: "2.1"
     */
    val version: String = "2.1",

    /**
     * Last modification timestamp (ISO 8601)
     * Used for sync/merge decisions
     */
    val timestamp: String,

    /**
     * List of all species with their aliases
     * One entry per species (766 species total)
     */
    val species: List<SpeciesEntry>
)

/**
 * SpeciesEntry - All aliases for a single species
 *
 * Groups aliases by species for efficient lookups and updates.
 * Each species has 2+ aliases minimum (canonical + tilename).
 */
@Serializable
data class SpeciesEntry(
    /**
     * Species ID (matches species.json "id" field)
     * Example: "20" for Aalscholver
     */
    val speciesId: String,

    /**
     * Canonical name (from species.json "soortnaam")
     * Example: "Aalscholver"
     */
    val canonical: String,

    /**
     * Short tile name (from species.json "soortkey")
     * Example: "Aal"
     * Nullable for species without custom tile names
     */
    val tilename: String?,

    /**
     * List of all aliases for this species
     * Includes:
     * - Seed aliases (canonical, tilename)
     * - User field training aliases (added during sessions)
     *
     * Minimum: 2 aliases (canonical + tilename)
     * Maximum: Unlimited (user can add as many as needed)
     */
    val aliases: List<AliasData>
)

/**
 * AliasData - Single alias with hybrid matching features
 *
 * Contains all data needed for 3-layer matching:
 * - Text: Exact/fuzzy string matching
 * - Cologne: Fast consonant skeleton matching
 * - Phonemes: High-precision vowel-aware matching
 *
 * Storage: ~77 bytes per alias (compact!)
 */
@Serializable
data class AliasData(
    /**
     * Original alias text (lowercase)
     * Example: "aalscholver", "ali", "alsgolver"
     */
    val text: String,

    /**
     * Normalized text (lowercase, no diacritics, single spaces)
     * Used for Levenshtein distance calculations
     * Example: "aalscholver" → "aalscholver"
     * Example: "blauwe  reiger" → "blauwe reiger"
     */
    val norm: String,

    /**
     * Cologne phonetic code (consonant skeleton)
     * Used for fast first-pass fuzzy matching
     * Example: "aalscholver" → "05247"
     * Example: "vijf" → "35" (different from "364" for "Vink"!)
     */
    val cologne: String,

    /**
     * IPA phonemes (space-separated)
     * Used for high-precision vowel-aware matching
     * Example: "aalscholver" → "aːlsxɔlvər"
     * Example: "vijf" → "vɛif" (vs "vɪŋk" for "Vink")
     */
    val phonemes: String,

    /**
     * Source of this alias
     * Values:
     * - "seed_canonical": From species.json canonical name
     * - "seed_tilename": From species.json tile name
     * - "user_field_training": Added by user during session
     *
     * Used for:
     * - Traceability (which aliases are user-trained?)
     * - Export (share user training with others)
     * - Conflict resolution (user aliases override seed)
     */
    val source: String = "seed_canonical",

    /**
     * Creation timestamp (ISO 8601)
     * Only present for user_field_training aliases
     * Used for analytics and export
     */
    val timestamp: String? = null
)

/*═══════════════════════════════════════════════════════════════════════
 * RUNTIME CACHE MODELS (in-memory + CBOR)
 *═══════════════════════════════════════════════════════════════════════*/

/**
 * AliasIndex - CBOR cache format
 *
 * Persisted to: SAF Documents/VT5/binaries/aliases_optimized.cbor.gz
 * Format: CBOR (binary) + GZIP compression
 *
 * Used by: AliasMatcher (loaded into in-memory cache at app start)
 *
 * Why separate from AliasMaster?
 * - AliasMaster: Human-readable JSON (for debugging/export)
 * - AliasIndex: Optimized binary format (for fast loading)
 *
 * Regenerated: Whenever AliasMaster changes (background async)
 */
@Serializable
data class AliasIndex(
    /**
     * Schema version (for future migrations)
     */
    val version: String = "2.1",

    /**
     * Creation timestamp (ISO 8601)
     */
    val timestamp: String,

    /**
     * Flat list of all alias records
     * Named "json" for backwards compatibility with existing AliasMatcher code
     *
     * Total: ~4000 records (766 species × ~5 aliases avg)
     */
    val json: List<AliasRecord>
)

/**
 * AliasRecord - Runtime record for in-memory cache
 *
 * Used by: AliasMatcher (in-memory hash maps)
 *
 * Optimized for:
 * - Fast lookups (hash map key = norm)
 * - Minimal memory footprint (~90 bytes per record)
 * - Thread-safe access (immutable data class)
 *
 * This is the "working format" for all runtime matching operations.
 */
@Serializable
data class AliasRecord(
    /**
     * Unique alias ID
     * Format: "speciesId_index" (e.g., "20_1", "20_2", ...)
     * Used for deduplication and tracking
     */
    val aliasid: String,

    /**
     * Species ID this alias belongs to
     * Example: "20" for Aalscholver
     */
    val speciesid: String,

    /**
     * Canonical species name
     * Example: "Aalscholver"
     */
    val canonical: String,

    /**
     * Short tile name (nullable)
     * Example: "Aal"
     */
    val tilename: String?,

    /**
     * Original alias text (lowercase)
     * Example: "ali", "alsgolver"
     */
    val alias: String,

    /*───────────────────────────────────────────────────────────────────
     * HYBRID MATCHING FEATURES (3-layer system)
     *───────────────────────────────────────────────────────────────────*/

    /**
     * Normalized text (for Levenshtein)
     * Example: "alsgolver"
     */
    val norm: String,

    /**
     * Cologne phonetic code (for fast fuzzy matching)
     * Example: "05247"
     * Nullable if encoding fails
     */
    val cologne: String?,

    /**
     * IPA phonemes (for precision matching)
     * Example: "ɑlsxɔlvər"
     * Nullable if phonemization fails
     */
    val phonemes: String?,

    /*───────────────────────────────────────────────────────────────────
     * METADATA
     *───────────────────────────────────────────────────────────────────*/

    /**
     * Relevance weight (1.0 = normal)
     * Future: Could be frequency-based (popular aliases get higher weight)
     * Currently: Always 1.0
     */
    val weight: Double = 1.0,

    /**
     * Source of this alias
     * Values: "seed_canonical", "seed_tilename", "user_field_training"
     */
    val source: String = "seed_canonical"
)

/*═══════════════════════════════════════════════════════════════════════
 * HELPER EXTENSIONS
 *═══════════════════════════════════════════════════════════════════════*/

/**
 * Convert AliasMaster to AliasIndex (for CBOR cache generation)
 *
 * Used by: AliasManager.rebuildCborCache()
 *
 * Flattens the hierarchical structure (species → aliases) into
 * a flat list of AliasRecord for efficient AliasMatcher loading.
 */
fun AliasMaster.toAliasIndex(): AliasIndex {
    val records = mutableListOf<AliasRecord>()

    for (speciesEntry in species) {
        speciesEntry.aliases.forEachIndexed { index, aliasData ->
            records += AliasRecord(
                aliasid = "${speciesEntry.speciesId}_${index + 1}",
                speciesid = speciesEntry.speciesId,
                canonical = speciesEntry.canonical,
                tilename = speciesEntry.tilename,
                alias = aliasData.text,
                norm = aliasData.norm,
                cologne = aliasData.cologne,
                phonemes = aliasData.phonemes,
                weight = 1.0,
                source = aliasData.source
            )
        }
    }

    return AliasIndex(
        version = this.version,
        timestamp = this.timestamp,
        json = records
    )
}

/**
 * Check if alias already exists in species entry
 *
 * Used by: AliasManager.addAlias() for duplicate detection
 *
 * Compares normalized text (case-insensitive, whitespace-normalized)
 */
fun SpeciesEntry.hasAlias(aliasText: String): Boolean {
    val normalized = aliasText.trim().lowercase()
    return aliases.any { it.text.trim().lowercase() == normalized }
}

/**
 * Find species entry by ID
 *
 * Used by: AliasManager.addAlias() for updating existing entries
 */
fun AliasMaster.findSpecies(speciesId: String): SpeciesEntry? {
    return species.firstOrNull { it.speciesId == speciesId }
}