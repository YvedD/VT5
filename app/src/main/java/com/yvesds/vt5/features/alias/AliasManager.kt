package com.yvesds.vt5.features.alias

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.helpers.*
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.speech.ColognePhonetic
import com.yvesds.vt5.features.speech.DutchPhonemizer
import com.yvesds.vt5.utils.TextUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * AliasManager.kt - REFACTORED with helper delegation
 *
 * This manager now delegates to specialized helpers:
 * - AliasIndexCache: Internal cache management
 * - AliasSafWriter: Safe SAF writes
 * - AliasMasterIO: Master file I/O
 * - AliasIndexLoader: Priority-based index loading
 * - AliasSeedGenerator: Seed generation from species.json
 * - AliasCborRebuilder: Debounced CBOR rebuilds
 *
 * Responsibilities:
 * - Coordinate alias operations across helpers
 * - Maintain in-memory index state
 * - Handle user alias additions
 * - Ensure index availability for AliasMatcher
 */

object AliasManager {

    private const val TAG = "AliasManager"

    /* FILE PATHS & CONSTANTS */
    private const val MASTER_FILE = "alias_master.json"
    private const val CBOR_FILE = "aliases_optimized.cbor.gz"
    private const val BINARIES = "binaries"
    private const val ASSETS = "assets"

    /* JSON SERIALIZER */
    private val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /* INDEX LOAD SYNCHRONIZATION */
    private val indexLoadMutex = Mutex()
    @Volatile private var indexLoaded = false
    @Volatile private var loadedIndex: AliasIndex? = null

    /* MASTER WRITE SYNCHRONIZATION */
    private val masterWriteMutex = Mutex()

    /* WRITE QUEUE (legacy batched writes) */
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

    /* INITIALIZATION: ensure SAF structure and optionally generate initial seed */
    suspend fun initialize(context: Context, saf: SaFStorageHelper): Boolean = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists()
            if (vt5 == null) {
                Log.e(TAG, "SAF VT5 root not set")
                return@withContext false
            }

