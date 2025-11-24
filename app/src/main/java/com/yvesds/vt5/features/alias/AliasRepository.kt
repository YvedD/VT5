@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.alias

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.speech.AliasMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.cbor.Cbor
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * AliasRepository.kt (CSV dependency removed)
 *
 * Responsibilities:
 *  - Load aliases from SAF (preferred: binaries/aliases_optimized.cbor.gz then binaries/alias_master.json)
 *  - Fallback to serverdata/aliases.json (legacy wrapper)
 *  - Maintain an in-memory map speciesId -> SpeciesEntry (AliasModels)
 *  - Maintain reverse map normalized alias -> speciesId
 *  - Provide hot-patch addAliasInMemory()
 *
 * Notes on changes in this patch:
 *  - Replaced GlobalScope usage with a private repoScope (structured concurrency).
 *  - Background persistence scheduled by addAliasInMemory now uses repoScope and is documented as best-effort.
 *  - getAliasesForSpecies / getAllAliases are public API helpers; marked with @Suppress("unused")
 *    (they may look unused in the codebase but are part of the repository API).
 *  - Minor safety and coroutine dispatcher improvements.
 */

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class AliasRepository(private val context: Context) {

    companion object {
        private const val TAG = "AliasRepository"

        // Preferred canonical file names in SAF
        private const val ALIAS_MASTER_FILE = "alias_master.json"
        private const val ALIASES_CBOR_GZ = "aliases_optimized.cbor.gz"

        // Legacy name (kept only to attempt to read if present)
        private const val ALIAS_JSON_FILE = "aliases.json"

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

    // Structured scope for repository background tasks (not tied to UI lifecycle).
    // Uses SupervisorJob so failures in one child do not cancel others.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Load all alias data (async). Preference order:
     * 1) binaries/aliases_optimized.cbor.gz (fast binary AliasIndex)
     * 2) binaries/alias_master.json (canonical human-readable master)
     * 3) serverdata/aliases.json (legacy wrapper)
     *
     * CSV import is NOT performed automatically anywhere in the app.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadAliasData(): Boolean = withContext(Dispatchers.IO) {
        if (isDataLoaded) return@withContext true

        try {
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists()
            if (vt5Dir == null) {
                Log.w(TAG, "SAF VT5 root not available")
                return@withContext false
            }

            // 1) Try binaries/aliases_optimized.cbor.gz (preferred fast path)
            val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory }
            if (binaries != null) {
                val cborDoc = binaries.findFile(ALIASES_CBOR_GZ)
                if (cborDoc != null && cborDoc.isFile) {
                    val bytes = runCatching {
                        appContext.contentResolver.openInputStream(cborDoc.uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val ungz = gunzip(bytes)
                        val idx = try {
                            Cbor.decodeFromByteArray(AliasIndex.serializer(), ungz)
                        } catch (ex: Exception) {
                            Log.w(TAG, "Failed to decode AliasIndex CBOR: ${ex.message}", ex)
                            null
                        }
                        if (idx != null) {
                            // Heavy conversion to aliasCache is performed off-main in Default dispatcher if needed by caller
                            loadFromAliasIndex(idx)
                            buildReverseMapping()
                            isDataLoaded = true
                            return@withContext true
                        }
                    }
                }
            }

            // 2) Try binaries/alias_master.json (human-readable canonical master)
            if (binaries != null) {
                val masterDoc = binaries.findFile(ALIAS_MASTER_FILE)
                if (masterDoc != null && masterDoc.exists()) {
                    val loaded = loadFromAliasesMaster()
                    if (loaded) {
                        buildReverseMapping()
                        isDataLoaded = true
                        return@withContext true
                    }
                }
            }

            // 3) Try serverdata/aliases.json (legacy)
            val serverDataDir = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory }
            val aliasJsonFile = serverDataDir?.findFile(ALIAS_JSON_FILE)
            if (aliasJsonFile != null && aliasJsonFile.exists()) {
                val loaded = loadFromLegacyJson()
                if (loaded) {
                    buildReverseMapping()
                    isDataLoaded = true
                    return@withContext true
                }
            }

            Log.w(TAG, "No alias data loaded (no CBOR, no master.json, no legacy json).")
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
    @Suppress("unused") // Public repository API — may be used by callers outside static analysis scope
    fun getAliasesForSpecies(soortId: String): SpeciesEntry? {
        if (!isDataLoaded) return null
        return aliasCache[soortId]
    }

    /**
     * Return snapshot copy of all species entries
     */
    @Suppress("unused") // Public repository API — may be used by callers outside static analysis scope
    fun getAllAliases(): Map<String, SpeciesEntry> {
        return aliasCache.toMap()
    }

    // ---------- Loaders ----------

    /**
     * Load canonical AliasMaster JSON (binaries/alias_master.json)
     */
    private fun loadFromAliasesMaster(): Boolean {
        try {
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return false
            val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory } ?: return false
            val masterDoc = binaries.findFile(ALIAS_MASTER_FILE) ?: return false

            val jsonString = appContext.contentResolver.openInputStream(masterDoc.uri)?.bufferedReader()?.use { it.readText() }
                ?: return false

            val master = json.decodeFromString<AliasMaster>(jsonString)

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
     * Load alias index from an in-memory AliasIndex object (CBOR decoded).
     * Converts AliasIndex -> SpeciesEntry map.
     *
     * This conversion is cheap enough to run here (called from dispatcher IO),
     * but keep it simple and thread-safe (synchronized on aliasCache).
     */
    private fun loadFromAliasIndex(index: AliasIndex) {
        try {
            val grouped = index.json.groupBy { it.speciesid }
            val localMap = mutableMapOf<String, SpeciesEntry>()
            for ((sid, recs) in grouped) {
                val canonical = recs.firstOrNull()?.canonical ?: sid
                val tilename = recs.firstOrNull()?.tilename
                val aliasesList = recs.map { r ->
                    AliasData(
                        text = r.alias,
                        norm = r.norm,
                        cologne = r.cologne ?: "",
                        phonemes = r.phonemes ?: "",
                        source = r.source,
                        timestamp = null
                    )
                }
                localMap[sid] = SpeciesEntry(speciesId = sid, canonical = canonical, tilename = tilename, aliases = aliasesList)
            }
            // Swap in atomically
            synchronized(aliasCache) {
                aliasCache.clear()
                aliasCache.putAll(localMap)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "loadFromAliasIndex failed: ${ex.message}", ex)
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
                json.decodeFromString<LegacyAliasWrapper>(jsonString)
            } catch (ex: Exception) {
                Log.w(TAG, "loadFromLegacyJson: cannot decode legacy wrapper: ${ex.message}")
                return false
            }

            val localMap = mutableMapOf<String, SpeciesEntry>()
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
                localMap[speciesId] = speciesEntry
            }

            synchronized(aliasCache) {
                aliasCache.clear()
                aliasCache.putAll(localMap)
            }

            return true
        } catch (ex: Exception) {
            Log.w(TAG, "loadFromLegacyJson failed: ${ex.message}", ex)
            return false
        }
    }

    /**
     * Build reverse mapping alias -> speciesId using canonical, tilename and all aliases
     */
    private fun buildReverseMapping() {
        val localMap = mutableMapOf<String, String>()
        for ((soortId, entry) in aliasCache) {
            // canonical (normalize)
            val canon = entry.canonical.takeIf { it.isNotBlank() } ?: ""
            if (canon.isNotBlank()) localMap[normalizeForKey(canon)] = soortId

            // tilename
            entry.tilename?.takeIf { it.isNotBlank() }?.let { localMap[normalizeForKey(it)] = soortId }

            // aliases list (AliasData)
            entry.aliases.forEach { a ->
                val key = normalizeForKey(a.text)
                if (key.isNotBlank()) localMap[key] = soortId
            }
        }
        aliasToSpeciesIdMap.clear()
        aliasToSpeciesIdMap.putAll(localMap)
    }

    /**
     * addAliasInMemory: add alias to in-memory structures (hot-reload)
     *
     * Replaces deprecated GlobalScope usage with a structured repoScope.
     * Background persistence uses AliasManager.addAlias(...) (best-effort).
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

                // Prevent duplicates (by normalized text)
                if (currentAliases.any { it.text.equals(alias, ignoreCase = true) }) return false

                // Build AliasData for in-memory cache
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

                // Update reverse map synchronously
                aliasToSpeciesIdMap[normalizeForKey(alias)] = soortId

                // Hotpatch AliasMatcher so the new alias is immediately matchable
                try {
                    com.yvesds.vt5.features.speech.AliasMatcher.addAliasHotpatch(
                        speciesId = soortId,
                        aliasRaw = alias,
                        canonical = canonical,
                        tilename = tilename
                    )
                } catch (ex: Exception) {
                    Log.w(TAG, "AliasMatcher.hotpatch failed: ${ex.message}", ex)
                }

                // Schedule background persistence using AliasManager (non-blocking, structured)
                try {
                    val bgContext = appContext
                    val saf = SaFStorageHelper(bgContext)
                    repoScope.launch {
                        try {
                            AliasManager.addAlias(bgContext, saf, soortId, alias, canonical, tilename)
                        } catch (ex: Exception) {
                            Log.w(TAG, "Background AliasManager.addAlias failed: ${ex.message}", ex)
                        }
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed to schedule background persist: ${ex.message}", ex)
                }
            }

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

    // -----------------------------
    // Small utilities
    // -----------------------------
    private fun gunzip(input: ByteArray): ByteArray {
        return try {
            GZIPInputStream(input.inputStream()).use { gis ->
                gis.readBytes()
            }
        } catch (ex: Exception) {
            ByteArray(0)
        }
    }
}