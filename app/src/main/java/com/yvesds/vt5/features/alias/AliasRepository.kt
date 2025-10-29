@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.alias

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.speech.AliasMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

/**
 * AliasRepository.kt
 *
 * Responsibilities:
 *  - Load aliases from SAF (preferred: binaries/aliases_master.json)
 *  - Fallback to serverdata/aliases.json (legacy) or aliasmapping.csv
 *  - Maintain an in-memory map speciesId -> SpeciesEntry (AliasModels)
 *  - Maintain reverse map normalized alias -> speciesId
 *  - Provide hot-patch addAliasInMemory()
 *  - Convert CSV -> legacy JSON (if requested)
 *
 * Notes:
 *  - Legacy wrapper types are defined below as LegacyAliasWrapper/LegacyAliasEntry.
 *  - The canonical runtime models are in AliasModels.kt (AliasMaster, SpeciesEntry, AliasData, AliasIndex, AliasRecord).
 */

// Reused Json instance for this file to avoid repeated allocations
private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class AliasRepository(private val context: Context) {

    companion object {
        private const val TAG = "AliasRepository"

        // Preferred canonical file in binaries
        private const val ALIAS_MASTER_FILE = "aliases_master.json"

        // Legacy names (kept for compatibility if present)
        private const val ALIAS_JSON_FILE = "aliases.json"
        private const val ALIAS_CSV_FILE = "aliasmapping.csv"

        const val ACTION_ALIAS_RELOAD_STARTED = "com.yvesds.vt5.ALIAS_RELOAD_STARTED"
        const val ACTION_ALIAS_RELOAD_COMPLETED = "com.yvesds.vt5.ALIAS_RELOAD_COMPLETED"
        const val EXTRA_RELOAD_SUCCESS = "com.yvesds.vt5.EXTRA_RELOAD_SUCCESS"

        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: AliasRepository? = null

        fun getInstance(context: Context): AliasRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AliasRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext

    // In-memory canonical cache: speciesId -> SpeciesEntry
    private val aliasCache = ConcurrentHashMap<String, SpeciesEntry>()

    // Reverse map: normalized alias -> speciesId
    private val aliasToSpeciesIdMap = ConcurrentHashMap<String, String>()

    private var isDataLoaded = false

    /**
     * Load all alias data (async). Preference order:
     * 1) binaries/aliases_master.json (AliasMaster)
     * 2) serverdata/aliases.json (legacy wrapper)
     * 3) assets/aliasmapping.csv (legacy CSV)
     */
    suspend fun loadAliasData(): Boolean = withContext(Dispatchers.IO) {
        if (isDataLoaded) return@withContext true

        try {
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists()
            if (vt5Dir == null) {
                Log.w(TAG, "SAF VT5 root not available")
                return@withContext false
            }

            // 1) Try binaries/aliases_master.json (preferred)
            val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory }
            val masterDoc = binaries?.findFile(ALIAS_MASTER_FILE)
            if (masterDoc != null && masterDoc.exists()) {
                val loaded = loadFromAliasesMaster()
                if (loaded) {
                    buildReverseMapping()
                    isDataLoaded = true
                    Log.d(TAG, "Loaded aliases from binaries/$ALIAS_MASTER_FILE: ${aliasCache.size} species")
                    return@withContext true
                }
            }

            // 2) Try serverdata/aliases.json (legacy)
            val serverDataDir = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory }
            val aliasJsonFile = serverDataDir?.findFile(ALIAS_JSON_FILE)
            if (aliasJsonFile != null && aliasJsonFile.exists()) {
                val loaded = loadFromLegacyJson()
                if (loaded) {
                    buildReverseMapping()
                    isDataLoaded = true
                    Log.d(TAG, "Loaded aliases from serverdata/$ALIAS_JSON_FILE: ${aliasCache.size} species")
                    return@withContext true
                }
            }

            // 3) Fallback: CSV in assets
            val csvLoaded = loadFromCsv()
            if (csvLoaded) {
                buildReverseMapping()
                isDataLoaded = true
                Log.d(TAG, "Loaded aliases from CSV: ${aliasCache.size} species")
                return@withContext true
            }

            Log.w(TAG, "No alias data loaded")
            return@withContext false
        } catch (ex: Exception) {
            Log.e(TAG, "loadAliasData failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /**
     * Find speciesId for an alias (normalized lookup)
     */
    fun findSpeciesIdByAlias(text: String): String? {
        if (!isDataLoaded) return null
        val normalized = normalizeForKey(text)
        return aliasToSpeciesIdMap[normalized]
    }

    /**
     * Return SpeciesEntry for species id, or null
     */
    fun getAliasesForSpecies(soortId: String): SpeciesEntry? {
        if (!isDataLoaded) return null
        return aliasCache[soortId]
    }

    /**
     * Return snapshot copy of all species entries
     */
    fun getAllAliases(): Map<String, SpeciesEntry> {
        return aliasCache.toMap()
    }

    // ---------- Loaders ----------

    /**
     * Load canonical AliasMaster JSON (binaries/aliases_master.json)
     */
    private fun loadFromAliasesMaster(): Boolean {
        try {
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return false
            val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory } ?: return false
            val masterDoc = binaries.findFile(ALIAS_MASTER_FILE) ?: return false

            val jsonString = appContext.contentResolver.openInputStream(masterDoc.uri)?.bufferedReader()?.use { it.readText() }
                ?: return false

            val master = json.decodeFromString(AliasMaster.serializer(), jsonString)

            aliasCache.clear()
            for (entry in master.species) {
                aliasCache[entry.speciesId] = entry
            }
            return true
        } catch (ex: Exception) {
            Log.w(TAG, "loadFromAliasesMaster failed: ${ex.message}", ex)
            return false
        }
    }

    /**
     * Load legacy single-file aliases JSON (serverdata/aliases.json)
     * Maps legacy format into canonical SpeciesEntry objects.
     */
    private fun loadFromLegacyJson(): Boolean {
        try {
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return false
            val serverData = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory } ?: return false
            val jsonFile = serverData.findFile(ALIAS_JSON_FILE) ?: return false

            val jsonString = appContext.contentResolver.openInputStream(jsonFile.uri)?.bufferedReader()?.use { it.readText() }
                ?: return false

            // Decode legacy wrapper structure
            val legacy = try {
                json.decodeFromString(LegacyAliasWrapper.serializer(), jsonString)
            } catch (ex: Exception) {
                Log.w(TAG, "loadFromLegacyJson: cannot decode legacy wrapper: ${ex.message}")
                return false
            }

            aliasCache.clear()
            for (e in legacy.aliases) {
                val speciesId = e.soortId
                val canonical = e.canonicalName
                val tilename = e.displayName.takeIf { it.isNotBlank() } ?: canonical
                val listAliases = e.aliases.map { it.trim() }.filter { it.isNotBlank() }

                val speciesEntry = SpeciesEntry(
                    speciesId = speciesId,
                    canonical = canonical,
                    tilename = tilename,
                    aliases = listAliases.map { a ->
                        AliasData(
                            text = a,
                            norm = normalizeForKey(a),
                            cologne = runCatching { com.yvesds.vt5.features.speech.ColognePhonetic.encode(normalizeForKey(a)) }.getOrNull()
                                ?: "",
                            phonemes = runCatching { com.yvesds.vt5.features.speech.DutchPhonemizer.phonemize(normalizeForKey(a)) }.getOrNull()
                                ?: "",
                            source = "seed_import",
                            timestamp = null
                        )
                    }
                )
                aliasCache[speciesId] = speciesEntry
            }
            return true
        } catch (ex: Exception) {
            Log.w(TAG, "loadFromLegacyJson failed: ${ex.message}", ex)
            return false
        }
    }

    /**
     * Load from CSV fallback (assets/aliasmapping.csv). Builds SpeciesEntry objects.
     */
    private fun loadFromCsv(): Boolean {
        try {
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return false
            val assetsDir = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: return false
            val csvDoc = assetsDir.findFile(ALIAS_CSV_FILE) ?: return false

            val inputStream = appContext.contentResolver.openInputStream(csvDoc.uri) ?: return false
            inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    var count = 0
                    aliasCache.clear()
                    while (reader.readLine().also { line = it } != null) {
                        if (line.isNullOrBlank()) continue
                        val parts = line!!.split(";").map { it.trim() }
                        if (parts.size < 2) continue

                        val soortId = parts[0]
                        val canonical = parts.getOrNull(1) ?: soortId
                        val tilename = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: canonical
                        val aliases = if (parts.size > 3) parts.drop(3).map { it.trim() } else emptyList()

                        val speciesEntry = SpeciesEntry(
                            speciesId = soortId,
                            canonical = canonical,
                            tilename = tilename,
                            aliases = aliases.map { a ->
                                AliasData(
                                    text = a,
                                    norm = normalizeForKey(a),
                                    cologne = runCatching { com.yvesds.vt5.features.speech.ColognePhonetic.encode(normalizeForKey(a)) }.getOrNull() ?: "",
                                    phonemes = runCatching { com.yvesds.vt5.features.speech.DutchPhonemizer.phonemize(normalizeForKey(a)) }.getOrNull() ?: "",
                                    source = "seed_import",
                                    timestamp = null
                                )
                            }
                        )
                        aliasCache[soortId] = speciesEntry
                        count++
                    }
                    Log.d(TAG, "Loaded $count alias CSV entries")
                    return count > 0
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "loadFromCsv failed: ${ex.message}", ex)
            return false
        }
    }

    /**
     * Build reverse mapping alias -> speciesId using canonical, tilename and all aliases
     */
    private fun buildReverseMapping() {
        aliasToSpeciesIdMap.clear()
        for ((soortId, entry) in aliasCache) {
            // canonical (normalize)
            val canon = entry.canonical.takeIf { it.isNotBlank() } ?: ""
            if (canon.isNotBlank()) aliasToSpeciesIdMap[normalizeForKey(canon)] = soortId

            // tilename
            entry.tilename?.takeIf { it.isNotBlank() }?.let { aliasToSpeciesIdMap[normalizeForKey(it)] = soortId }

            // aliases list (AliasData)
            entry.aliases.forEach { a ->
                // a is AliasData
                val key = normalizeForKey(a.text)
                if (key.isNotBlank()) aliasToSpeciesIdMap[key] = soortId
            }
        }
        Log.d(TAG, "Built reverse mapping with ${aliasToSpeciesIdMap.size} entries")
    }

    /**
     * Convert CSV -> aliases_master.json (writes AliasMaster to binaries/aliases_master.json)
     */
    suspend fun convertCsvToJson(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Ensure CSV is present and parse
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return@withContext false
            val assetsDir = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: return@withContext false
            val csvDoc = assetsDir.findFile(ALIAS_CSV_FILE) ?: return@withContext false

            val csvText = appContext.contentResolver.openInputStream(csvDoc.uri)?.bufferedReader()?.use { it.readText() } ?: return@withContext false
            val index = PrecomputeAliasIndex.buildFromCsv(csvText, q = 3)

            // Convert AliasIndex -> AliasMaster
            val speciesMap = index.json.groupBy { it.speciesid }
            val speciesList = speciesMap.map { (sid, recs) ->
                val canonical = recs.firstOrNull()?.canonical ?: sid
                val tilename = recs.firstOrNull()?.tilename
                val aliasesList = recs.map { r ->
                    AliasData(
                        text = r.alias,
                        norm = r.norm,
                        cologne = r.cologne ?: "",
                        phonemes = r.phonemes ?: "",
                        source = r.source
                    )
                }
                SpeciesEntry(speciesId = sid, canonical = canonical, tilename = tilename, aliases = aliasesList)
            }

            val master = AliasMaster(version = "2.1", timestamp = Instant.now().toString(), species = speciesList)

            // Write to binaries/aliases_master.json
            val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("binaries") ?: return@withContext false
            val outFile = binaries.findFile(ALIAS_MASTER_FILE)?.also { it.delete() } ?: binaries.createFile("application/json", ALIAS_MASTER_FILE) ?: return@withContext false
            val jsonText = json.encodeToString(AliasMaster.serializer(), master)
            appContext.contentResolver.openOutputStream(outFile.uri)?.use { os ->
                os.write(jsonText.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: return@withContext false

            Log.d(TAG, "Wrote aliases_master.json with ${speciesList.size} species")
            return@withContext true
        } catch (ex: Exception) {
            Log.e(TAG, "convertCsvToJson failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /**
     * addAliasInMemory: add alias to in-memory structures (hot-reload)
     */
    fun addAliasInMemory(soortId: String, aliasRaw: String): Boolean {
        try {
            var alias = aliasRaw.trim()
            alias = alias.replace(Regex("(?i)^\\s*asr:\\s*"), "")
            alias = alias.replace("/", " of ")
            alias = alias.replace(";", " ")
            alias = alias.replace(Regex("(?:\\s+\\d+)+\\s*$"), "")
            alias = alias.replace(Regex("\\s+"), " ").trim()
            alias = alias.lowercase(Locale.getDefault())
            if (alias.isBlank()) return false

            synchronized(aliasCache) {
                val existing = aliasCache[soortId]
                val currentAliases = existing?.aliases?.toMutableList() ?: mutableListOf()

                // Prevent duplicates
                if (currentAliases.any { it.text.equals(alias, ignoreCase = true) }) return false

                // add as AliasData
                val newAlias = AliasData(
                    text = alias,
                    norm = normalizeForKey(alias),
                    cologne = runCatching { com.yvesds.vt5.features.speech.ColognePhonetic.encode(normalizeForKey(alias)) }.getOrNull() ?: "",
                    phonemes = runCatching { com.yvesds.vt5.features.speech.DutchPhonemizer.phonemize(normalizeForKey(alias)) }.getOrNull() ?: "",
                    source = "user_field_training",
                    timestamp = Instant.now().toString()
                )

                val newList = currentAliases.toMutableList()
                newList.add(newAlias)
                val canonical = existing?.canonical ?: soortId
                val tilename = existing?.tilename

                aliasCache[soortId] = SpeciesEntry(
                    speciesId = soortId,
                    canonical = canonical,
                    tilename = tilename,
                    aliases = newList
                )

                // Update reverse map
                aliasToSpeciesIdMap[normalizeForKey(alias)] = soortId
            }

            Log.d(TAG, "addAliasInMemory: added alias '$alias' for species $soortId")
            return true
        } catch (ex: Exception) {
            Log.w(TAG, "addAliasInMemory failed: ${ex.message}", ex)
            return false
        }
    }

    private fun normalizeForKey(input: String): String {
        val lower = input.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }

    /**
     * Trigger AliasMatcher reload (low-priority helper).
     */
    suspend fun reloadMatcherIfNeeded(context: Context, saf: SaFStorageHelper): Boolean = withContext(Dispatchers.IO) {
        try {
            AliasMatcher.reloadIndex(context, saf)
            Log.i(TAG, "AliasMatcher.reloadIndex completed")
            return@withContext true
        } catch (ex: Exception) {
            Log.w(TAG, "AliasMatcher.reloadIndex failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    // -----------------------------
    // Legacy wrapper DTOs (for reading/writing old single-file JSON)
    // -----------------------------
    @Serializable
    data class LegacyAliasWrapper(
        val aliases: List<LegacyAliasEntry>
    )

    @Serializable
    data class LegacyAliasEntry(
        val soortId: String,
        val canonicalName: String,
        val displayName: String,
        val aliases: List<String>
    )
}