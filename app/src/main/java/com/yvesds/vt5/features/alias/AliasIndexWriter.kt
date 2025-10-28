@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.alias

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/**
 * AliasIndexWriter (SAF-only, updated)
 *
 * - Produces JSON and CBOR outputs for aliases/species/phonetic map and manifest.
 * - JSON export is compact and no longer contains legacy fields (dmetapho, beidermorse, ngrams, minhash64, simhash64).
 * - CBOR export uses AliasIndex (the canonical structure) — if you want CBOR without legacy fields,
 *   ensure AliasIndex/AliasRecord definitions also omit them (we adapted PrecomputeAliasIndex and will adapt models next).
 *
 * Author: VT5 Team (YvedD)
 * Date: 2025-10-28
 */

object AliasIndexWriter {
    private const val TAG = "AliasIndexWriter"

    private const val CSV_NAME = "aliasmapping.csv"
    private const val ASSETS = "assets"
    private const val SERVERDATA = "serverdata"
    private const val BINARIES = "binaries"
    private const val EXPORTS = "exports"

    private const val ALIAS_JSON_NAME = "alias_index.json"
    private const val ALIAS_JSON_GZ_NAME = "alias_index.json.gz"
    private const val ALIASES_CBOR_GZ = "aliases_flat.cbor.gz"
    private const val SPECIES_CBOR_GZ = "species_master.cbor.gz"

    private const val ALIASES_SCHEMA = "aliases_flat.schema.json"
    private const val SPECIES_SCHEMA = "species_master.schema.json"
    private const val PHONETIC_SCHEMA = "phonetic_map.schema.json"
    private const val MANIFEST = "manifest.schema.json"

    private val jsonPretty = Json { prettyPrint = true }

    /**
     * Compact JSON DTO for alias export (no legacy fields)
     */
    @Serializable
    data class AliasJsonEntry(
        val aliasid: String,
        val speciesid: String,
        val canonical: String,
        val tilename: String? = null,
        val alias: String,
        val norm: String,
        val cologne: String? = null,
        val phonemes: String? = null,
        val weight: Double = 1.0
    )

    @Serializable
    data class SimpleSpecies(
        val speciesId: String,
        val soortnaam: String,
        val tilename: String,
        val sortering: String? = null,
        val aliases: List<String> = emptyList()
    )

    @Serializable
    data class ManifestIndexEntry(
        val path: String,
        val sha256: String,
        val size: String,
        val aliases_count: String? = null,
        val species_count: String? = null
    )

    @Serializable
    data class Manifest(
        val version: String,
        val generated_at: String,
        val sources: Map<String, Map<String, String>>,
        val indexes: Map<String, ManifestIndexEntry>,
        val counts: Map<String, Int>,
        val notes: String
    )

