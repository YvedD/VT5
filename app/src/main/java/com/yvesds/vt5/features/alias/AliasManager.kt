package com.yvesds.vt5.features.alias

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.speech.ColognePhonetic
import com.yvesds.vt5.features.speech.DutchPhonemizer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * AliasManager.kt
 *
 * Responsibilities and behavior (CSV-free):
 * - Manage alias master / CBOR generation and persistence to SAF (Documents/VT5)
 * - Maintain an internal, app-private CBOR cache (context.filesDir/aliases_optimized.cbor.gz)
 * - Provide ensureIndexLoadedSuspend(...) which is idempotent and prefers:
 *     1) internal CBOR cache
 *     2) SAF binaries/aliases_optimized.cbor.gz (copy -> internal)
 *     3) SAF assets/alias_master.json or regenerate from serverdata/species.json
 * - Provide batched user-alias persistence (write queue -> assets master + binaries CBOR)
 *
 * Notes:
 * - CSV references have been removed entirely from runtime. Any CSV migration must be done offline
 *   into alias_master.json and aliases_optimized.cbor.gz placed in Documents/VT5/binaries/.
 */

object AliasManager {

    private const val TAG = "AliasManager"

    /* FILE PATHS & CONSTANTS */
    private const val MASTER_FILE = "alias_master.json"
    private const val CBOR_FILE = "aliases_optimized.cbor.gz"
    private const val BINARIES = "binaries"
    private const val ASSETS = "assets"

    /* INTERNAL CACHE (app filesDir) */
    private const val INTERNAL_CBOR = "aliases_optimized.cbor.gz"

    /* JSON/CBOR SERIALIZERS */
    private val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /* INDEX LOAD SYNCHRONIZATION */
    private val indexLoadMutex = Mutex()
    @Volatile private var indexLoaded = false
    @Volatile private var loadedIndex: AliasIndex? = null

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

    /* INITIALIZATION: ensure SAF structure and optionally generate initial seed */
    suspend fun initialize(context: Context, saf: SaFStorageHelper): Boolean = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists()
            if (vt5 == null) {
                Log.e(TAG, "SAF VT5 root not set")
                return@withContext false
            }

