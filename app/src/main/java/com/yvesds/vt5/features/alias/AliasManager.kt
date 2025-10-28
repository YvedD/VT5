package com.yvesds.vt5.features.alias

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.speech.ColognePhonetic
import com.yvesds.vt5.features.speech.DutchPhonemizer
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant

/**
 * AliasManager.kt
 *
 * PURPOSE:
 * Central alias management system with hot-reload capabilities.
 * SINGLE SOURCE OF TRUTH for all alias operations.
 *
 * ARCHITECTURE (3-Layer System):
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ LAYER 1: PERSISTENT STORAGE (SAF Documents/VT5/binaries/)  │
 * ├─────────────────────────────────────────────────────────────┤
 * │ aliases_master.json (Human-readable, pretty JSON)           │
 * │ ├─ Read: App start (warm cache)                            │
 * │ └─ Write: Batched (every 5 additions or 30s)              │
 * │                                                              │
 * │ aliases_optimized.cbor.gz (Binary cache, fast loading)      │
 * │ └─ Regenerated: Async when master changes                  │
 * └─────────────────────────────────────────────────────────────┘
 *                          ↕
 * ┌─────────────────────────────────────────────────────────────┐
 * │ LAYER 2: IN-MEMORY CACHE (AliasMatcher)                    │
 * ├─────────────────────────────────────────────────────────────┤
 * │ aliasMap: ConcurrentHashMap<String, List<AliasRecord>>     │
 * │ phoneticCache: ConcurrentHashMap<String, String>           │
 * │ bloomFilter: ConcurrentHashMap<Long, Boolean>              │
 * │                                                              │
 * │ → Hot-patchable: addAliasHotpatch() updates instantly      │
 * │ → Thread-safe: ConcurrentHashMap for multi-coroutine       │
 * └─────────────────────────────────────────────────────────────┘
 *                          ↕
 * ┌─────────────────────────────────────────────────────────────┐
 * │ LAYER 3: WRITE QUEUE (Background Worker)                   │
 * ├─────────────────────────────────────────────────────────────┤
 * │ writeQueue: ConcurrentHashMap<String, PendingAlias>        │
 * │                                                              │
 * │ Flow:                                                        │
 * │ 1. User taps log → addAlias()                              │
 * │ 2. INSTANT: Hot-patch Layer 2 (0.2ms)                      │
 * │ 3. ASYNC: Add to writeQueue                                │
 * │ 4. BATCHED: Flush every 5 adds or 30s                      │
 * │    ├─ Merge into master.json                               │
 * │    └─ Regenerate CBOR cache (background)                   │
 * └─────────────────────────────────────────────────────────────┘
 *
 * KEY FEATURES:
 * - NO HERSTART: Hot-patch makes aliases instantly active
 * - THREAD-SAFE: ConcurrentHashMap for parallel access
 * - BATCHED WRITES: Performance + data safety (30s max latency)
 * - SEED GENERATION: Auto-generate from species.json on first install
 *
 * USAGE:
 * ```kotlin
 * // App start (VT5App.onCreate or InstallatieScherm)
 * AliasManager.initialize(context, saf)
 *
 * // User adds alias (TellingScherm gesture detector)
 * AliasManager.addAlias(context, saf, "20", "ali", "Aalscholver", "Aal")
 * // → Instant hot-patch! User can immediately say "ali 5"
 *
 * // App pause/destroy (force flush pending writes)
 * AliasManager.forceFlush(context, saf)
 * ```
 *
 * AUTHOR: VT5 Team (YvedD)
 * DATE: 2025-10-28
 * VERSION: 2.1
 */
object AliasManager {

    private const val TAG = "AliasManager"

    /*═══════════════════════════════════════════════════════════════... */
    /* FILE PATHS & CONSTANTS */
    private const val MASTER_FILE = "aliases_master.json"
    private const val CBOR_FILE = "aliases_optimized.cbor.gz"
    private const val BINARIES = "binaries"