            // Ensure binaries directory exists
            val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory } ?: vt5.createDirectory(BINARIES)
            if (binaries == null) {
                Log.e(TAG, "Cannot create binaries directory")
                return@withContext false
            }

            // Try to load existing master from assets
            val master = AliasMasterIO.readMasterFromAssets(context, vt5)
            if (master != null) {
                Log.i(TAG, "Loaded ${master.species.size} species, ${master.species.sumOf { it.aliases.size }} total aliases from assets")
                // Ensure CBOR cache exists
                if (!AliasIndexCache.exists(context)) {
                    Log.w(TAG, "CBOR cache missing, regenerating...")
                    AliasMasterIO.writeMasterAndCbor(context, master, vt5, saf)
                }
                // Hot-load into AliasMatcher
                try { com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(context, saf) } catch (_: Exception) {}
                return@withContext true
            }

            // Fallback: try legacy location (binaries)
            val legacyMaster = AliasMasterIO.readMasterFromBinaries(context, vt5)
            if (legacyMaster != null) {
                Log.i(TAG, "Loaded ${legacyMaster.species.size} species from binaries (legacy)")
                if (!AliasIndexCache.exists(context)) {
                    Log.w(TAG, "CBOR cache missing, regenerating...")
                    AliasMasterIO.writeMasterAndCbor(context, legacyMaster, vt5, saf)
                }
                try { com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(context, saf) } catch (_: Exception) {}
                return@withContext true
            }

            // First install: generate seed from species.json
            Log.i(TAG, "First install detected, generating seed from species.json...")
            AliasSeedGenerator.generateSeed(context, saf, vt5)

            return@withContext true

        } catch (ex: Exception) {
            Log.e(TAG, "Initialize failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /* Note: Helper methods moved to specialized helper classes:
     * - AliasIndexCache: loadIndexFromInternalCache, writeIndexToInternalCache, deleteInternalCache
     * - AliasSafWriter: safeWriteToDocument, safeWriteTextToDocument, writeCopyToExports
     */

    /**
     * Ensure the in-memory AliasIndex is loaded - REFACTORED with AliasIndexLoader helper.
     * This function is suspend and idempotent.
     */
    suspend fun ensureIndexLoadedSuspend(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        if (indexLoaded && loadedIndex != null) {
            return@withContext
        }

        indexLoadMutex.withLock {
            if (indexLoaded && loadedIndex != null) {
                return@withLock
            }

            // Delegate to AliasIndexLoader helper for priority-based loading
            val index = AliasIndexLoader.loadIndex(context, saf)
            if (index != null) {
                loadedIndex = index
                indexLoaded = true
                Log.i(TAG, "AliasIndex loaded via helper")
            } else {
                loadedIndex = null
                indexLoaded = false
                Log.w(TAG, "AliasIndex: no index available")
            }
        }
    }

    /** Quick helper to know if index is already loaded in memory */
    fun isIndexLoaded(): Boolean = indexLoaded

    /** Optional getter for the loaded index (null if not loaded) */
    fun getLoadedIndex(): AliasIndex? = loadedIndex

    /**
     * Get all unique species from the loaded alias index.
     * Returns a map of speciesId -> canonical name.
     * This represents ALL species from site_species.json regardless of telpost assignment.
     * 
     * Performance: O(n) but cached in memory, runs off-main thread.
     * Should be called after ensureIndexLoadedSuspend() to guarantee data availability.
     */
    suspend fun getAllSpeciesFromIndex(context: Context, saf: SaFStorageHelper): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            // Ensure index is loaded
            ensureIndexLoadedSuspend(context, saf)
            
            val index = loadedIndex
            if (index == null) {
                Log.w(TAG, "getAllSpeciesFromIndex: index not loaded")
                return@withContext emptyMap()
            }
            
            // Build unique species map from alias records
            // Each record has speciesid and canonical, we deduplicate by species ID
            val speciesMap = mutableMapOf<String, String>()
            for (record in index.json) {
                if (!speciesMap.containsKey(record.speciesid)) {
                    speciesMap[record.speciesid] = record.canonical
                }
            }
            
            speciesMap
        } catch (ex: Exception) {
            Log.e(TAG, "getAllSpeciesFromIndex failed: ${ex.message}", ex)
            emptyMap()
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
            val found = com.yvesds.vt5.features.speech.AliasMatcher.findExact(TextUtils.normalizeLowerNoDiacritics(normalizedText), context, saf)
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

            // 1) Hot-patch in-memory (AliasMatcher) - keeps runtime fast
            com.yvesds.vt5.features.speech.AliasMatcher.addAliasHotpatch(
                speciesId = speciesId,
                aliasRaw = normalizedText,
                canonical = canonical,
                tilename = tilename
            )

            // 2) Persist alias immediately to alias_master.json (lightweight per-alias write). This makes the alias durable quickly.
            val timestamp = Instant.now().toString()
            val persisted = writeSingleAliasToMasterImmediate(context, saf, speciesId, normalizedText, canonical, tilename, timestamp)
            if (!persisted) {
                Log.w(TAG, "addAlias: immediate persist to master.json failed (alias still hotpatched in-memory)")
            }

            // 3) Refresh internal cache and AliasMatcher from updated master
            try {
                val vt5Local = saf.getVt5DirIfExists()
                if (vt5Local != null) {
                    val masterObj = AliasMasterIO.readMasterFromAssets(context, vt5Local)
                    if (masterObj != null) {
                        AliasIndexCache.write(context, masterObj.toAliasIndex())
                        Log.i(TAG, "addAlias: internal CBOR cache updated")
                        
                        // Reload AliasMatcher
                        try {
                            com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(context, saf)
                            Log.i(TAG, "addAlias: AliasMatcher reloaded")
                        } catch (ex: Exception) {
                            Log.w(TAG, "addAlias: AliasMatcher reload failed: ${ex.message}", ex)
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "addAlias: post-persist refresh failed: ${ex.message}", ex)
            }

            // 4) Schedule debounced CBOR rebuild for SAF binaries
            AliasCborRebuilder.scheduleRebuild(context, saf)

            Log.i(TAG, "addAlias: hotpatched and persisted alias='$normalizedText' for species=$speciesId (master.json immediate)")
            return@withContext true
        } catch (ex: Exception) {
            Log.e(TAG, "addAlias failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /* FORCE FLUSH - REFACTORED */
    suspend fun forceFlush(context: Context, saf: SaFStorageHelper) {
        // Delegate to AliasCborRebuilder for immediate CBOR rebuild
        AliasCborRebuilder.forceRebuild(context, saf)
        Log.i(TAG, "Force rebuild completed")
    }

    /* FORCE REBUILD CBOR NOW - REFACTORED */
    suspend fun forceRebuildCborNow(context: Context, saf: SaFStorageHelper) {
        // Delegate to AliasCborRebuilder for synchronous rebuild
        AliasCborRebuilder.forceRebuild(context, saf)
        Log.i(TAG, "Synchronous CBOR rebuild completed")
    }

    /* Note: scheduleCborRebuildDebounced moved to AliasCborRebuilder helper */

    @ExperimentalSerializationApi
    private suspend fun writeMasterAndCborToSaf(
        context: android.content.Context,
        master: AliasMaster,
        vt5RootDir: DocumentFile,
        saf: SaFStorageHelper? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val jsonPrettyLocal = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

            // --- ASSETS: pretty JSON ---
            val assetsDir = vt5RootDir.findFile(ASSETS)?.takeIf { it.isDirectory } ?: vt5RootDir.createDirectory(ASSETS)
            if (assetsDir == null) {
                Log.e(TAG, "writeMasterAndCborToSaf: cannot access/create assets dir (vt5=${vt5RootDir.uri})")
            } else {
                val masterName = MASTER_FILE
                val existingMaster = assetsDir.findFile(masterName)?.takeIf { it.isFile }
                var masterDoc = existingMaster
                if (masterDoc == null) {
                    masterDoc = runCatching { assetsDir.createFile("application/json", masterName) }.getOrNull()
                }
                val prettyJson = jsonPrettyLocal.encodeToString(AliasMaster.serializer(), master)
                var wroteMaster = false
                if (masterDoc != null) {
                    wroteMaster = AliasSafWriter.safeWriteTextToDocument(context, masterDoc, prettyJson)
                    if (!wroteMaster) {
                        Log.w(TAG, "writeMasterAndCborToSaf: writing master.json to assets failed; will fallback to internal cache and exports copy")
                    } else {
                        Log.i(TAG, "writeMasterAndCborToSaf: wrote $masterName to ${masterDoc.uri} (${prettyJson.length} bytes)")
                    }
                } else {
                    Log.e(TAG, "writeMasterAndCborToSaf: failed creating $masterName in assets")
                }

                // Also write a user-visible copy to exports folder (best-effort)
                runCatching {
                    AliasSafWriter.writeCopyToExports(context, vt5RootDir, MASTER_FILE, prettyJson.toByteArray(Charsets.UTF_8), "application/json")
                }
            }

            // --- BINARIES: gzipped CBOR ---
            val binariesDir = vt5RootDir.findFile(BINARIES)?.takeIf { it.isDirectory } ?: vt5RootDir.createDirectory(BINARIES)
            if (binariesDir == null) {
                Log.e(TAG, "writeMasterAndCborToSaf: cannot access/create binaries dir (vt5=${vt5RootDir.uri})")
                return@withContext
            }

            val index = master.toAliasIndex()
            val cborBytes = Cbor.encodeToByteArray(AliasIndex.serializer(), index)

            val gzipped: ByteArray = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gzip ->
                    gzip.write(cborBytes)
                    gzip.finish()
                }
                baos.toByteArray()
            }

            val cborName = CBOR_FILE
            // remove existing if present
            binariesDir.findFile(cborName)?.delete()
            val cborDoc = runCatching { binariesDir.createFile("application/octet-stream", cborName) }.getOrNull()
            var wroteCborSaf = false
            if (cborDoc != null) {
                wroteCborSaf = AliasSafWriter.safeWriteToDocument(context, cborDoc, gzipped)
                if (wroteCborSaf) {
                    Log.i(TAG, "writeMasterAndCborToSaf: wrote $cborName to ${cborDoc.uri} (${gzipped.size} bytes)")
                } else {
                    Log.w(TAG, "writeMasterAndCborToSaf: failed writing $cborName to binaries; will fallback to internal cache")
                }
            } else {
                Log.e(TAG, "writeMasterAndCborToSaf: failed creating $cborName in binaries")
            }

            // Also write user-visible copy to exports
            runCatching {
                AliasSafWriter.writeCopyToExports(context, vt5RootDir, CBOR_FILE, gzipped, "application/octet-stream")
            }

            // If SAF write to binaries failed, ensure internal cache is updated so runtime uses latest index
            try {
                AliasIndexCache.write(context, index)
                Log.i(TAG, "Internal CBOR cache updated after writeMasterAndCborToSaf")
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to update internal cache after writeMasterAndCborToSaf: ${ex.message}")
            }

        } catch (ex: Exception) {
            Log.e(TAG, "writeMasterAndCborToSaf failed: ${ex.message}", ex)
        }
    }

    /* SEED GENERATION */
    @OptIn(ExperimentalSerializationApi::class)
    /* Note: generateSeedFromSpeciesJson moved to AliasSeedGenerator helper */

    /**
     * Merge user-added aliases from the existing master (if any) into the new master.
     */
    private suspend fun mergeUserAliasesIntoMaster(
        context: android.content.Context,
        vt5RootDir: DocumentFile,
        newMaster: AliasMaster
    ): AliasMaster = withContext(Dispatchers.IO) {
        try {
            val assets = vt5RootDir.findFile(ASSETS)?.takeIf { it.isDirectory } ?: return@withContext newMaster

            val existingDoc = assets.findFile(MASTER_FILE)?.takeIf { it.isFile }
            if (existingDoc == null) return@withContext newMaster

            val existingJson = try {
                context.contentResolver.openInputStream(existingDoc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (ex: Exception) {
                Log.w(TAG, "mergeUserAliasesIntoMaster: cannot read existing master: ${ex.message}")
                null
            }
            if (existingJson.isNullOrBlank()) return@withContext newMaster

            val existingMaster = try {
                jsonPretty.decodeFromString(AliasMaster.serializer(), existingJson)
            } catch (ex: Exception) {
                Log.w(TAG, "mergeUserAliasesIntoMaster: failed to decode existing master: ${ex.message}")
                return@withContext newMaster
            }

            // collect user aliases grouped by speciesId
            val userAliasesBySpecies = mutableMapOf<String, MutableList<AliasData>>()
            existingMaster.species.forEach { sp ->
                sp.aliases.forEach { a ->
                    val src = a.source ?: ""
                    if (src.startsWith("user") || src == "user_field_training") {
                        var alias = a
                        // ensure norm
                        if (alias.norm.isBlank()) {
                            alias = alias.copy(norm = TextUtils.normalizeLowerNoDiacritics(alias.text))
                        }
                        // ensure cologne
                        if (alias.cologne.isBlank()) {
                            alias = alias.copy(cologne = runCatching { ColognePhonetic.encode(alias.norm) }.getOrNull() ?: "")
                        }
                        // ensure phonemes
                        if (alias.phonemes.isBlank()) {
                            alias = alias.copy(phonemes = runCatching { DutchPhonemizer.phonemize(alias.norm) }.getOrNull() ?: "")
                        }
                        userAliasesBySpecies.getOrPut(sp.speciesId) { mutableListOf() }.add(alias)
                    }
                }
            }

            if (userAliasesBySpecies.isEmpty()) {
                Log.i(TAG, "mergeUserAliasesIntoMaster: no user aliases to merge")
                return@withContext newMaster
            }

            // Build quick lookup for norms present in newMaster and map norms -> species
            val newSpeciesMap = newMaster.species.associateBy { it.speciesId }.toMutableMap()
            val normToSpecies = mutableMapOf<String, MutableSet<String>>()
            newSpeciesMap.forEach { (sid, sp) ->
                sp.aliases.forEach { a -> if (a.norm.isNotBlank()) normToSpecies.getOrPut(a.norm) { mutableSetOf() }.add(sid) }
                val canonNorm = TextUtils.normalizeLowerNoDiacritics(sp.canonical)
                if (canonNorm.isNotBlank()) normToSpecies.getOrPut(canonNorm) { mutableSetOf() }.add(sid)
                sp.tilename?.let {
                    val tilNorm = TextUtils.normalizeLowerNoDiacritics(it)
                    if (tilNorm.isNotBlank()) normToSpecies.getOrPut(tilNorm) { mutableSetOf() }.add(sid)
                }
            }

            val conflicts = mutableListOf<String>()
            var mergedAdded = 0

            // Merge user aliases into new species map
            for ((sid, uAliases) in userAliasesBySpecies) {
                val target = newSpeciesMap.getOrPut(sid) {
                    val existingSpecies = existingMaster.species.firstOrNull { it.speciesId == sid }
                    val canonical = existingSpecies?.canonical ?: sid
                    val tilename = existingSpecies?.tilename
                    SpeciesEntry(speciesId = sid, canonical = canonical, tilename = tilename, aliases = emptyList())
                }

                val existingNorms = target.aliases.map { it.norm }.toMutableSet()

                val toAppend = mutableListOf<AliasData>()
                for (ua in uAliases) {
                    val norm = ua.norm.ifBlank { TextUtils.normalizeLowerNoDiacritics(ua.text) }
                    val mapped = normToSpecies[norm]
                    if (mapped != null && !(mapped.size == 1 && mapped.contains(sid))) {
                        conflicts.add("alias='${ua.text}' norm='$norm' mappedTo=${mapped.joinToString(",")} userSpecies=$sid")
                    }
                    if (!existingNorms.contains(norm)) {
                        var finalAlias = ua
                        if (finalAlias.norm.isBlank()) finalAlias = finalAlias.copy(norm = norm)
                        if (finalAlias.cologne.isBlank()) finalAlias = finalAlias.copy(cologne = runCatching { ColognePhonetic.encode(norm) }.getOrNull() ?: "")
                        if (finalAlias.phonemes.isBlank()) finalAlias = finalAlias.copy(phonemes = runCatching { DutchPhonemizer.phonemize(norm) }.getOrNull() ?: "")
                        val source = if (finalAlias.source.isNullOrBlank()) "user_field_training" else finalAlias.source
                        val timestamp = finalAlias.timestamp ?: Instant.now().toString()
                        finalAlias = finalAlias.copy(source = source, timestamp = timestamp)
                        toAppend.add(finalAlias)
                        existingNorms.add(norm)
                        normToSpecies.getOrPut(norm) { mutableSetOf() }.add(sid)
                    }
                }

                if (toAppend.isNotEmpty()) {
                    newSpeciesMap[sid] = target.copy(aliases = target.aliases + toAppend)
                    mergedAdded += toAppend.size
                }
            }

            if (conflicts.isNotEmpty()) {
                Log.w(TAG, "mergeUserAliasesIntoMaster: conflicts detected (${conflicts.size}); example: ${conflicts.firstOrNull()}")
            }

            val merged = newMaster.copy(species = newSpeciesMap.values.sortedBy { it.speciesId })
            Log.i(TAG, "mergeUserAliasesIntoMaster: merged user aliases; added=$mergedAdded conflicts=${conflicts.size}")
            return@withContext merged
        } catch (ex: Exception) {
            Log.e(TAG, "mergeUserAliasesIntoMaster failed: ${ex.message}", ex)
            return@withContext newMaster
        }
    }

    /**
     * Generate AliasData from text
     */
    private fun generateAliasData(text: String, source: String = "seed_canonical"): AliasData {
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

    /* BATCH WRITE SYSTEM (legacy; still available) */
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
            val assetsDir = vt5.findFile(ASSETS)?.takeIf { it.isDirectory } ?: vt5.createDirectory(ASSETS) ?: return@withContext
            val masterDoc = assetsDir.findFile(MASTER_FILE) ?: assetsDir.createFile("application/json", MASTER_FILE) ?: return@withContext

            // Load current master (if present) or create minimal
            val masterJson = context.contentResolver.openInputStream(masterDoc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val master = if (masterJson.isBlank()) {
                AliasMaster(version = "2.1", timestamp = Instant.now().toString(), species = emptyList())
            } else {
                jsonPretty.decodeFromString(AliasMaster.serializer(), masterJson)
            }

            // Merge pending aliases into master
            val speciesMap = master.species.associateBy { it.speciesId }.toMutableMap()

            for ((_, pending) in writeQueue) {
                val speciesEntry = speciesMap.getOrElse(pending.speciesId) {
                    SpeciesEntry(
                        speciesId = pending.speciesId,
                        canonical = pending.canonical,
                        tilename = pending.tilename,
                        aliases = emptyList()
                    ).also { speciesMap[pending.speciesId] = it }
                }

                val newAlias = generateAliasData(pending.aliasText, source = "user_field_training").copy(timestamp = pending.timestamp)

                val existingNorms = speciesEntry.aliases.map { it.norm }.toMutableSet()
                if (!existingNorms.contains(newAlias.norm)) {
                    val updatedAliasList = speciesEntry.aliases + newAlias
                    speciesMap[pending.speciesId] = speciesEntry.copy(aliases = updatedAliasList)
                }
            }

            val updatedMaster = master.copy(
                timestamp = Instant.now().toString(),
                species = speciesMap.values.sortedBy { it.speciesId }
            )

            // Write master JSON (pretty) using safe helper
            val updatedJson = jsonPretty.encodeToString(AliasMaster.serializer(), updatedMaster)
            val wroteMaster = AliasSafWriter.safeWriteTextToDocument(context, masterDoc, updatedJson)
            if (!wroteMaster) {
                Log.w(TAG, "flushWriteQueue: failed writing master.json to SAF; master updated in hotpatch and internal cache will be updated")
            } else {
                Log.i(TAG, "Flushed ${writeQueue.size} pending aliases to master")
            }

            // Regenerate CBOR cache from master.toAliasIndex()
            val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory } ?: vt5.createDirectory(BINARIES) ?: return@withContext
            rebuildCborCache(updatedMaster, binaries, context, saf)

            // After flush, update internal cache as well
            try {
                val idx = updatedMaster.toAliasIndex()
                AliasIndexCache.write(context, idx)
                Log.i(TAG, "Internal CBOR cache updated after flushWriteQueue")
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to update internal cache after flushWriteQueue: ${ex.message}")
            }

            writeQueue.clear()
        } catch (ex: Exception) {
            Log.e(TAG, "flushWriteQueue failed: ${ex.message}", ex)
        }
    }

    /* CBOR CACHE GENERATION */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun rebuildCborCache(master: AliasMaster, binariesDir: DocumentFile, context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        try {
            val index = master.toAliasIndex()

            val cborBytes = Cbor.encodeToByteArray(AliasIndex.serializer(), index)
            val gzipped = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gzip ->
                    gzip.write(cborBytes)
                    gzip.finish()
                }
                baos.toByteArray()
            }

            // Attempt SAF write using safe helper
            var wroteSaf = false
            try {
                // delete existing and create new doc
                binariesDir.findFile(CBOR_FILE)?.delete()
            } catch (_: Exception) {}
            val cborDoc = runCatching { binariesDir.createFile("application/octet-stream", CBOR_FILE) }.getOrNull()
            if (cborDoc != null) {
                wroteSaf = AliasSafWriter.safeWriteToDocument(context, cborDoc, gzipped)
                if (wroteSaf) {
                    Log.i(TAG, "Rebuilt CBOR cache and wrote to SAF binaries (${gzipped.size} bytes)")
                } else {
                    Log.w(TAG, "rebuildCborCache: SAF write to binaries failed; falling back to internal cache")
                }
            } else {
                Log.w(TAG, "rebuildCborCache: failed creating CBOR doc in binaries")
            }

            // Always update internal cache so runtime matching sees latest index immediately
            try {
                AliasIndexCache.write(context, index)
                Log.i(TAG, "Internal CBOR cache updated after rebuildCborCache")
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to update internal cache after rebuildCborCache: ${ex.message}")
            }

            // Also write a user-visible copy to exports for accessibility (best-effort)
            val vt5 = saf.getVt5DirIfExists()
            if (vt5 != null) {
                runCatching { AliasSafWriter.writeCopyToExports(context, vt5, CBOR_FILE, gzipped, "application/octet-stream") }
            }

            Log.i(TAG, "Rebuilt CBOR cache: ${index.json.size} records, ${gzipped.size} bytes compressed (safWrite=$wroteSaf)")
        } catch (ex: Exception) {
            Log.e(TAG, "rebuildCborCache failed: ${ex.message}", ex)
        }
    }

    /* ---------------------------------
       New helper: single-alias master.json update
       --------------------------------- */

    /**
     * Atomically insert a single alias into alias_master.json in SAF assets.
     * Returns true if write succeeded or alias already existed (idempotent).
     * Uses safe write helper and writes a copy to exports for user visibility.
     */
    private suspend fun writeSingleAliasToMasterImmediate(
        context: Context,
        saf: SaFStorageHelper,
        speciesId: String,
        aliasText: String,
        canonical: String,
        tilename: String?,
        timestamp: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            masterWriteMutex.withLock {
                val vt5 = saf.getVt5DirIfExists() ?: run {
                    Log.w(TAG, "writeSingleAliasToMasterImmediate: VT5 not available")
                    return@withContext false
                }

                val assetsDir = vt5.findFile(ASSETS)?.takeIf { it.isDirectory } ?: vt5.createDirectory(ASSETS)
                if (assetsDir == null) {
                    Log.w(TAG, "writeSingleAliasToMasterImmediate: cannot access/create assets dir")
                    return@withContext false
                }

                val masterDoc = assetsDir.findFile(MASTER_FILE)?.takeIf { it.isFile } ?: assetsDir.createFile("application/json", MASTER_FILE)
                if (masterDoc == null) {
                    Log.w(TAG, "writeSingleAliasToMasterImmediate: cannot create master.json")
                    return@withContext false
                }

                // Read existing master
                val masterJson = runCatching {
                    context.contentResolver.openInputStream(masterDoc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                val master: AliasMaster = if (masterJson.isNullOrBlank()) {
                    AliasMaster(version = "2.1", timestamp = Instant.now().toString(), species = emptyList())
                } else {
                    runCatching { jsonPretty.decodeFromString(AliasMaster.serializer(), masterJson) }.getOrElse {
                        Log.w(TAG, "writeSingleAliasToMasterImmediate: failed decode existing master; creating new master")
                        AliasMaster(version = "2.1", timestamp = Instant.now().toString(), species = emptyList())
                    }
                }

                val speciesMap = master.species.associateBy { it.speciesId }.toMutableMap()

                val speciesEntry = speciesMap.getOrElse(speciesId) {
                    SpeciesEntry(speciesId = speciesId, canonical = canonical, tilename = tilename, aliases = emptyList()).also { speciesMap[speciesId] = it }
                }

                val newAlias = generateAliasData(aliasText, source = "user_field_training").copy(timestamp = timestamp)
                val existingNorms = speciesEntry.aliases.map { it.norm }.toMutableSet()
                if (existingNorms.contains(newAlias.norm)) {
                    // already present -> update master timestamp and write to ensure updated timestamp
                    val updatedMaster = master.copy(timestamp = Instant.now().toString(), species = speciesMap.values.sortedBy { it.speciesId })
                    val prettyJson = jsonPretty.encodeToString(AliasMaster.serializer(), updatedMaster)
                    val wrote = AliasSafWriter.safeWriteTextToDocument(context, masterDoc, prettyJson)
                    if (!wrote) {
                        // fallback: write a copy to exports for user access, and ensure internal cache updated
                        AliasSafWriter.writeCopyToExports(context, vt5, MASTER_FILE, prettyJson.toByteArray(Charsets.UTF_8), "application/json")
                        val idx = updatedMaster.toAliasIndex()
                        AliasIndexCache.write(context, idx)
                    }
                    Log.i(TAG, "writeSingleAliasToMasterImmediate: alias already existed (norm=${newAlias.norm}) for species=$speciesId")
                    return@withContext true
                } else {
                    // append
                    val updatedSpeciesEntry = speciesEntry.copy(aliases = speciesEntry.aliases + newAlias)
                    speciesMap[speciesId] = updatedSpeciesEntry
                    val updatedMaster = master.copy(timestamp = Instant.now().toString(), species = speciesMap.values.sortedBy { it.speciesId })
                    val prettyJson = jsonPretty.encodeToString(AliasMaster.serializer(), updatedMaster)
                    val wrote = AliasSafWriter.safeWriteTextToDocument(context, masterDoc, prettyJson)
                    if (!wrote) {
                        // fallback: write to exports and update internal cache
                        AliasSafWriter.writeCopyToExports(context, vt5, MASTER_FILE, prettyJson.toByteArray(Charsets.UTF_8), "application/json")
                        val idx = updatedMaster.toAliasIndex()
                        AliasIndexCache.write(context, idx)
                        Log.w(TAG, "writeSingleAliasToMasterImmediate: writing master.json to assets failed; used exports/internal cache fallback")
                        return@withContext true // treat as success: durable via internal cache + exports
                    } else {
                        // also write a user-visible copy to exports
                        AliasSafWriter.writeCopyToExports(context, vt5, MASTER_FILE, prettyJson.toByteArray(Charsets.UTF_8), "application/json")
                        Log.i(TAG, "writeSingleAliasToMasterImmediate: wrote alias to master.json for species=$speciesId")
                        return@withContext true
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "writeSingleAliasToMasterImmediate failed: ${ex.message}", ex)
            false
        }
    }

    /* Note: Utility functions (jsonArrayOrNull, findFirstArrayWithObjects, cancelAndJoinSafe) 
     * were removed as they were only used by generateSeedFromSpeciesJson, which is now in AliasSeedGenerator helper */
}