            // Ensure binaries exists
            val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory } ?: vt5.createDirectory(BINARIES)
            if (binaries == null) {
                Log.e(TAG, "Cannot create binaries directory")
                return@withContext false
            }

            // Prefer alias_master.json in assets (human-readable)
            val assetsDir = vt5.findFile(ASSETS)?.takeIf { it.isDirectory } ?: vt5.createDirectory(ASSETS)
            val masterDocAssets = assetsDir?.findFile(MASTER_FILE)
            if (masterDocAssets != null && masterDocAssets.exists()) {
                Log.i(TAG, "Loading existing alias_master.json from assets...")
                val masterJson = context.contentResolver.openInputStream(masterDocAssets.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                if (!masterJson.isNullOrBlank()) {
                    val master = try {
                        jsonPretty.decodeFromString(AliasMaster.serializer(), masterJson)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to decode existing alias_master.json: ${ex.message}")
                        null
                    }
                    if (master != null) {
                        Log.i(TAG, "Loaded ${master.species.size} species, ${master.species.sumOf { it.aliases.size }} total aliases")
                        // Ensure CBOR exists else rebuild
                        val cborDoc = binaries.findFile(CBOR_FILE)
                        if (cborDoc == null || !cborDoc.exists()) {
                            Log.w(TAG, "CBOR cache missing, regenerating...")
                            rebuildCborCache(master, binaries, context)
                        }
                        // Hot-load (AliasMatcher may also load)
                        try { com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(context, saf) } catch (_: Exception) {}
                        return@withContext true
                    }
                }
            }

            // Fallback: maybe master exists in binaries (legacy placement)
            val masterDocBinaries = binaries.findFile(MASTER_FILE)
            if (masterDocBinaries != null && masterDocBinaries.exists()) {
                Log.i(TAG, "Loading existing alias_master.json from binaries (legacy)...")
                val masterJson = context.contentResolver.openInputStream(masterDocBinaries.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                if (!masterJson.isNullOrBlank()) {
                    val master = try {
                        jsonPretty.decodeFromString(AliasMaster.serializer(), masterJson)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to decode legacy master: ${ex.message}")
                        null
                    }
                    if (master != null) {
                        Log.i(TAG, "Loaded ${master.species.size} species (from binaries), ${master.species.sumOf { it.aliases.size }} total aliases")
                        val cborDoc = binaries.findFile(CBOR_FILE)
                        if (cborDoc == null || !cborDoc.exists()) {
                            Log.w(TAG, "CBOR cache missing, regenerating...")
                            rebuildCborCache(master, binaries, context)
                        }
                        try { com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(context, saf) } catch (_: Exception) {}
                        return@withContext true
                    }
                }
            }

            // First install: generate seed from species.json (pass vt5 root so writeMaster writes to assets & binaries)
            Log.i(TAG, "First install detected, generating seed from species.json...")
            generateSeedFromSpeciesJson(context, saf, vt5)

            return@withContext true

        } catch (ex: Exception) {
            Log.e(TAG, "Initialize failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /* ------------------------
       Internal CBOR cache helpers
       ------------------------ */

    @OptIn(ExperimentalSerializationApi::class)
    private fun loadIndexFromInternalCache(context: Context): AliasIndex? {
        return try {
            val f = File(context.filesDir, INTERNAL_CBOR)
            if (!f.exists() || f.length() == 0L) return null
            f.inputStream().use { fis ->
                GZIPInputStream(fis).use { gis ->
                    val bytes = gis.readBytes()
                    Cbor.decodeFromByteArray(AliasIndex.serializer(), bytes)
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "loadIndexFromInternalCache failed: ${ex.message}")
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeIndexToInternalCache(context: Context, index: AliasIndex) {
        try {
            val f = File(context.filesDir, INTERNAL_CBOR)
            val bytes = Cbor.encodeToByteArray(AliasIndex.serializer(), index)
            val tmp = File(context.filesDir, "$INTERNAL_CBOR.tmp")
            tmp.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.write(bytes)
                    gos.finish()
                }
            }
            tmp.renameTo(f)
            Log.i(TAG, "Wrote internal CBOR cache: ${f.absolutePath} (${f.length()} bytes)")
        } catch (ex: Exception) {
            Log.w(TAG, "writeIndexToInternalCache failed: ${ex.message}")
        }
    }

    private fun deleteInternalCache(context: Context) {
        try {
            val f = File(context.filesDir, INTERNAL_CBOR)
            if (f.exists()) f.delete()
        } catch (_: Exception) {}
    }

    /**
     * Ensure the in-memory AliasIndex is loaded. This function is suspend and idempotent.
     * Load priority:
     * 1) internal CBOR cache (fast)
     * 2) SAF binaries/aliases_optimized.cbor.gz (copy to internal & load)
     * 3) SAF assets/alias_master.json or serverdata species.json -> build index
     * After building/merging (including user aliases), write internal CBOR for next time.
     */
    suspend fun ensureIndexLoadedSuspend(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        if (indexLoaded && loadedIndex != null) {
            Log.d(TAG, "ensureIndexLoadedSuspend: already loaded")
            return@withContext
        }

        indexLoadMutex.withLock {
            if (indexLoaded && loadedIndex != null) {
                Log.d(TAG, "ensureIndexLoadedSuspend: already loaded (inside lock)")
                return@withLock
            }

            // 1) try internal cache
            val fromInternal: AliasIndex? = loadIndexFromInternalCache(context)
            if (fromInternal != null) {
                loadedIndex = fromInternal
                indexLoaded = true
                Log.i(TAG, "Loaded AliasIndex from internal cache")
                return@withLock
            }

            // 2) try SAF binaries (Documents/VT5/binaries/aliases_optimized.cbor.gz)
            val vt5 = saf.getVt5DirIfExists()
            if (vt5 != null) {
                try {
                    val binariesDir = vt5.findFile(BINARIES)?.takeIf { it.isDirectory }
                    val cborDoc = binariesDir?.findFile(CBOR_FILE)
                    if (cborDoc != null && cborDoc.isFile) {
                        context.contentResolver.openInputStream(cborDoc.uri)?.use { ins ->
                            val tmp = File(context.filesDir, "$INTERNAL_CBOR.tmp")
                            tmp.outputStream().use { outs -> ins.copyTo(outs) }
                            tmp.renameTo(File(context.filesDir, INTERNAL_CBOR))
                        }
                        val idx = loadIndexFromInternalCache(context)
                        if (idx != null) {
                            loadedIndex = idx
                            indexLoaded = true
                            Log.i(TAG, "Loaded AliasIndex from SAF binaries (copied to internal)")
                            return@withLock
                        }
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed loading cbor from SAF: ${ex.message}", ex)
                }
            }

            // 3) Build from SAF alias_master.json or from serverdata
            try {
                val masterFromAssets: AliasMaster? = try {
                    val assetsDir = vt5?.findFile(ASSETS)?.takeIf { it.isDirectory }
                    val masterDoc = assetsDir?.findFile(MASTER_FILE)?.takeIf { it.isFile }
                    masterDoc?.let { doc ->
                        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }?.let { txt ->
                            jsonPretty.decodeFromString(AliasMaster.serializer(), txt)
                        }
                    }
                } catch (_: Exception) { null }

                val baseMaster = masterFromAssets ?: run {
                    // fallback: try to build seed from serverdata (this will write master & cbor to SAF)
                    try {
                        generateSeedFromSpeciesJson(context, saf, vt5 ?: throw IllegalStateException("VT5 not present"))
                    } catch (ex: Exception) {
                        Log.w(TAG, "generateSeedFromSpeciesJson during ensureIndex failed: ${ex.message}", ex)
                    }
                    // try reading asset again
                    if (vt5 != null) {
                        val assetsDir2 = vt5.findFile(ASSETS)?.takeIf { it.isDirectory }
                        val masterDoc2 = assetsDir2?.findFile(MASTER_FILE)?.takeIf { it.isFile }
                        masterDoc2?.let { doc ->
                            context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }?.let { txt ->
                                try { jsonPretty.decodeFromString(AliasMaster.serializer(), txt) } catch (_: Exception) { null }
                            }
                        }
                    } else null
                }

                val finalMaster = baseMaster ?: AliasMaster(version = "2.1", timestamp = Instant.now().toString(), species = emptyList())
                val mergedMaster = mergeUserAliasesIntoMaster(context, vt5 ?: throw IllegalStateException("VT5 not present"), finalMaster)

                val index = mergedMaster.toAliasIndex()

                // persist to internal CBOR cache for future fast loads
                writeIndexToInternalCache(context, index)

                loadedIndex = index
                indexLoaded = true
                Log.i(TAG, "Built AliasIndex from JSON and wrote internal cache")
                return@withLock
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to build AliasIndex from JSON: ${ex.message}", ex)
            }

            // last fallback: no index available
            loadedIndex = null
            indexLoaded = false
            Log.w(TAG, "AliasIndex fallback: no index available")
        }
    }

    /** Quick helper to know if index is already loaded in memory */
    fun isIndexLoaded(): Boolean = indexLoaded

    /** Optional getter for the loaded index (null if not loaded) */
    fun getLoadedIndex(): AliasIndex? = loadedIndex

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

    @kotlinx.serialization.ExperimentalSerializationApi
    private suspend fun writeMasterAndCborToSaf(
        context: android.content.Context,
        master: AliasMaster,
        vt5RootDir: DocumentFile
    ) = withContext(Dispatchers.IO) {
        try {
            val jsonPrettyLocal = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

            // --- ASSETS: pretty JSON ---
            val assetsDir = vt5RootDir.findFile(ASSETS)?.takeIf { it.isDirectory } ?: vt5RootDir.createDirectory(ASSETS)
            if (assetsDir == null) {
                Log.e(TAG, "writeMasterAndCborToSaf: cannot access/create assets dir (vt5=${vt5RootDir.uri})")
                return@withContext
            }

            val masterName = MASTER_FILE
            // try to find existing or create new
            val existingMaster = assetsDir.findFile(masterName)?.takeIf { it.isFile }
            val masterDoc = existingMaster ?: kotlin.runCatching { assetsDir.createFile("application/json", masterName) }.getOrNull()
            if (masterDoc == null) {
                Log.e(TAG, "writeMasterAndCborToSaf: failed creating $masterName in assets")
            } else {
                val prettyJson = jsonPrettyLocal.encodeToString(AliasMaster.serializer(), master)
                try {
                    context.contentResolver.openOutputStream(masterDoc.uri, "w")?.use { os ->
                        os.write(prettyJson.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    Log.i(TAG, "writeMasterAndCborToSaf: wrote $masterName to ${masterDoc.uri} (${prettyJson.length} bytes)")
                } catch (ex: Exception) {
                    Log.e(TAG, "writeMasterAndCborToSaf: failed writing $masterName: ${ex.message}", ex)
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
            binariesDir.findFile(cborName)?.delete()
            val cborDoc = binariesDir.createFile("application/octet-stream", cborName)
            if (cborDoc != null) {
                try {
                    context.contentResolver.openOutputStream(cborDoc.uri, "w")?.use { os ->
                        os.write(gzipped)
                        os.flush()
                    }
                    Log.i(TAG, "writeMasterAndCborToSaf: wrote $cborName to ${cborDoc.uri} (${gzipped.size} bytes)")
                    // also update internal cache for faster subsequent loads
                    try {
                        writeIndexToInternalCache(context, index)
                        Log.i(TAG, "Internal CBOR cache updated after writeMasterAndCborToSaf")
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to update internal cache after writeMasterAndCborToSaf: ${ex.message}")
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "writeMasterAndCborToSaf: failed writing $cborName: ${ex.message}", ex)
                }
            } else {
                Log.e(TAG, "writeMasterAndCborToSaf: failed creating $cborName in binaries")
            }

        } catch (ex: Exception) {
            Log.e(TAG, "writeMasterAndCborToSaf failed: ${ex.message}", ex)
        }
    }

    /* SEED GENERATION */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun generateSeedFromSpeciesJson(
        context: android.content.Context,
        saf: SaFStorageHelper,
        vt5RootDir: DocumentFile
    ) = withContext(Dispatchers.IO) {
        try {
            // locate serverdata
            val serverDir = vt5RootDir.findFile("serverdata")?.takeIf { it.isDirectory }
            if (serverDir == null) {
                Log.w(TAG, "serverdata not available, aborting seed generation")
                return@withContext
            }

            // list contents for diagnostics
            kotlin.runCatching {
                val present = serverDir.listFiles().mapNotNull { it.name }
                Log.i(TAG, "serverdata contains: ${present.joinToString(", ")}")
            }

            // tolerant lookup for site_species file
            val siteSpeciesFile = serverDir.listFiles().firstOrNull { doc ->
                val nm = doc.name?.lowercase() ?: return@firstOrNull false
                nm == "site_species.json" || nm == "site_species" || nm.startsWith("site_species")
            }

            if (siteSpeciesFile == null) {
                Log.w(TAG, "No site_species file found in serverdata")
                return@withContext
            } else {
                Log.i(TAG, "Using site_species file: ${siteSpeciesFile.name} (uri=${siteSpeciesFile.uri})")
            }

            val siteBytes: ByteArray? = kotlin.runCatching {
                context.contentResolver.openInputStream(siteSpeciesFile.uri)?.use { it.readBytes() }
            }.getOrNull()

            if (siteBytes == null || siteBytes.isEmpty()) {
                Log.w(TAG, "site_species file is empty or could not be read")
                return@withContext
            }

            // strip BOM if present
            val bytesNoBom = if (siteBytes.size >= 3 && siteBytes[0] == 0xEF.toByte() && siteBytes[1] == 0xBB.toByte() && siteBytes[2] == 0xBF.toByte()) {
                siteBytes.copyOfRange(3, siteBytes.size)
            } else siteBytes

            val text = bytesNoBom.toString(Charsets.UTF_8).trim()
            // try parse flexibly: top-level array or object with "json" or "data" keys
            val siteSpeciesIds = mutableSetOf<String>()
            kotlin.runCatching {
                val root = Json.parseToJsonElement(text)
                var arr = root.jsonArrayOrNull()
                if (arr == null && root is kotlinx.serialization.json.JsonObject) {
                    arr = root["json"]?.jsonArray ?: root["data"]?.jsonArray ?: root["items"]?.jsonArray
                }
                // fallback: search for first array of objects
                if (arr == null && root is kotlinx.serialization.json.JsonObject) {
                    for ((k, v) in root) {
                        if (v is kotlinx.serialization.json.JsonArray) { arr = v; break }
                    }
                }
                if (arr == null) {
                    // try recursive search
                    arr = root.findFirstArrayWithObjects()
                }
                if (arr != null) {
                    arr.forEach { el ->
                        if (el is kotlinx.serialization.json.JsonObject) {
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

            if (siteSpeciesIds.isEmpty()) {
                Log.w(TAG, "No site_species entries found — aborting seed generation")
                return@withContext
            }

            // Load species map (prefer ServerDataCache)
            val snapshot = kotlin.runCatching { ServerDataCache.getOrLoad(context) }.getOrNull()
            val speciesMap = mutableMapOf<String, Pair<String, String?>>()
            if (snapshot != null) {
                snapshot.speciesById.forEach { (k, v) ->
                    speciesMap[k.lowercase()] = Pair(v.soortnaam ?: k, v.soortkey?.takeIf { it.isNotBlank() })
                }
            } else {
                // fallback to reading species.json from serverdata
                val speciesFile = serverDir.findFile("species.json")?.takeIf { it.isFile }
                val speciesBytes = speciesFile?.let { doc -> kotlin.runCatching { context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() } }.getOrNull() }
                if (speciesBytes != null) {
                    kotlin.runCatching {
                        val root = Json.parseToJsonElement(speciesBytes.toString(Charsets.UTF_8))
                        val arr = root.jsonArrayOrNull() ?: root.jsonObject["json"]?.jsonArray
                        arr?.forEach { el ->
                            if (el is kotlinx.serialization.json.JsonObject) {
                                val sid = el["soortid"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: return@forEach
                                val naam = el["soortnaam"]?.jsonPrimitive?.contentOrNull ?: sid
                                val key = el["soortkey"]?.jsonPrimitive?.contentOrNull
                                speciesMap[sid] = Pair(naam, key?.takeIf { it.isNotBlank() })
                            }
                        }
                    }.onFailure { Log.w(TAG, "Failed to parse species.json: ${it.message}") }
                }
            }

            // Build deterministic, sorted list of site species ids
            val sidList = siteSpeciesIds.toList().sortedWith(Comparator { a, b ->
                val ai = a.toIntOrNull(); val bi = b.toIntOrNull()
                when {
                    ai != null && bi != null -> ai.compareTo(bi)
                    ai != null && bi == null -> -1
                    ai == null && bi != null -> 1
                    else -> a.compareTo(b)
                }
            })

            val speciesList = sidList.map { sid ->
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

            // Build new master, then merge user aliases from any existing master BEFORE writing
            val newMaster = AliasMaster(version = "2.1", timestamp = Instant.now().toString(), species = speciesList)
            val mergedMaster = mergeUserAliasesIntoMaster(context, vt5RootDir, newMaster)

            // Write master (to assets) and CBOR (to binaries)
            writeMasterAndCborToSaf(context, mergedMaster, vt5RootDir)

            // Hot-load CBOR into matcher (reload)
            try { com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(context, saf) } catch (_: Exception) {}

            Log.i(TAG, "Seed generated: ${mergedMaster.species.size} species, ${mergedMaster.species.sumOf { it.aliases.size }} total aliases")
        } catch (ex: Exception) {
            Log.e(TAG, "Seed generation failed: ${ex.message}", ex)
        }
    }

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
                            alias = alias.copy(norm = normalizeLowerNoDiacritics(alias.text))
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
                val canonNorm = normalizeLowerNoDiacritics(sp.canonical)
                if (canonNorm.isNotBlank()) normToSpecies.getOrPut(canonNorm) { mutableSetOf() }.add(sid)
                sp.tilename?.let {
                    val tilNorm = normalizeLowerNoDiacritics(it)
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
                    val norm = ua.norm.ifBlank { normalizeLowerNoDiacritics(ua.text) }
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
        val cleaned = normalizeLowerNoDiacritics(text)
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

            // Write master JSON (pretty)
            val updatedJson = jsonPretty.encodeToString(AliasMaster.serializer(), updatedMaster)
            context.contentResolver.openOutputStream(masterDoc.uri, "w")?.use {
                it.write(updatedJson.toByteArray(Charsets.UTF_8))
                it.flush()
            }

            Log.i(TAG, "Flushed ${writeQueue.size} pending aliases to master")

            // Regenerate CBOR cache from master.toAliasIndex()
            val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory } ?: vt5.createDirectory(BINARIES) ?: return@withContext
            rebuildCborCache(updatedMaster, binaries, context)

            // After flush, update internal cache as well
            try {
                val idx = updatedMaster.toAliasIndex()
                writeIndexToInternalCache(context, idx)
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
    private suspend fun rebuildCborCache(master: AliasMaster, binariesDir: DocumentFile, context: Context) = withContext(Dispatchers.IO) {
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

            val cborFile = binariesDir.findFile(CBOR_FILE)?.also { it.delete() } ?: binariesDir.createFile("application/octet-stream", CBOR_FILE)
            if (cborFile != null) {
                context.contentResolver.openOutputStream(cborFile.uri, "w")?.use { it.write(gzipped); it.flush() }
                Log.i(TAG, "Rebuilt CBOR cache: ${index.json.size} records, ${gzipped.size} bytes compressed")
                // update internal cache as well
                try {
                    writeIndexToInternalCache(context, index)
                    Log.i(TAG, "Internal CBOR cache updated after rebuildCborCache")
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed to update internal cache after rebuildCborCache: ${ex.message}")
                }
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

    // small helpers for JsonElement convenience
    private fun JsonElement.jsonArrayOrNull() = try { this.jsonArray } catch (_: Throwable) { null }
    private fun JsonElement.findFirstArrayWithObjects(): kotlinx.serialization.json.JsonArray? {
        when (this) {
            is kotlinx.serialization.json.JsonArray -> {
                if (this.any { it is kotlinx.serialization.json.JsonObject }) return this
                for (el in this) {
                    val found = el.findFirstArrayWithObjects()
                    if (found != null) return found
                }
            }
            is kotlinx.serialization.json.JsonObject -> {
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