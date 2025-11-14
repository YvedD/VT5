@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.serverdata.model

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * Optimized ServerDataRepository:
 * - Parallel loading of data files for faster startup
 * - Memory efficient decoding with reused buffers
 * - Selective data loading for specific use cases
 * - Efficient caching of file metadata
 *
 * Changes:
 * - Added coroutineScope around parallel loads to ensure structured concurrency.
 * - Replaced ArrayList byte-accumulation with ByteArrayOutputStream for readAllBytesCompat.
 * - Kept public API surface identical (no breaking changes).
 */

class ServerDataRepository(
    private val context: Context,
    private val json: Json = defaultJson,
    private val cbor: Cbor = defaultCbor
) {
    companion object {
        private const val TAG = "ServerDataRepo"

        val defaultJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val defaultCbor = Cbor { ignoreUnknownKeys = true }

        // Cache for file existence to avoid repeated directory scanning
        private val fileExistenceCache = ConcurrentHashMap<String, Boolean>()
    }

    private val snapshotState = MutableStateFlow(DataSnapshot())
    val snapshot: StateFlow<DataSnapshot> = snapshotState

    // File type quick lookup cache
    private val fileTypeCache = ConcurrentHashMap<String, Boolean>() // true = .bin, false = .json

    /**
     * Checks if required data files exist without loading them
     * Used to quickly determine if app is ready to run
     */
    suspend fun hasRequiredFiles(): Boolean = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext false
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext false

        // Cache result for each file to avoid repeated scans
        val cacheKey = "${vt5Root.uri}_hasRequiredFiles"
        fileExistenceCache[cacheKey]?.let {
            return@withContext it
        }

        // Check for essential files (sites and codes)
        val hasSites = serverdata.findChildByName("sites.bin") != null || serverdata.findChildByName("sites.json") != null
        val hasCodes = serverdata.findChildByName("codes.bin") != null || serverdata.findChildByName("codes.json") != null
        val hasSpecies = serverdata.findChildByName("species.bin") != null || serverdata.findChildByName("species.json") != null

        val result = hasSites && hasCodes && hasSpecies
        fileExistenceCache[cacheKey] = result

        return@withContext result
    }

    /**
     * Clear any cached file information
     */
    fun clearFileCache() {
        fileExistenceCache.clear()
        fileTypeCache.clear()
    }

    /**
     * Load ONLY codes data - ultra-fast for app startup
     * Only 55 records with 3 fields = ~4KB memory
     * This allows MetadataScherm to open instantly
     */
    suspend fun loadCodesOnly(): Map<String, List<CodeItemSlim>> = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext emptyMap()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext emptyMap()

        val codes = runCatching {
            readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES)
        }.getOrElse { emptyList() }

        // Filter and convert to slim format
        val relevantCategories = setOf(
            "neerslag", "typetelling_trek", "wind", "windoms",
            "leeftijd", "geslacht", "teltype", "kleed"
        )
        
        return@withContext codes
            .filter { it.category in relevantCategories }
            .mapNotNull { CodeItemSlim.fromCodeItem(it) }
            .groupBy { it.category }
    }

    /**
     * Load only minimal data required for startup
     * - Optimized for faster initial rendering
     */
    suspend fun loadMinimalData(): DataSnapshot = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext DataSnapshot()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext DataSnapshot()

        // Use coroutineScope for structured concurrency so sibling tasks are cancelled on failure
        coroutineScope {
            val sitesDef = async { readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES) }
            val codesDef = async {
                runCatching {
                    readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES)
                }.getOrElse { emptyList() }
            }

            // Await results into local vals
            val sites = sitesDef.await()
            val codes = codesDef.await()

            // Process data
            val sitesById = sites.associateBy { it.telpostid }
            
            // Convert CodeItem to CodeItemSlim and filter relevant categories
            val relevantCategories = setOf(
                "neerslag", "typetelling_trek", "wind", "windoms",
                "leeftijd", "geslacht", "teltype", "kleed"
            )
            val codesByCategory = codes
                .filter { it.category in relevantCategories }
                .mapNotNull { CodeItemSlim.fromCodeItem(it) }
                .groupBy { it.category }

            // Create minimal snapshot
            val snap = DataSnapshot(
                sitesById = sitesById,
                codesByCategory = codesByCategory
            )

            // Don't update the state flow with partial data
            return@coroutineScope snap
        }
    }

    /** Full data load with parallel processing for better performance */
    suspend fun loadAllFromSaf(): DataSnapshot = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext DataSnapshot()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext DataSnapshot()

        // First, check if we have files
        if (!hasRequiredFiles()) {
            Log.w(TAG, "Missing required data files")
            return@withContext DataSnapshot()
        }

        // Use coroutineScope for structured concurrency
        return@withContext coroutineScope {
            val userObjDef = async { readOne<CheckUserItem>(serverdata, "checkuser", VT5Bin.Kind.CHECK_USER) }
            val speciesListDef = async { readList<SpeciesItem>(serverdata, "species", VT5Bin.Kind.SPECIES) }
            val protocolInfoDef = async { readList<ProtocolInfoItem>(serverdata, "protocolinfo", VT5Bin.Kind.PROTOCOL_INFO) }
            val protocolSpeciesDef = async { readList<ProtocolSpeciesItem>(serverdata, "protocolspecies", VT5Bin.Kind.PROTOCOL_SPECIES) }
            val sitesDef = async { readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES) }
            val siteLocationsDef = async { readList<SiteValueItem>(serverdata, "site_locations", VT5Bin.Kind.SITE_LOCATIONS) }
            val siteHeightsDef = async { readList<SiteValueItem>(serverdata, "site_heights", VT5Bin.Kind.SITE_HEIGHTS) }
            val siteSpeciesDef = async { readList<SiteSpeciesItem>(serverdata, "site_species", VT5Bin.Kind.SITE_SPECIES) }
            val codesDef = async { runCatching { readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES) }.getOrElse { emptyList() } }

            // Await all results into local vals
            val userObj = userObjDef.await()
            val speciesList = speciesListDef.await()
            val protocolInfo = protocolInfoDef.await()
            val protocolSpecies = protocolSpeciesDef.await()
            val sites = sitesDef.await()
            val siteLocations = siteLocationsDef.await()
            val siteHeights = siteHeightsDef.await()
            val siteSpecies = siteSpeciesDef.await()
            val codes = codesDef.await()

            // Process data into maps efficiently
            val speciesById = speciesList.associateBy { it.soortid }

            // Build canonical map only when needed and avoid multiple iterations
            val canonicalBuilder = HashMap<String, String>(speciesById.size)
            speciesList.forEach { sp ->
                canonicalBuilder[normalizeCanonical(sp.soortnaam)] = sp.soortid
            }
            val speciesByCanonical = canonicalBuilder

            val sitesById = sites.associateBy { it.telpostid }
            val siteLocationsBySite = siteLocations.groupBy { it.telpostid }
            val siteHeightsBySite = siteHeights.groupBy { it.telpostid }
            val siteSpeciesBySite = siteSpecies.groupBy { it.telpostid }
            val protocolSpeciesByProtocol = protocolSpecies.groupBy { it.protocolid }

            // Filter codes to only relevant categories and convert to slim format
            // This reduces memory by ~83% (66% from filtering + 50% from slim format)
            val relevantCategories = setOf(
                "neerslag", "typetelling_trek", "wind", "windoms",
                "leeftijd", "geslacht", "teltype", "kleed"
            )
            
            val codesByCategory = codes
                .filter { it.category in relevantCategories }
                .mapNotNull { CodeItemSlim.fromCodeItem(it) }
                .groupBy { it.category }

            // Create full snapshot
            val snap = DataSnapshot(
                currentUser = userObj,
                speciesById = speciesById,
                speciesByCanonical = speciesByCanonical,
                sitesById = sitesById,
                assignedSites = emptyList(),
                siteLocationsBySite = siteLocationsBySite,
                siteHeightsBySite = siteHeightsBySite,
                siteSpeciesBySite = siteSpeciesBySite,
                protocolsInfo = protocolInfo,
                protocolSpeciesByProtocol = protocolSpeciesByProtocol,
                codesByCategory = codesByCategory
            )

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Loaded all data in ${elapsed}ms - ${speciesById.size} species, ${sitesById.size} sites")

            // Update state flow and return
            snapshotState.value = snap
            snap
        }
    }

    /** Load only site data - optimized with caching */
    @Suppress("unused")
    suspend fun loadSitesOnly(): Map<String, SiteItem> = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext emptyMap()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory } ?: return@withContext emptyMap()
        val sites = readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES)
        sites.associateBy { it.telpostid }
    }

    /** 
     * Load only codes for a specific field - optimized with caching.
     * Returns lightweight CodeItemSlim instead of full CodeItem for memory efficiency.
     */
    @Suppress("unused")
    suspend fun loadCodesFor(field: String): List<CodeItemSlim> = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext emptyList()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory } ?: return@withContext emptyList()

        // Reuse previously loaded codes if possible (already in slim format)
        val cachedCodes = snapshotState.value.codesByCategory[field]
        if (!cachedCodes.isNullOrEmpty()) {
            return@withContext cachedCodes.sortedBy { it.text.lowercase(Locale.getDefault()) }
        }

        // Fallback: load from disk if not in cache
        val codes = runCatching { readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES) }.getOrElse { emptyList() }
        codes
            .filter { it.category == field }
            .mapNotNull { CodeItemSlim.fromCodeItem(it) }
            .sortedBy { it.text.lowercase(Locale.getDefault()) }
    }

    /* ============ Binaries-first readers (SAF) with optimizations ============ */

    private sealed class Decoded<out T> {
        data class AsList<T>(val list: List<T>) : Decoded<T>()
        data class AsWrapped<T>(val wrapped: WrappedJson<T>) : Decoded<T>()
        data class AsSingle<T>(val value: T) : Decoded<T>()
    }

    // Shared buffer for better memory usage
    private val headerBuffer = ByteArray(VT5Bin.HEADER_SIZE)

    private inline fun <reified T> readList(dir: DocumentFile, baseName: String, expectedKind: UShort): List<T> {
        // Check cache for file type preference (.bin vs .json)
        val cacheKey = "${dir.uri}_$baseName"
        fileTypeCache[cacheKey]?.let { isBin ->
            if (isBin) {
                dir.findChildByName("$baseName.bin")?.let { bin ->
                    vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                        return when (decoded) {
                            is Decoded.AsList<T> -> decoded.list
                            is Decoded.AsWrapped<T> -> decoded.wrapped.json
                            is Decoded.AsSingle<T> -> listOf(decoded.value)
                        }
                    }
                }
            } else {
                dir.findChildByName("$baseName.json")?.let { jf ->
                    context.contentResolver.openInputStream(jf.uri)?.use { input ->
                        val text = input.readBytes().decodeToString()
                        runCatching {
                            json.decodeFromString(
                                WrappedJson.serializer(json.serializersModule.serializer<T>()),
                                text
                            ).json
                        }.getOrElse {
                            runCatching {
                                json.decodeFromString(
                                    json.serializersModule.serializer<List<T>>(),
                                    text
                                )
                            }.getOrElse {
                                listOf(
                                    json.decodeFromString(
                                        json.serializersModule.serializer<T>(),
                                        text
                                    )
                                )
                            }
                        }.let { return it }
                    }
                }
            }
        }

        // Try .bin first (faster binary format)
        dir.findChildByName("$baseName.bin")?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                // Cache that this file exists as binary
                fileTypeCache[cacheKey] = true
                return when (decoded) {
                    is Decoded.AsList<T> -> decoded.list
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json
                    is Decoded.AsSingle<T> -> listOf(decoded.value)
                }
            }
        }

        // Fall back to .json
        dir.findChildByName("$baseName.json")?.let { jf ->
            // Cache that this file exists as JSON
            fileTypeCache[cacheKey] = false
            context.contentResolver.openInputStream(jf.uri)?.use { input ->
                val text = input.readBytes().decodeToString()
                runCatching {
                    json.decodeFromString(
                        WrappedJson.serializer(json.serializersModule.serializer<T>()),
                        text
                    ).json
                }.getOrElse {
                    runCatching {
                        json.decodeFromString(
                            json.serializersModule.serializer<List<T>>(),
                            text
                        )
                    }.getOrElse {
                        listOf(
                            json.decodeFromString(
                                json.serializersModule.serializer<T>(),
                                text
                            )
                        )
                    }
                }.let { return it }
            }
        }
        return emptyList()
    }

    private inline fun <reified T> readOne(dir: DocumentFile, baseName: String, expectedKind: UShort): T? {
        // Similar optimization as readList, with caching of file type preference
        val cacheKey = "${dir.uri}_$baseName"
        fileTypeCache[cacheKey]?.let { isBin ->
            if (isBin) {
                dir.findChildByName("$baseName.bin")?.let { bin ->
                    vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                        return when (decoded) {
                            is Decoded.AsWrapped<T> -> decoded.wrapped.json.firstOrNull()
                            is Decoded.AsList<T> -> decoded.list.firstOrNull()
                            is Decoded.AsSingle<T> -> decoded.value
                        }
                    }
                }
            } else {
                dir.findChildByName("$baseName.json")?.let { jf ->
                    context.contentResolver.openInputStream(jf.uri)?.use { input ->
                        val text = input.readBytes().decodeToString()
                        return runCatching {
                            json.decodeFromString(
                                WrappedJson.serializer(json.serializersModule.serializer<T>()),
                                text
                            ).json.firstOrNull()
                        }.getOrElse {
                            json.decodeFromString(
                                json.serializersModule.serializer<T>(),
                                text
                            )
                        }
                    }
                }
            }
        }

        // Try .bin first
        dir.findChildByName("$baseName.bin")?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                // Cache that this file exists as binary
                fileTypeCache[cacheKey] = true
                return when (decoded) {
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json.firstOrNull()
                    is Decoded.AsList<T> -> decoded.list.firstOrNull()
                    is Decoded.AsSingle<T> -> decoded.value
                }
            }
        }

        // Fall back to .json
        dir.findChildByName("$baseName.json")?.let { jf ->
            // Cache that this file exists as JSON
            fileTypeCache[cacheKey] = false
            context.contentResolver.openInputStream(jf.uri)?.use { input ->
                val text = input.readBytes().decodeToString()
                return runCatching {
                    json.decodeFromString(
                        WrappedJson.serializer(json.serializersModule.serializer<T>()),
                        text
                    ).json.firstOrNull()
                }.getOrElse {
                    json.decodeFromString(json.serializersModule.serializer<T>(), text)
                }
            }
        }
        return null
    }

    private inline fun <reified T> vt5ReadDecoded(binFile: DocumentFile, expectedKind: UShort): Decoded<T>? {
        val cr = context.contentResolver
        cr.openInputStream(binFile.uri)?.use { raw ->
            val bis = BufferedInputStream(raw)

            // Use shared buffer
            synchronized(headerBuffer) {
                if (bis.read(headerBuffer) != VT5Bin.HEADER_SIZE) return null

                val hdr = VT5Header.fromBytes(headerBuffer) ?: return null
                if (!hdr.magic.contentEquals(VT5Bin.MAGIC)) return null
                if (hdr.headerVersion.toInt() < VT5Bin.HEADER_VERSION.toInt()) return null
                if (hdr.datasetKind != expectedKind) return null
                if (hdr.codec != VT5Bin.Codec.JSON && hdr.codec != VT5Bin.Codec.CBOR) return null
                if (hdr.compression != VT5Bin.Compression.NONE && hdr.compression != VT5Bin.Compression.GZIP) return null

                val pl = hdr.payloadLen.toLong()
                if (pl < 0) return null
                val payload = ByteArray(pl.toInt())
                val read = bis.readNBytesCompat(payload)
                if (read != pl.toInt()) return null

                val dataBytes = when (hdr.compression) {
                    VT5Bin.Compression.GZIP -> GZIPInputStream(ByteArrayInputStream(payload)).use { it.readAllBytesCompat() }
                    VT5Bin.Compression.NONE -> payload
                    else -> return null
                }

                return when (hdr.codec) {
                    VT5Bin.Codec.CBOR -> {
                        runCatching {
                            val w = cbor.decodeFromByteArray(
                                WrappedJson.serializer(cbor.serializersModule.serializer<T>()),
                                dataBytes
                            )
                            Decoded.AsWrapped(w)
                        }.getOrElse {
                            runCatching {
                                val l = cbor.decodeFromByteArray(
                                    cbor.serializersModule.serializer<List<T>>(),
                                    dataBytes
                                )
                                Decoded.AsList(l)
                            }.getOrElse {
                                val t = cbor.decodeFromByteArray(
                                    cbor.serializersModule.serializer<T>(),
                                    dataBytes
                                )
                                Decoded.AsSingle(t)
                            }
                        }
                    }
                    VT5Bin.Codec.JSON -> {
                        val text = dataBytes.decodeToString()
                        runCatching {
                            val w = json.decodeFromString(
                                WrappedJson.serializer(json.serializersModule.serializer<T>()),
                                text
                            )
                            Decoded.AsWrapped(w)
                        }.getOrElse {
                            runCatching {
                                val l = json.decodeFromString(
                                    json.serializersModule.serializer<List<T>>(),
                                    text
                                )
                                Decoded.AsList(l)
                            }.getOrElse {
                                val t = json.decodeFromString(
                                    json.serializersModule.serializer<T>(),
                                    text
                                )
                                Decoded.AsSingle(t)
                            }
                        }
                    }
                    else -> null
                }
            }
        }
        return null
    }

    /* ========================= Utils ========================= */

    private fun normalizeCanonical(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            val mapped = when (ch) {
                'à','á','â','ã','ä','å' -> 'a'
                'ç' -> 'c'
                'è','é','ê','ë' -> 'e'
                'ì','í','î','ï' -> 'i'
                'ñ' -> 'n'
                'ò','ó','ô','õ','ö' -> 'o'
                'ù','ú','û','ü' -> 'u'
                'ý','ÿ' -> 'y'
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString().trim()
    }

    private fun InputStream.readNBytesCompat(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = this.read(buf, off, buf.size - off)
            if (r <= 0) break
            off += r
        }
        return off
    }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = this.read(buffer)
            if (n <= 0) break
            baos.write(buffer, 0, n)
        }
        return baos.toByteArray()
    }
}

