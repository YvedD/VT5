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

    /*═══════════════════════════════════════════════════════════════════════
     * FILE PATHS & CONSTANTS
     *═══════════════════════════════════════════════════════════════════════*/

    private const val MASTER_FILE = "aliases_master.json"
    private const val CBOR_FILE = "aliases_optimized.cbor.gz"
    private const val BINARIES = "binaries"

    /*═══════════════════════════════════════════════════════════════════════
     * JSON/CBOR SERIALIZERS
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Pretty JSON for aliases_master.json (human-readable)
     */
    private val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Compact JSON for internal operations
     */
    private val jsonCompact = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /*═══════════════════════════════════════════════════════════════════════
     * WRITE QUEUE (Layer 3: Async Batched Writes)
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Pending alias additions (waiting for batch flush)
     * Key: "speciesId||aliasText" (for deduplication)
     */
    private val writeQueue = ConcurrentHashMap<String, PendingAlias>()

    /**
     * Write job pending flag (atomic for thread safety)
     */
    private val writePending = AtomicBoolean(false)

    /**
     * Coroutine scope for background write operations
     */
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Current write job (nullable, cancelled on new batch)
     */
    private var writeJob: Job? = null

    /**
     * Batch write thresholds
     */
    private const val BATCH_SIZE_THRESHOLD = 5    // Write after 5 additions
    private const val BATCH_TIME_THRESHOLD_MS = 30_000L  // Or after 30 seconds

    /**
     * Pending alias data structure
     */
    private data class PendingAlias(
        val speciesId: String,
        val aliasText: String,
        val canonical: String,
        val tilename: String?,
        val timestamp: String
    )

    /*═══════════════════════════════════════════════════════════════════════
     * PUBLIC API: INITIALIZATION
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Initialize alias system
     *
     * Called by: VT5App.onCreate() or InstallatieScherm
     *
     * Flow:
     * 1. Check if aliases_master.json exists in SAF
     * 2. If NOT: Generate seed from species.json (first install)
     * 3. If YES: Load existing (preserve user training)
     * 4. Load CBOR cache into AliasMatcher
     *
     * @param context Application context
     * @param saf SAF helper for file access
     * @return true if successful, false otherwise
     */
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

    /*═══════════════════════════════════════════════════════════════════════
     * PUBLIC API: ADD ALIAS (HOT-RELOAD)
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Add alias with instant hot-reload (NO HERSTART!)
     *
     * Called by: TellingScherm gesture detector (user taps raw log)
     *
     * Flow:
     * 1. INSTANT (0.2ms): Hot-patch AliasMatcher in-memory cache
     * 2. ASYNC: Add to writeQueue for batched persistence
     * 3. BATCHED (5 adds or 30s): Flush to disk in background
     *
     * Timeline Example:
     * T=0s:   User taps "ali" → addAlias(...)
     * T=0.2ms: Hot-patch complete → alias active!
     * T=0.3s: User says "ali 5" → MATCH! ✅
     * T=30s:  Background flush → persisted to disk
     *
     * @param context Application context
     * @param saf SAF helper
     * @param speciesId Species ID (e.g., "20" for Aalscholver)
     * @param aliasText Alias text (e.g., "ali")
     * @param canonical Canonical name (e.g., "Aalscholver")
     * @param tilename Tile name (e.g., "Aal"), nullable
     * @return true if successful, false if duplicate or error
     */
    suspend fun addAlias(
        context: Context,
        saf: SaFStorageHelper,
        speciesId: String,
        aliasText: String,
        canonical: String,
        tilename: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalized = aliasText.trim().lowercase()
            if (normalized.isBlank()) {
                Log.w(TAG, "addAlias: Empty alias text")
                return@withContext false
            }

            // Check duplicate (fast in-memory check via AliasMatcher)
            val existing = com.yvesds.vt5.features.speech.AliasMatcher.findExact(normalized, context, saf)
            if (existing.isNotEmpty()) {
                val existingSpecies = existing.first().speciesid
                if (existingSpecies == speciesId) {
                    Log.w(TAG, "addAlias: Duplicate alias '$normalized' for species $speciesId")
                    return@withContext false
                } else {
                    Log.w(TAG, "addAlias: Alias '$normalized' already exists for species $existingSpecies (conflict!)")
                    // TODO: Show conflict dialog (user override?)
                    return@withContext false
                }
            }

            // STEP 1: INSTANT HOT-PATCH (Layer 2: In-memory cache)
            com.yvesds.vt5.features.speech.AliasMatcher.addAliasHotpatch(
                speciesId = speciesId,
                aliasRaw = normalized,
                canonical = canonical,
                tilename = tilename
            )
            Log.i(TAG, "Hot-patched alias: '$normalized' → $speciesId (instant!)")

            // STEP 2: ASYNC ADD TO WRITE QUEUE (Layer 3: Batched writes)
            val key = "$speciesId||$normalized"
            val pending = PendingAlias(
                speciesId = speciesId,
                aliasText = normalized,
                canonical = canonical,
                tilename = tilename,
                timestamp = Instant.now().toString()
            )
            writeQueue[key] = pending

            // STEP 3: SCHEDULE BATCH WRITE
            scheduleBatchWrite(context, saf)

            return@withContext true

        } catch (ex: Exception) {
            Log.e(TAG, "addAlias failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /*═══════════════════════════════════════════════════════════════════════
     * PUBLIC API: FORCE FLUSH
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Force flush pending writes (call on app pause/destroy)
     *
     * Called by: TellingScherm.onDestroy(), VT5App.onTerminate()
     *
     * Ensures no data loss if app crashes or user closes app.
     */
    suspend fun forceFlush(context: Context, saf: SaFStorageHelper) {
        writeJob?.cancel()
        if (writeQueue.isNotEmpty()) {
            Log.i(TAG, "Force flushing ${writeQueue.size} pending aliases...")
            flushWriteQueue(context, saf)
        }
    }

    /*═══════════════════════════════════════════════════════════════════════
     * PRIVATE: SEED GENERATION (First Install)
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Generate seed from species.json (first install only)
     *
     * Creates minimal aliases: canonical + tilename per species
     * No user training data (that comes from field use!)
     *
     * Example output:
     * {
     *   "speciesId": "20",
     *   "canonical": "Aalscholver",
     *   "tilename": "Aal",
     *   "aliases": [
     *     { "text": "aalscholver", "source": "seed_canonical", ... },
     *     { "text": "aal", "source": "seed_tilename", ... }
     *   ]
     * }
     */
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
     */
    private fun generateAliasData(text: String, source: String): AliasData {
        val normalized = normalizeLowerNoDiacritics(text)

        val cologne = runCatching {
            ColognePhonetic.encode(normalized)
        }.getOrNull() ?: ""

        val phonemes = runCatching {
            DutchPhonemizer.phonemize(normalized)
        }.getOrNull() ?: ""

        return AliasData(
            text = text.lowercase(),
            norm = normalized,
            cologne = cologne,
            phonemes = phonemes,
            source = source,
            timestamp = if (source == "user_field_training") Instant.now().toString() else null
        )
    }

    /*═══════════════════════════════════════════════════════════════════════
     * PRIVATE: BATCH WRITE SYSTEM
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Schedule batch write (triggered after each addAlias call)
     *
     * Logic:
     * - If queue >= 5 aliases: Write immediately
     * - Else: Schedule write after 30 seconds
     *
     * Thread-safe: Uses AtomicBoolean to prevent duplicate jobs
     */
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

    /**
     * Flush write queue to disk (Layer 1: Persistent storage)
     *
     * Flow:
     * 1. Load current aliases_master.json
     * 2. Merge writeQueue entries into master
     * 3. Write updated master to disk
     * 4. Regenerate CBOR cache (background)
     * 5. Clear writeQueue
     */
    private suspend fun flushWriteQueue(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        if (writeQueue.isEmpty()) return@withContext

        try {
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext
            val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory } ?: return@withContext
            val masterDoc = binaries.findFile(MASTER_FILE) ?: return@withContext

            // 1. Load current master
            val masterJson = context.contentResolver.openInputStream(masterDoc.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@withContext

            val master = jsonPretty.decodeFromString<AliasMaster>(masterJson)

            // 2. Merge writeQueue into master
            val updatedSpecies = master.species.map { species ->
                val pendingForSpecies = writeQueue.values.filter { it.speciesId == species.speciesId }

                if (pendingForSpecies.isEmpty()) {
                    species
                } else {
                    val newAliases = pendingForSpecies.map { pending ->
                        generateAliasData(
                            text = pending.aliasText,
                            source = "user_field_training"
                        ).copy(timestamp = pending.timestamp)
                    }

                    // Deduplicate: keep only new aliases
                    val existingTexts = species.aliases.map { it.text.lowercase() }.toSet()
                    val uniqueNew = newAliases.filter { it.text.lowercase() !in existingTexts }

                    species.copy(
                        aliases = species.aliases + uniqueNew
                    )
                }
            }.toList()

            val updatedMaster = master.copy(
                timestamp = Instant.now().toString(),
                species = updatedSpecies
            )

            // 3. Write updated master
            val updatedJson = jsonPretty.encodeToString(updatedMaster)

            context.contentResolver.openOutputStream(masterDoc.uri, "wt")?.use {
                it.write(updatedJson.toByteArray(Charsets.UTF_8))
                it.flush()
            }

            Log.i(TAG, "Flushed ${writeQueue.size} aliases to master")

            // 4. Regenerate CBOR cache (background)
            rebuildCborCache(updatedMaster, binaries, context)

            // 5. Clear queue
            writeQueue.clear()

        } catch (ex: Exception) {
            Log.e(TAG, "flushWriteQueue failed: ${ex.message}", ex)
        }
    }

    /*═══════════════════════════════════════════════════════════════════════
     * PRIVATE: CBOR CACHE GENERATION
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Rebuild CBOR cache from master
     *
     * Converts hierarchical AliasMaster → flat AliasIndex → CBOR bytes
     * Used by AliasMatcher for fast loading (binary format)
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun rebuildCborCache(
        master: AliasMaster,
        binariesDir: androidx.documentfile.provider.DocumentFile,
        context: Context
    ) = withContext(Dispatchers.IO) {
        try {
            // Convert to flat index
            val index = master.toAliasIndex()

            // Serialize to CBOR
            val cborBytes = Cbor.encodeToByteArray(AliasIndex.serializer(), index)

            // GZIP compress
            val gzipped = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gzip ->
                    gzip.write(cborBytes)
                    gzip.finish()
                }
                baos.toByteArray()
            }

            // Write to file
            val cborFile = binariesDir.findFile(CBOR_FILE)?.also { it.delete() }
                ?: binariesDir.createFile("application/octet-stream", CBOR_FILE)

            if (cborFile != null) {
                context.contentResolver.openOutputStream(cborFile.uri, "w")?.use {
                    it.write(gzipped)
                    it.flush()
                }

                Log.i(TAG, "Rebuilt CBOR cache: ${index.json.size} records, ${gzipped.size} bytes compressed")
            }

        } catch (ex: Exception) {
            Log.e(TAG, "rebuildCborCache failed: ${ex.message}", ex)
        }
    }

    /*═══════════════════════════════════════════════════════════════════════
     * PRIVATE: HELPER FUNCTIONS
     *═══════════════════════════════════════════════════════════════════════*/

    /**
     * Normalize text (lowercase, no diacritics, single spaces)
     *
     * Matches PrecomputeAliasIndex.normalizeLowerNoDiacritics()
     */
    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase()
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}