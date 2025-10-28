package com.yvesds.vt5.features.alias

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.cbor.Cbor
import java.text.Normalizer
import org.apache.commons.codec.language.ColognePhonetic
import com.yvesds.vt5.features.speech.DutchPhonemizer
import java.time.Instant

/**
 * PrecomputeAliasIndex.kt
 *
 * Purpose:
 * - Read legacy aliasmapping CSV content and produce an AliasIndex (flat list of AliasRecord)
 * - Produce only the fields we agreed to keep:
 *     aliasid, speciesid, canonical, tilename, alias, norm, cologne, phonemes, weight, source
 * - Drop heavy/legacy features: dmetapho, beidermorse, ngrams, minhash64, simhash64
 *
 * Notes:
 * - The public API signature was simplified: buildFromCsv(csvRawText, q = 3)
 *   The minhash/minhashK parameter was removed entirely (we no longer compute
 *   MinHash/SimHash). Call sites must pass only the CSV text (and optionally q).
 *
 * Usage:
 * val aliasIndex = PrecomputeAliasIndex.buildFromCsv(csvText, q = 3)
 *
 * Author: VT5 Team (YvedD)
 * Date: 2025-10-28
 * Version: 2.1
 */

object PrecomputeAliasIndex {

    private val jsonPretty = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Public API: build AliasIndex from legacy aliasmapping.csv text.
     * - q remains available for possible future q-gram usage.
     * - The old minhashK argument has been removed on purpose (no MinHash/SimHash).
     */
    fun buildFromCsv(csvRawText: String, q: Int = 3): AliasIndex {
        val rows = parseCsv(csvRawText)
        val records = mutableListOf<AliasRecord>()
        var globalCounter = 0

        val cologne = ColognePhonetic()

        for (row in rows) {
            var aliasCounter = 0

            fun addAlias(aliasRaw: String, source: String = "seed_import") {
                if (aliasRaw.isBlank()) return
                aliasCounter += 1
                globalCounter += 1
                val aliasId = "${row.speciesId}_${aliasCounter}"
                val aliasText = aliasRaw.trim()
                val norm = normalizeLowerNoDiacritics(aliasText)
                val col = runCatching { cologne.encode(norm) }.getOrNull()
                val phon = runCatching { DutchPhonemizer.phonemize(norm) }.getOrNull()

                val rec = AliasRecord(
                    aliasid = aliasId,
                    speciesid = row.speciesId,
                    canonical = row.canonical,
                    tilename = row.tileName,
                    alias = aliasText,
                    norm = norm,
                    cologne = col,
                    phonemes = phon,
                    weight = 1.0,
                    source = source
                )
                records += rec
            }

            // 1) canonical
            addAlias(row.canonical, source = "seed_canonical")

            // 2) tilename (if present and different)
            if (!row.tileName.isNullOrBlank() && !row.tileName.equals(row.canonical, ignoreCase = true)) {
                addAlias(row.tileName, source = "seed_tilename")
            }

            // 3) all extra aliases on CSV line
            row.otherAliases.forEach { alias ->
                if (alias.isNotBlank()) addAlias(alias, source = "seed_import")
            }
        }

        val index = AliasIndex(
            version = "2.1",
            timestamp = Instant.now().toString(),
            json = records
        )

        return index
    }

    /**
     * Simple CSV parser for the aliasmapping.csv format observed:
     * speciesId ; canonical ; tileName ; alias1 ; alias2 ; ...
     */
    private data class CsvRow(
        val speciesId: String,
        val canonical: String,
        val tileName: String?,
        val otherAliases: List<String>
    )

    private fun parseCsv(txt: String): List<CsvRow> {
        val out = mutableListOf<CsvRow>()
        txt.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                // tolerate both semicolon and comma, but primary is semicolon
                val parts = line.split(';').map { it.trim() }
                if (parts.size >= 2) {
                    val speciesId = parts[0]
                    val canonical = parts.getOrNull(1) ?: ""
                    val tileName = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                    val aliases = if (parts.size > 3) parts.drop(3).map { it } else emptyList()
                    out += CsvRow(speciesId = speciesId, canonical = canonical, tileName = tileName, otherAliases = aliases)
                }
            }
        return out
    }

    /**
     * Normalize: lowercase, remove diacritics, remove non alnum characters except spaces,
     * collapse multi-space to single space and trim.
     */
    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase()
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd} ]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}