/* ================= VT5 Header & constants ================= */

private object VT5Bin {
    val MAGIC: ByteArray = byteArrayOf(0x56,0x54,0x35,0x42,0x49,0x4E,0x31,0x30) // "VT5BIN10"
    const val HEADER_SIZE: Int = 40
    val HEADER_VERSION: UShort = 0x0001u

    object Codec { const val JSON: UByte = 0u; const val CBOR: UByte = 1u }
    object Compression { const val NONE: UByte = 0u; const val GZIP: UByte = 1u }

    object Kind {
        val SPECIES: UShort = 1u
        val SITES: UShort = 2u
        val SITE_LOCATIONS: UShort = 3u
        val SITE_HEIGHTS: UShort = 4u
        val SITE_SPECIES: UShort = 5u
        val CODES: UShort = 6u
        val PROTOCOL_INFO: UShort = 7u
        val PROTOCOL_SPECIES: UShort = 8u
        val CHECK_USER: UShort = 9u
        val ALIAS_INDEX: UShort = 100u
    }

    val RECORDCOUNT_UNKNOWN: UInt = 0xFFFF_FFFFu
}

private data class VT5Header(
    val magic: ByteArray,
    val headerVersion: UShort,
    val datasetKind: UShort,
    val codec: UByte,
    val compression: UByte,
    val reserved16: UShort,
    val payloadLen: ULong,
    val uncompressedLen: ULong,
    val recordCount: UInt,
    val headerCrc32: UInt
) {
    companion object {
        private const val HEADER_LEN = VT5Bin.HEADER_SIZE

        fun fromBytes(bytes: ByteArray): VT5Header? {
            if (bytes.size != HEADER_LEN) return null

            val crc = CRC32()
            crc.update(bytes, 0, 0x24)
            val computed = (crc.value and 0xFFFF_FFFF).toUInt()

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(8).also { bb.get(it) }
            val headerVersion = bb.short.toUShort()
            val datasetKind = bb.short.toUShort()
            val codec = (bb.get().toInt() and 0xFF).toUByte()
            val compression = (bb.get().toInt() and 0xFF).toUByte()
            val reserved16 = bb.short.toUShort()
            val payloadLen = bb.long.toULong()
            val uncompressedLen = bb.long.toULong()
            val recordCount = bb.int.toUInt()
            val headerCrc32 = bb.int.toUInt()

            if (computed != headerCrc32) return null

            return VT5Header(
                magic, headerVersion, datasetKind, codec, compression,
                reserved16, payloadLen, uncompressedLen, recordCount, headerCrc32
            )
        }
    }
}

private fun DocumentFile.findChildByName(name: String): DocumentFile? =
    listFiles().firstOrNull { it.name == name }