    /* JSON/CBOR SERIALIZERS */
    private val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonCompact = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /* WRITE QUEUE */
    private val writeQueue = ConcurrentHashMap<String, PendingAlias>()
    private val writePending = AtomicBoolean(false)
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writeJob: Job? = null

    private const val BATCH_SIZE_THRESHOLD = 5
    private const val BATCH_TIME_THRESHOLD_MS = 30_000L

    private data class PendingAlias(
        val speciesId: String,
        val aliasText: String,
        val canonical: String,
        val tilename: String?,
        val timestamp: String
    )

    /* INITIALIZATION */
    suspend fun initialize(context: Context, saf: SaFStorageHelper): Boolean = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists()
            if (vt5 == null) {
                Log.e(TAG, "SAF VT5 root not set")
                return@withContext false
            }

            val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory }
                ?: vt5.createDirectory(BINARIES)

            if (binaries == null) {
                Log.e(TAG, "Cannot create binaries directory")
                return@withContext false
            }

            // Check if master file exists
            val masterDoc = binaries.findFile(MASTER_FILE)

            if (masterDoc != null && masterDoc.exists()) {
                // Existing installation: load master
                Log.i(TAG, "Loading existing aliases_master.json...")

                val masterJson = context.contentResolver.openInputStream(masterDoc.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }

                if (masterJson != null) {
                    val master = jsonPretty.decodeFromString<AliasMaster>(masterJson)
                    Log.i(TAG, "Loaded ${master.species.size} species, ${master.species.sumOf { it.aliases.size }} total aliases")

                    // Ensure CBOR cache exists and is up-to-date
                    val cborDoc = binaries.findFile(CBOR_FILE)
                    if (cborDoc == null || !cborDoc.exists()) {
                        Log.w(TAG, "CBOR cache missing, regenerating...")
                        rebuildCborCache(master, binaries, context)
                    }

                    // Hot-load into AliasMatcher
                    com.yvesds.vt5.features.speech.AliasMatcher.ensureLoaded(context, saf)

                    return@withContext true
                }
            }

            // First install: generate seed from species.json
            Log.i(TAG, "First install detected, generating seed from species.json...")
            generateSeedFromSpeciesJson(context, saf, binaries)

            return@withContext true

        } catch (ex: Exception) {
            Log.e(TAG, "Initialize failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /* ADD ALIAS (HOT-RELOAD) */
    suspend fun addAlias(
        context: Context,
        saf: SaFStorageHelper,
        speciesId: String,
        aliasText: String,
        canonical: String,
        tilename: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalizedText = aliasText.trim()
            if (normalizedText.isBlank()) {
                Log.w(TAG, "addAlias: empty")
                return@withContext false
            }

            // Fast duplicate check in-memory (AliasMatcher)
            val found = com.yvesds.vt5.features.speech.AliasMatcher.findExact(normalizeLowerNoDiacritics(normalizedText), context, saf)
            if (found.isNotEmpty()) {
                val existingSpecies = found.first().speciesid
                if (existingSpecies == speciesId) {
                    Log.w(TAG, "addAlias: duplicate alias for same species")
                    return@withContext false
                } else {
                    Log.w(TAG, "addAlias: alias already exists for species $existingSpecies")
                    return@withContext false
                }
            }

            // 1) Hot-patch in-memory (AliasMatcher)
            com.yvesds.vt5.features.speech.AliasMatcher.addAliasHotpatch(
                speciesId = speciesId,
                aliasRaw = normalizedText,
                canonical = canonical,
                tilename = tilename
            )

            // 2) Add to in-memory write queue for batched persistence
            val key = "$speciesId||${normalizeLowerNoDiacritics(normalizedText)}"
            val pending = PendingAlias(
                speciesId = speciesId,
                aliasText = normalizedText,
                canonical = canonical,
                tilename = tilename,
                timestamp = Instant.now().toString()
            )
            writeQueue[key] = pending

            // 3) Schedule batched write (existing logic)
            scheduleBatchWrite(context, saf)

            Log.i(TAG, "addAlias: hotpatched and queued alias='$normalizedText' for species=$speciesId")
            return@withContext true
        } catch (ex: Exception) {
            Log.e(TAG, "addAlias failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /* FORCE FLUSH */
    suspend fun forceFlush(context: Context, saf: SaFStorageHelper) {
        writeJob?.cancel()
        if (writeQueue.isNotEmpty()) {
            Log.i(TAG, "Force flushing ${writeQueue.size} pending aliases...")
            flushWriteQueue(context, saf)
        }
    }

    /* SEED GENERATION */
    private suspend fun generateSeedFromSpeciesJson(
        context: Context,
        saf: SaFStorageHelper,
        binariesDir: androidx.documentfile.provider.DocumentFile
    ) = withContext(Dispatchers.IO) {
        try {
            // Load species.json via ServerDataCache
            val snapshot = ServerDataCache.getOrLoad(context)

            // Generate minimal seed (canonical + tilename per species)
            val speciesList = snapshot.speciesById.map { (id, sp) ->
                val canonicalAlias = generateAliasData(
                    text = sp.soortnaam,
                    source = "seed_canonical"
                )

                val tilenameAlias = if (!sp.soortkey.equals(sp.soortnaam, ignoreCase = true)) {
                    generateAliasData(
                        text = sp.soortkey,
                        source = "seed_tilename"
                    )
                } else {
                    null  // Skip tilename if identical to canonical
                }

                SpeciesEntry(
                    speciesId = id,
                    canonical = sp.soortnaam,
                    tilename = sp.soortkey,
                    aliases = listOfNotNull(canonicalAlias, tilenameAlias)
                )
            }

            val master = AliasMaster(
                version = "2.1",
                timestamp = Instant.now().toString(),
                species = speciesList
            )

            // Write to SAF
            val masterJson = jsonPretty.encodeToString(master)
            val masterDoc = binariesDir.createFile("application/json", MASTER_FILE)

            if (masterDoc != null) {
                context.contentResolver.openOutputStream(masterDoc.uri, "w")?.use {
                    it.write(masterJson.toByteArray(Charsets.UTF_8))
                    it.flush()
                }

                Log.i(TAG, "Seed generated: ${speciesList.size} species, ${speciesList.sumOf { it.aliases.size }} total aliases")

                // Generate CBOR cache
                rebuildCborCache(master, binariesDir, context)

                // Load into AliasMatcher
                com.yvesds.vt5.features.speech.AliasMatcher.ensureLoaded(context, saf)
            }

        } catch (ex: Exception) {
            Log.e(TAG, "Seed generation failed: ${ex.message}", ex)
        }
    }

    /**
     * Generate AliasData from text
     *
     * Computes:
     * - norm: Normalized text
     * - cologne: Cologne phonetic code
     * - phonemes: IPA phonemes
     *
     * This version ensures cologne/phonemes are returned as consistent (non-null) strings
     * to match the AliasData model (which uses non-null String fields).
     */
    private fun generateAliasData(text: String, source: String = "seed_canonical"): AliasData {
        val cleaned = normalizeLowerNoDiacritics(text)
        val col = runCatching { ColognePhonetic.encode(cleaned) }.getOrNull() ?: ""
        val phon = runCatching { DutchPhonemizer.phonemize(cleaned) }.getOrNull() ?: ""

        return AliasData(
            text = text.trim().lowercase(),
            norm = cleaned,
            // AliasData model expects non-null Strings for cologne/phonemes — provide empty string when encoding fails
            cologne = col,
            phonemes = phon,
            source = source,
            // consistent token for user-added aliases
            timestamp = if (source == "user_field_training") Instant.now().toString() else null
        )
    }

    /* BATCH WRITE SYSTEM */
    private fun scheduleBatchWrite(context: Context, saf: SaFStorageHelper) {
        if (writePending.compareAndSet(false, true)) {
            writeJob?.cancel()

            val shouldWriteNow = writeQueue.size >= BATCH_SIZE_THRESHOLD

            writeJob = writeScope.launch {
                try {
                    if (!shouldWriteNow) {
                        delay(BATCH_TIME_THRESHOLD_MS)
                    }

                    flushWriteQueue(context, saf)

                } finally {
                    writePending.set(false)
                }
            }
        }
    }

    private suspend fun flushWriteQueue(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        if (writeQueue.isEmpty()) return@withContext

        try {
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext
            val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory } ?: return@withContext
            val masterDoc = binaries.findFile(MASTER_FILE) ?: return@withContext

            // Load current master
            val masterJson = context.contentResolver.openInputStream(masterDoc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext

            val master = jsonPretty.decodeFromString(AliasMaster.serializer(), masterJson)

            // Merge pending aliases into master
            val speciesMap = master.species.associateBy { it.speciesId }.toMutableMap()

            for ((_, pending) in writeQueue) {
                val speciesEntry = speciesMap.getOrElse(pending.speciesId) {
                    // If not present, create minimal SpeciesEntry with canonical from pending.canonical
                    SpeciesEntry(
                        speciesId = pending.speciesId,
                        canonical = pending.canonical,
                        tilename = pending.tilename,
                        aliases = emptyList()
                    ).also { speciesMap[pending.speciesId] = it }
                }

                // Prepare new AliasData
                val newAlias = generateAliasData(pending.aliasText, source = "user_field_training").copy(timestamp = pending.timestamp)

                // Deduplicate by norm/text
                val existingNorms = speciesEntry.aliases.map { it.norm }.toMutableSet()
                if (!existingNorms.contains(newAlias.norm)) {
                    val updatedAliasList = speciesEntry.aliases + newAlias
                    speciesMap[pending.speciesId] = speciesEntry.copy(aliases = updatedAliasList)
                }
            }

            // Build updated master
            val updatedMaster = master.copy(
                timestamp = Instant.now().toString(),
                species = speciesMap.values.sortedBy { it.speciesId }
            )

            // Write master JSON (pretty)
            val updatedJson = jsonPretty.encodeToString(AliasMaster.serializer(), updatedMaster)
            context.contentResolver.openOutputStream(masterDoc.uri, "wt")?.use {
                it.write(updatedJson.toByteArray(Charsets.UTF_8))
                it.flush()
            }

            Log.i(TAG, "Flushed ${writeQueue.size} pending aliases to master")

            // Regenerate CBOR cache from master.toAliasIndex()
            rebuildCborCache(updatedMaster, binaries, context)

            // Clear the writeQueue
            writeQueue.clear()
        } catch (ex: Exception) {
            Log.e(TAG, "flushWriteQueue failed: ${ex.message}", ex)
        }
    }

    /* CBOR CACHE GENERATION */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun rebuildCborCache(master: AliasMaster, binariesDir: androidx.documentfile.provider.DocumentFile, context: Context) = withContext(Dispatchers.IO) {
        try {
            // Convert master -> AliasIndex (flat)
            val index = master.toAliasIndex()

            // Serialize with CBOR and gzip
            val cborBytes = Cbor.encodeToByteArray(AliasIndex.serializer(), index)
            val gzipped = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gzip ->
                    gzip.write(cborBytes)
                    gzip.finish()
                }
                baos.toByteArray()
            }

            val cborFile = binariesDir.findFile(CBOR_FILE)?.also { it.delete() } ?: binariesDir.createFile("application/octet-stream", CBOR_FILE)
            if (cborFile != null) {
                context.contentResolver.openOutputStream(cborFile.uri, "w")?.use { it.write(gzipped); it.flush() }
                Log.i(TAG, "Rebuilt CBOR cache: ${index.json.size} records, ${gzipped.size} bytes compressed")
            }
        } catch (ex: Exception) {
            Log.e(TAG, "rebuildCborCache failed: ${ex.message}", ex)
        }
    }

    /* HELPERS */
    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase()
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}