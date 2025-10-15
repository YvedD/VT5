@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.serverdata.model

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * ServerDataRepository
 *
 * - Singleton via getInstance(ctx)
 * - Gerichte, off-main loaders met eenvoudige caching:
 *     * loadSites()                 → Map<String, SiteItem>
 *     * loadCodesFor(field)         → List<CodeItem> (bv. "wind", "neerslag", "typetelling_trek")
 *     * loadCheckUser()             → CheckUserItem?
 *     * tryWarmup(fields)           → optionele zachte prefetch
 *
 * Let op:
 * - 'species' en andere zware datasets laden we NIET hier voor Metadata; doe dat pas wanneer nodig.
 * - Caching wordt ongeldig wanneer het onderliggende bestand (lastModified) wijzigt.
 */
class ServerDataRepository private constructor(
    private val context: Context,
    private val json: Json = defaultJson,
    private val cbor: Cbor = defaultCbor
) {

    /* -------------------- Public snapshot (optioneel) -------------------- */
    private val snapshotState = MutableStateFlow(DataSnapshot())
    val snapshot: StateFlow<DataSnapshot> = snapshotState

    /**
     * Legacy "alles-in-1" loader — vooral nuttig voor debug / complete refresh.
     * In UX-kritische paden liever de gerichte loaders gebruiken.
     */
    suspend fun loadAllFromSaf(): DataSnapshot = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext DataSnapshot()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext DataSnapshot()

        val userObj = readOne<CheckUserItem>(serverdata, "checkuser", VT5Bin.Kind.CHECK_USER)

        val speciesList = readList<SpeciesItem>(serverdata, "species", VT5Bin.Kind.SPECIES)
        val protocolInfo = readList<ProtocolInfoItem>(serverdata, "protocolinfo", VT5Bin.Kind.PROTOCOL_INFO)
        val protocolSpecies = readList<ProtocolSpeciesItem>(serverdata, "protocolspecies", VT5Bin.Kind.PROTOCOL_SPECIES)
        val sites = readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES)
        val siteLocations = readList<SiteValueItem>(serverdata, "site_locations", VT5Bin.Kind.SITE_LOCATIONS)
        val siteHeights = readList<SiteValueItem>(serverdata, "site_heights", VT5Bin.Kind.SITE_HEIGHTS)
        val siteSpecies = readList<SiteSpeciesItem>(serverdata, "site_species", VT5Bin.Kind.SITE_SPECIES)
        val codes = runCatching { readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES) }
            .getOrElse { emptyList() }

        val speciesById = speciesList.associateBy { it.soortid }
        val speciesByCanonical = speciesList.associate { sp ->
            normalizeCanonical(sp.soortnaam) to sp.soortid
        }

        val sitesById = sites.associateBy { it.telpostid }
        val siteLocationsBySite = siteLocations.groupBy { it.telpostid }
        val siteHeightsBySite = siteHeights.groupBy { it.telpostid }
        val siteSpeciesBySite = siteSpecies.groupBy { it.telpostid }
        val protocolSpeciesByProtocol = protocolSpecies.groupBy { it.protocolid }

        // codes gegroepeerd op category (veld) – fallback op key prefix
        val codesByCategory = codes.groupBy { it.category ?: it.key?.substringBefore('_') ?: "uncategorized" }

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

        snapshotState.value = snap
        snap
    }

    /* -------------------- Gerichte loaders + caches -------------------- */

    private data class CacheEntry<T>(val lastModified: Long, val value: T)

    private val sitesCache = AtomicReference<CacheEntry<Map<String, SiteItem>>?>(null)
    private val codesCache = ConcurrentHashMap<String, CacheEntry<List<CodeItem>>>() // per "veld"
    private val checkUserCache = AtomicReference<CacheEntry<CheckUserItem?>?>(null)

    /**
     * Laad sites één keer, cache op lastModified.
     */
    suspend fun loadSites(): Map<String, SiteItem> = withContext(Dispatchers.IO) {
        val located = locate("serverdata", "sites") ?: return@withContext emptyMap()
        val (dir, file) = located
        val lm = file.lastModified()

        sitesCache.get()?.let { if (it.lastModified == lm) return@withContext it.value }

        val list = readList<SiteItem>(dir, "sites", VT5Bin.Kind.SITES)
        val map = list.associateBy { it.telpostid }
        sitesCache.set(CacheEntry(lm, map))
        map
    }

    /**
     * Laad codes voor één "veld" (bv. "wind", "neerslag", "typetelling_trek").
     * Leest het volledige codes-bestand één keer en cachet alle categorieën tegelijk.
     * Past typetelling-filters toe op tekstkey zoals gevraagd.
     */
    suspend fun loadCodesFor(field: String): List<CodeItem> = withContext(Dispatchers.IO) {
        val located = locate("serverdata", "codes") ?: return@withContext emptyList()
        val (dir, file) = located
        val lm = file.lastModified()

        codesCache[field]?.let { if (it.lastModified == lm) return@withContext it.value }

        val all = readList<CodeItem>(dir, "codes", VT5Bin.Kind.CODES)
        val grouped = all.groupBy { it.category ?: it.key?.substringBefore('_') ?: "uncategorized" }

        // Cache alle categorieën met dezelfde lastModified
        grouped.forEach { (cat, list) ->
            val prepared = list.sortedWith(
                compareBy(
                    { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
                )
            )
            codesCache[cat] = CacheEntry(lm, prepared)
        }

        // Neem de gevraagde categorie en pas filter toe voor typetelling
        val base = (grouped[field] ?: emptyList())
        val filtered =
            if (field == "typetelling_trek") {
                base.filterNot { c ->
                    val k = c.key ?: ""
                    k.contains("_sound") ||
                            k.contains("_ringen") ||
                            k.startsWith("samplingrate_") ||
                            k.startsWith("gain_") ||
                            k.startsWith("verstoring_")
                }
            } else base

        val sorted = filtered.sortedWith(
            compareBy(
                { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
            )
        )

        codesCache[field] = CacheEntry(lm, sorted)
        sorted
    }

    /**
     * Laad CheckUser (voor credentials/ui).
     */
    suspend fun loadCheckUser(): CheckUserItem? = withContext(Dispatchers.IO) {
        val located = locate("serverdata", "checkuser") ?: return@withContext null
        val (dir, file) = located
        val lm = file.lastModified()
        checkUserCache.get()?.let { if (it.lastModified == lm) return@withContext it.value }

        val one = readOne<CheckUserItem>(dir, "checkuser", VT5Bin.Kind.CHECK_USER)
        checkUserCache.set(CacheEntry(lm, one))
        one
    }

    /**
     * Optionele warm-up: probeer sites/codes/user alvast te cache’n.
     */
    suspend fun tryWarmup(codeFields: List<String>) = withContext(Dispatchers.IO) {
        runCatching { loadSites() }
        runCatching { loadCheckUser() }
        codeFields.forEach { f -> runCatching { loadCodesFor(f) } }
    }

    /* -------------------- Binaries-first readers (SAF) -------------------- */

    private sealed class Decoded<out T> {
        data class AsList<T>(val list: List<T>) : Decoded<T>()
        data class AsWrapped<T>(val wrapped: WrappedJson<T>) : Decoded<T>()
        data class AsSingle<T>(val value: T) : Decoded<T>()
    }

    private inline fun <reified T> readList(dir: DocumentFile, baseName: String, expectedKind: UShort): List<T> {
        dir.findChildByName("$baseName.bin")?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                return when (decoded) {
                    is Decoded.AsList<T> -> decoded.list
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json
                    is Decoded.AsSingle<T> -> listOf(decoded.value)
                }
            }
        }
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
        return emptyList()
    }

    private inline fun <reified T> readOne(dir: DocumentFile, baseName: String, expectedKind: UShort): T? {
        dir.findChildByName("$baseName.bin")?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                return when (decoded) {
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json.firstOrNull()
                    is Decoded.AsList<T> -> decoded.list.firstOrNull()
                    is Decoded.AsSingle<T> -> decoded.value
                }
            }
        }
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
        return null
    }

    private inline fun <reified T> vt5ReadDecoded(binFile: DocumentFile, expectedKind: UShort): Decoded<T>? {
        val cr = context.contentResolver
        cr.openInputStream(binFile.uri)?.use { raw ->
            val bis = BufferedInputStream(raw)
            val headerBytes = ByteArray(VT5Bin.HEADER_SIZE)
            if (bis.read(headerBytes) != VT5Bin.HEADER_SIZE) return null

            val hdr = VT5Header.fromBytes(headerBytes) ?: return null
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
        return null
    }

    /* -------------------- Helpers -------------------- */

    private fun locate(subdir: String, base: String): Pair<DocumentFile, DocumentFile>? {
        val saf = SaFStorageHelper(context)
        val vt5 = saf.getVt5DirIfExists() ?: return null
        val dir = vt5.findChildByName(subdir)?.takeIf { it.isDirectory } ?: return null
        val bin = dir.findChildByName("$base.bin")
        val json = dir.findChildByName("$base.json")
        val pick = bin ?: json ?: return null
        return dir to pick
    }

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
        val out = ArrayList<Byte>()
        val tmp = ByteArray(8192)
        while (true) {
            val n = this.read(tmp)
            if (n <= 0) break
            for (i in 0 until n) out.add(tmp[i])
        }
        return ByteArray(out.size) { idx -> out[idx] }
    }

    companion object {
        @Volatile private var INSTANCE: ServerDataRepository? = null
        fun getInstance(ctx: Context): ServerDataRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServerDataRepository(ctx.applicationContext).also { INSTANCE = it }
            }

        val defaultJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val defaultCbor = Cbor { ignoreUnknownKeys = true }
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

/** Zoek een child in DocumentFile by name (exact match). */
private fun DocumentFile.findChildByName(name: String): DocumentFile? =
    listFiles().firstOrNull { it.name == name }