    data class Result(
        val safAliasJsonPath: String?,
        val safCborPath: String?,
        val safSpeciesCborPath: String?,
        val safManifestPath: String?,
        val safSchemas: List<String>,
        val safExportLog: String?,
        val aliasCount: Int,
        val messages: List<String>
    )

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun ensureComputedSafOnly(context: Context, saf: SaFStorageHelper, q: Int = 3): Result {
        val messages = mutableListOf<String>()
        val vt5 = saf.getVt5DirIfExists()
        if (vt5 == null) {
            messages += "SAF root NOT set. Kies 'Kies documenten' en selecteer Documents/VT5."
            return Result(null, null, null, null, emptyList(), null, 0, messages)
        }

        // Validate inputs
        val assetsDir = vt5.findFile(ASSETS)?.takeIf { it.isDirectory }
        val csvDoc = assetsDir?.findFile(CSV_NAME)?.takeIf { it.isFile }
        if (csvDoc == null) {
            messages += "Required: Documents/VT5/$ASSETS/$CSV_NAME missing."
            return Result(null, null, null, null, emptyList(), null, 0, messages)
        }
        val serverDir = vt5.findFile(SERVERDATA)?.takeIf { it.isDirectory }
        if (serverDir == null) {
            messages += "Required: Documents/VT5/$SERVERDATA missing."
            return Result(null, null, null, null, emptyList(), null, 0, messages)
        }
        val speciesDoc = serverDir.findFile("species.json")?.takeIf { it.isFile }
        val siteSpeciesDoc = serverDir.findFile("site_species.json")?.takeIf { it.isFile }
        if (speciesDoc == null || siteSpeciesDoc == null) {
            messages += "Required: species.json and/or site_species.json missing in Documents/VT5/$SERVERDATA."
            return Result(null, null, null, null, emptyList(), null, 0, messages)
        }

        // Read input files
        val csvBytes = runCatching {
            context.contentResolver.openInputStream(csvDoc.uri)?.use { it.readBytes() }
        }.getOrNull()
        if (csvBytes == null || csvBytes.isEmpty()) {
            messages += "Failed to read $CSV_NAME from SAF."
            return Result(null, null, null, null, emptyList(), null, 0, messages)
        }
        messages += "Read Documents/VT5/$ASSETS/$CSV_NAME"

        val speciesBytes = runCatching {
            context.contentResolver.openInputStream(speciesDoc.uri)?.use { it.readBytes() }
        }.getOrNull()
        val siteSpeciesBytes = runCatching {
            context.contentResolver.openInputStream(siteSpeciesDoc.uri)?.use { it.readBytes() }
        }.getOrNull()
        if (speciesBytes == null || siteSpeciesBytes == null) {
            messages += "Failed to read species/site_species from SAF."
            return Result(null, null, null, null, emptyList(), null, 0, messages)
        }
        messages += "Read Documents/VT5/$SERVERDATA/species.json and site_species.json"

        // Build alias index using the new API (csvText, q)
        val csvText = csvBytes.toString(Charsets.UTF_8)
        val aliasIndex = try {
            PrecomputeAliasIndex.buildFromCsv(csvText, q)
        } catch (ex: Exception) {
            messages += "Error building alias index: ${ex.message}"
            return Result(null, null, null, null, emptyList(), null, 0, messages)
        }
        val aliasCount = aliasIndex.json.size
        messages += "Built alias index: $aliasCount records"

        // Build species_master list by extracting minimal fields from species.json
        val speciesJsonElement = Json.parseToJsonElement(speciesBytes.toString(Charsets.UTF_8))
        val speciesArray = speciesJsonElement.jsonObject["json"]?.jsonArray ?: JsonArray(emptyList())
        val speciesMapById = speciesArray.associateBy { it.jsonObject["soortid"]?.jsonPrimitive?.content ?: "" }

        val speciesIdsInIndex = aliasIndex.json.map { it.speciesid }.toSet()
        val simpleSpeciesList = speciesIdsInIndex.map { sid ->
            val entry = speciesMapById[sid]
            val soortnaam = entry?.jsonObject?.get("soortnaam")?.jsonPrimitive?.content ?: sid
            val soortkey = entry?.jsonObject?.get("soortkey")?.jsonPrimitive?.content ?: soortnaam
            SimpleSpecies(speciesId = sid, soortnaam = soortnaam, tilename = soortkey)
        }

        // Build phonetic_map (code -> list of aliasIds) using only cologne and phonemes
        val phoneticMap = mutableMapOf<String, MutableList<String>>()
        aliasIndex.json.forEach { a ->
            a.cologne?.let { code ->
                if (code.isNotBlank()) phoneticMap.getOrPut("cologne:$code") { mutableListOf() }.add(a.aliasid)
            }
            a.phonemes?.let { p ->
                val key = "phonemes:${p.replace("\\s+".toRegex(), "")}"
                phoneticMap.getOrPut(key) { mutableListOf() }.add(a.aliasid)
            }
        }

        // Map AliasRecord -> AliasJsonEntry (explicit DTO) excluding legacy fields
        val aliasJsonList = aliasIndex.json.map { a ->
            AliasJsonEntry(
                aliasid = a.aliasid,
                speciesid = a.speciesid,
                canonical = a.canonical,
                tilename = a.tilename,
                alias = a.alias,
                norm = a.norm,
                cologne = a.cologne,
                phonemes = a.phonemes,
                weight = a.weight
            )
        }

        // Serialize JSON and CBOR with explicit serializers
        val aliasJsonText = jsonPretty.encodeToString(ListSerializer(AliasJsonEntry.serializer()), aliasJsonList)
        val aliasJsonBytes = aliasJsonText.toByteArray(Charsets.UTF_8)
        val aliasCborBytes = Cbor.encodeToByteArray(AliasIndex.serializer(), aliasIndex)

        val speciesMasterJsonText = jsonPretty.encodeToString(ListSerializer(SimpleSpecies.serializer()), simpleSpeciesList)
        val speciesMasterBytes = speciesMasterJsonText.toByteArray(Charsets.UTF_8)
        val speciesMasterCborBytes = Cbor.encodeToByteArray(ListSerializer(SimpleSpecies.serializer()), simpleSpeciesList)

        val phoneticJsonText = jsonPretty.encodeToString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), phoneticMap)
        val phoneticBytes = phoneticJsonText.toByteArray(Charsets.UTF_8)

        // Build manifest object and serialize
        val sources = mapOf(
            "aliasmapping.csv" to mapOf(
                "sha256" to sha256OfBytes(csvBytes),
                "size" to csvBytes.size.toString()
            )
        )
        val indexes = mapOf(
            "aliases_flat" to ManifestIndexEntry(
                path = "Documents/VT5/$BINARIES/$ALIASES_CBOR_GZ",
                sha256 = sha256OfBytes(aliasCborBytes),
                size = aliasCborBytes.size.toString(),
                aliases_count = aliasCount.toString(),
                species_count = null
            ),
            "species_master" to ManifestIndexEntry(
                path = "Documents/VT5/$BINARIES/$SPECIES_CBOR_GZ",
                sha256 = sha256OfBytes(speciesMasterCborBytes),
                size = speciesMasterCborBytes.size.toString(),
                aliases_count = null,
                species_count = speciesIdsInIndex.size.toString()
            )
        )
        val manifest = Manifest(
            version = "v1",
            generated_at = Instant.now().toString(),
            sources = sources,
            indexes = indexes,
            counts = mapOf("aliases" to aliasCount, "species" to speciesIdsInIndex.size),
            notes = "Generated on-device (SAF-only)"
        )
        val manifestBytes = jsonPretty.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

        // Write all outputs to SAF (overwrite semantics)
        val aliasJsonOk = writeBytesToSaFOverwrite(context, saf, ASSETS, ALIAS_JSON_NAME, aliasJsonBytes, "application/json")
        val aliasJsonGzOk = writeBytesToSaFOverwrite(context, saf, ASSETS, ALIAS_JSON_GZ_NAME, gzip(aliasJsonBytes), "application/gzip")
        val aliasCborOk = writeBytesToSaFOverwrite(context, saf, BINARIES, ALIASES_CBOR_GZ, gzip(aliasCborBytes), "application/gzip")
        val speciesCborOk = writeBytesToSaFOverwrite(context, saf, BINARIES, SPECIES_CBOR_GZ, gzip(speciesMasterCborBytes), "application/gzip")
        val speciesSchemaOk = writeBytesToSaFOverwrite(context, saf, SERVERDATA, SPECIES_SCHEMA, speciesMasterBytes, "application/json")
        val aliasesSchemaOk = writeBytesToSaFOverwrite(context, saf, SERVERDATA, ALIASES_SCHEMA, aliasJsonBytes, "application/json")
        val phoneticOk = writeBytesToSaFOverwrite(context, saf, SERVERDATA, PHONETIC_SCHEMA, phoneticBytes, "application/json")
        val manifestOk = writeBytesToSaFOverwrite(context, saf, SERVERDATA, MANIFEST, manifestBytes, "application/json")

        messages += "SAF writes: aliasJson=$aliasJsonOk, aliasJsonGz=$aliasJsonGzOk, aliasCbor=$aliasCborOk, speciesCbor=$speciesCborOk"
        messages += "Serverdata writes: speciesSchema=$speciesSchemaOk, aliasesSchema=$aliasesSchemaOk, phonetic=$phoneticOk, manifest=$manifestOk"

        // Export log
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val exportFilename = "alias_precompute_log_$timestamp.txt"
        val logText = buildString {
            append("Alias precompute log - $timestamp (UTC)\n\n")
            append("aliases_count: $aliasCount\n")
            append("safWrites:\n")
            messages.forEach { append(" - $it\n") }
        }
        val exportOk = writeStringToSaFOverwrite(context, saf, EXPORTS, exportFilename, logText, "text/plain")
        val exportPath = if (exportOk) "Documents/VT5/$EXPORTS/$exportFilename" else null
        if (exportOk) messages += "Wrote export log: $exportPath" else messages += "Failed to write export log"

        val aliasJsonPath = "Documents/VT5/$ASSETS/$ALIAS_JSON_NAME"
        val aliasCborPath = "Documents/VT5/$BINARIES/$ALIASES_CBOR_GZ"
        val speciesCborPath = "Documents/VT5/$BINARIES/$SPECIES_CBOR_GZ"
        val schemas = listOf(
            "Documents/VT5/$SERVERDATA/$ALIASES_SCHEMA",
            "Documents/VT5/$SERVERDATA/$SPECIES_SCHEMA",
            "Documents/VT5/$SERVERDATA/$PHONETIC_SCHEMA",
            "Documents/VT5/$SERVERDATA/$MANIFEST"
        )

        return Result(aliasJsonPath, aliasCborPath, speciesCborPath, "Documents/VT5/$SERVERDATA/$MANIFEST", schemas, exportPath, aliasCount, messages)
    }

    // Helpers: overwrite via SAF
    private fun writeBytesToSaFOverwrite(context: Context, saf: SaFStorageHelper, subDirName: String, filename: String, bytes: ByteArray, mimeType: String = "application/octet-stream"): Boolean {
        val vt5 = saf.getVt5DirIfExists() ?: run {
            Log.w(TAG, "SAF root not set; cannot write $filename")
            return false
        }
        val subdir = vt5.findFile(subDirName)?.takeIf { it.isDirectory } ?: vt5.createDirectory(subDirName) ?: run {
            Log.w(TAG, "Could not create SAF subdir $subDirName")
            return false
        }
        subdir.findFile(filename)?.delete()
        val created = subdir.createFile(mimeType, filename) ?: run {
            Log.w(TAG, "Could not create SAF file $filename in $subDirName")
            return false
        }
        return try {
            context.contentResolver.openOutputStream(created.uri)?.use { os ->
                os.write(bytes)
                os.flush()
            }
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Error writing $filename to SAF: ${ex.message}", ex)
            try { created.delete() } catch (_: Throwable) { /* ignore */ }
            false
        }
    }

    private fun writeStringToSaFOverwrite(context: Context, saf: SaFStorageHelper, subDirName: String, filename: String, content: String, mimeType: String = "text/plain"): Boolean {
        return writeBytesToSaFOverwrite(context, saf, subDirName, filename, content.toByteArray(Charsets.UTF_8), mimeType)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { gz -> gz.write(data) }
        return bos.toByteArray()
    }

    private fun sha256OfBytes(bytes: ByteArray): String {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(bytes)
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("0")
    }
}