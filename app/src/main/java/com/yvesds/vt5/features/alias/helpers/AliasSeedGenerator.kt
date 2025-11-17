package com.yvesds.vt5.features.alias.helpers

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasData
import com.yvesds.vt5.features.alias.AliasMaster
import com.yvesds.vt5.features.alias.SpeciesEntry
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.speech.ColognePhonetic
import com.yvesds.vt5.features.speech.DutchPhonemizer
import com.yvesds.vt5.utils.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * AliasSeedGenerator: Generates initial alias master from species data.
 * 
 * Responsibilities:
 * - Read site_species.json from SAF
 * - Parse species IDs and names
 * - Generate phonetic encodings
 * - Build initial AliasMaster seed
 * - Write master and CBOR to SAF
 * 
 * This is used during first install or when regenerating the index.
 */
object AliasSeedGenerator {
    
    private const val TAG = "AliasSeedGenerator"
    
    /**
     * Generate seed from species.json in serverdata directory.
     * Writes the resulting master to SAF assets and CBOR to binaries.
     */
    suspend fun generateSeed(
        context: Context,
        saf: SaFStorageHelper,
        vt5RootDir: DocumentFile
    ) = withContext(Dispatchers.IO) {
        try {
            // Locate serverdata
            val serverDir = vt5RootDir.findFile("serverdata")?.takeIf { it.isDirectory }
            if (serverDir == null) {
                Log.w(TAG, "serverdata not available, aborting seed generation")
                return@withContext
            }
            
            // List contents for diagnostics
            kotlin.runCatching {
                val present = serverDir.listFiles().mapNotNull { it.name }
                Log.i(TAG, "serverdata contains: ${present.joinToString(", ")}")
            }
            
            // Tolerant lookup for site_species file
            val siteSpeciesFile = serverDir.listFiles().firstOrNull { doc ->
                val nm = doc.name?.lowercase() ?: return@firstOrNull false
                nm == "site_species.json" || nm == "site_species" || nm.startsWith("site_species")
            }
            
            if (siteSpeciesFile == null) {
                Log.w(TAG, "No site_species file found in serverdata")
                return@withContext
            } else {
                Log.i(TAG, "Using site_species file: ${siteSpeciesFile.name}")
            }
            
            // Read site_species content
            val siteBytes: ByteArray? = kotlin.runCatching {
                context.contentResolver.openInputStream(siteSpeciesFile.uri)?.use { it.readBytes() }
            }.getOrNull()
            
            if (siteBytes == null || siteBytes.isEmpty()) {
                Log.w(TAG, "site_species file is empty or could not be read")
                return@withContext
            }
            
            // Strip BOM if present
            val bytesNoBom = if (siteBytes.size >= 3 && 
                siteBytes[0] == 0xEF.toByte() && 
                siteBytes[1] == 0xBB.toByte() && 
                siteBytes[2] == 0xBF.toByte()) {
                siteBytes.copyOfRange(3, siteBytes.size)
            } else siteBytes
            
            val text = bytesNoBom.toString(Charsets.UTF_8).trim()
            
            // Parse species IDs
            val siteSpeciesIds = parseSiteSpeciesIds(text)
            if (siteSpeciesIds.isEmpty()) {
                Log.w(TAG, "No site_species entries found — aborting seed generation")
                return@withContext
            }
            
            // Load species map
            val speciesMap = loadSpeciesMap(context, serverDir)
            
            // Build species list
            val speciesList = buildSpeciesList(siteSpeciesIds, speciesMap)
            
            // Create master
            val newMaster = AliasMaster(
                version = "2.1",
                timestamp = Instant.now().toString(),
                species = speciesList
            )
            
            // Write master and CBOR to SAF
            AliasMasterIO.writeMasterAndCbor(context, newMaster, vt5RootDir, saf)
            
            // Hot-load CBOR into matcher
            try {
                com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(context, saf)
            } catch (_: Exception) {}
            
            Log.i(TAG, "Seed generated: ${newMaster.species.size} species, ${newMaster.species.sumOf { it.aliases.size }} total aliases")
        } catch (ex: Exception) {
            Log.e(TAG, "Seed generation failed: ${ex.message}", ex)
        }
    }
    
    /**
     * Parse site_species IDs from JSON.
     */
    private fun parseSiteSpeciesIds(text: String): Set<String> {
        val siteSpeciesIds = mutableSetOf<String>()
        
        kotlin.runCatching {
            val root = Json.parseToJsonElement(text)
            var arr = root.jsonArrayOrNull()
            
            if (arr == null && root is JsonObject) {
                arr = root["json"]?.jsonArray 
                    ?: root["data"]?.jsonArray 
                    ?: root["items"]?.jsonArray
            }
            
            // Fallback: search for first array of objects
            if (arr == null && root is JsonObject) {
                for ((_, v) in root) {
                    if (v is JsonArray) { arr = v; break }
                }
            }
            
            // Try recursive search
            if (arr == null) {
                arr = root.findFirstArrayWithObjects()
            }
            
            if (arr != null) {
                arr.forEach { el ->
                    if (el is JsonObject) {
                        val sid = el["soortid"]?.jsonPrimitive?.contentOrNull
                            ?: el["soort_id"]?.jsonPrimitive?.contentOrNull
                            ?: el["soortId"]?.jsonPrimitive?.contentOrNull
                            ?: el["id"]?.jsonPrimitive?.contentOrNull
                        
                        if (!sid.isNullOrBlank()) siteSpeciesIds.add(sid.lowercase().trim())
                    }
                }
            } else {
                Log.w(TAG, "site_species parsed but no usable array found")
            }
        }.onFailure {
            Log.w(TAG, "Failed to parse site_species content: ${it.message}", it)
        }
        
        return siteSpeciesIds
    }
    
    /**
     * Load species map from ServerDataCache or species.json.
     */
    private suspend fun loadSpeciesMap(
        context: Context,
        serverDir: DocumentFile
    ): Map<String, Pair<String, String?>> = withContext(Dispatchers.IO) {
        val speciesMap = mutableMapOf<String, Pair<String, String?>>()
        
        // Try ServerDataCache first
        val snapshot = kotlin.runCatching { ServerDataCache.getOrLoad(context) }.getOrNull()
        if (snapshot != null) {
            snapshot.speciesById.forEach { (k, v) ->
                speciesMap[k.lowercase()] = Pair(
                    v.soortnaam ?: k,
                    v.soortkey?.takeIf { it.isNotBlank() }
                )
            }
            return@withContext speciesMap
        }
        
        // Fallback to reading species.json from serverdata
        val speciesFile = serverDir.findFile("species.json")?.takeIf { it.isFile }
        val speciesBytes = speciesFile?.let { doc ->
            kotlin.runCatching {
                context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            }.getOrNull()
        }
        
        if (speciesBytes != null) {
            kotlin.runCatching {
                val root = Json.parseToJsonElement(speciesBytes.toString(Charsets.UTF_8))
                val arr = root.jsonArrayOrNull() ?: root.jsonObject["json"]?.jsonArray
                
                arr?.forEach { el ->
                    if (el is JsonObject) {
                        val sid = el["soortid"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: return@forEach
                        val naam = el["soortnaam"]?.jsonPrimitive?.contentOrNull ?: sid
                        val key = el["soortkey"]?.jsonPrimitive?.contentOrNull
                        speciesMap[sid] = Pair(naam, key?.takeIf { it.isNotBlank() })
                    }
                }
            }.onFailure {
                Log.w(TAG, "Failed to parse species.json: ${it.message}")
            }
        }
        
        speciesMap
    }
    
    /**
     * Build sorted species list with generated aliases.
     */
    private fun buildSpeciesList(
        siteSpeciesIds: Set<String>,
        speciesMap: Map<String, Pair<String, String?>>
    ): List<SpeciesEntry> {
        // Sort species IDs deterministically
        val sidList = siteSpeciesIds.toList().sortedWith(Comparator { a, b ->
            val ai = a.toIntOrNull()
            val bi = b.toIntOrNull()
            when {
                ai != null && bi != null -> ai.compareTo(bi)
                ai != null && bi == null -> -1
                ai == null && bi != null -> 1
                else -> a.compareTo(b)
            }
        })
        
        return sidList.map { sid ->
            val (naamRaw, keyRaw) = speciesMap[sid] ?: Pair(sid, null)
            val canonical = naamRaw ?: sid
            val tilename = keyRaw
            
            val canonicalAlias = generateAliasData(text = canonical, source = "seed_canonical")
            val tilenameAlias = if (!tilename.isNullOrBlank() && !tilename.equals(canonical, ignoreCase = true)) {
                generateAliasData(text = tilename, source = "seed_tilename")
            } else null
            
            SpeciesEntry(
                speciesId = sid,
                canonical = canonical,
                tilename = tilename,
                aliases = listOfNotNull(canonicalAlias, tilenameAlias)
            )
        }
    }
    
    /**
     * Generate AliasData with phonetic encodings.
     */
    private fun generateAliasData(text: String, source: String): AliasData {
        val cleaned = TextUtils.normalizeLowerNoDiacritics(text)
        val col = runCatching { ColognePhonetic.encode(cleaned) }.getOrNull() ?: ""
        val phon = runCatching { DutchPhonemizer.phonemize(cleaned) }.getOrNull() ?: ""
        
        return AliasData(
            text = text.trim().lowercase(),
            norm = cleaned,
            cologne = col,
            phonemes = phon,
            source = source,
            timestamp = if (source == "user_field_training") Instant.now().toString() else null
        )
    }
    
    private fun JsonElement.jsonArrayOrNull() = try { this.jsonArray } catch (_: Throwable) { null }
    
    private fun JsonElement.findFirstArrayWithObjects(): JsonArray? {
        when (this) {
            is JsonArray -> {
                if (this.any { it is JsonObject }) return this
                for (el in this) {
                    val found = el.findFirstArrayWithObjects()
                    if (found != null) return found
                }
            }
            is JsonObject -> {
                for ((_, v) in this) {
                    val found = v.findFirstArrayWithObjects()
                    if (found != null) return found
                }
            }
            else -> {}
        }
        return null
    }
}
