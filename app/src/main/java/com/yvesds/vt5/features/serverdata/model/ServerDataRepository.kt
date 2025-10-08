@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.serverdata.model

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.json.decodeFromStream
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

class ServerDataRepository private constructor(
    private val context: Context,
    private val json: Json = defaultJson,
    private val cbor: Cbor = defaultCbor
) {
    companion object {
        @Volatile private var INSTANCE: ServerDataRepository? = null

        fun getInstance(ctx: Context): ServerDataRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServerDataRepository(ctx.applicationContext).also { INSTANCE = it }
            }

        val defaultJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
        val defaultCbor = Cbor { ignoreUnknownKeys = true }
    }

    // ---------- eenvoudige in-memory caches (per app-run) ----------
    private val cacheSites = AtomicReference<Map<String, SiteItem>?>(null)
    private val cacheCodesByField = ConcurrentHashMap<String, List<CodeItem>>() // b.v. "wind" → lijst

    private val saf by lazy { SaFStorageHelper(context) }

    // =====================================================================
    //  Snelle, gerichte loaders (ad-hoc) — gebruikt door MetadataScherm
    // =====================================================================

    /** Laad enkel `sites.json` / `sites.bin` (streaming) en cache in-memory. */
    suspend fun loadSites(): Map<String, SiteItem> = withContext(Dispatchers.IO) {
        cacheSites.get()?.let { return@withContext it }

        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext emptyMap()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext emptyMap()

        // Probeer eerst .bin → dan .json (streaming)
        val list = readListStreamingOrBin<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES)
        val map = list.associateBy { it.telpostid }
        cacheSites.set(map)
        map
    }

    /**
     * Laad alleen de codes voor een bepaald veld (b.v. "wind", "neerslag", "typetelling_trek")
     * uit `codes.json`/`codes.bin`, filter en sorteer. Resultaat wordt gecached per `field`.
     */
    suspend fun loadCodesFor(field: String): List<CodeItem> = withContext(Dispatchers.IO) {
        cacheCodesByField[field]?.let { return@withContext it }

        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext emptyList()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext emptyList()

        val all = readListStreamingOrBin<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES)

        // Herkenning van 'veld' (jouw dump gebruikt NL-keys: tekstkey/tekst/sortering/veld/waarde)
        val filtered = all.asSequence()
            .filter { item ->
                // primaire match op 'category' of 'veld'
                when {
                    item.category?.equals(field, ignoreCase = true) == true -> true
                    else -> false
                } || run {
                    val head = (item.key ?: item.id ?: "")
                        .substringBefore('_') // b.v. "neerslag_regen" ⇒ "neerslag"
                    head.equals(field, ignoreCase = true)
                }
            }
            .sortedWith(
                compareBy(
                    { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
                )
            )
            .toList()

        cacheCodesByField[field] = filtered
        filtered
    }

    // =====================================================================
    //  Volledige snapshot (legacy) — blijft bruikbaar waar nodig
    // =====================================================================

    suspend fun loadAllFromSaf(): DataSnapshot = withContext(Dispatchers.IO) {
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext DataSnapshot()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext DataSnapshot()

        val userObj = readOneStreamingOrBin<CheckUserItem>(serverdata, "checkuser", VT5Bin.Kind.CHECK_USER)

        val speciesList = readListStreamingOrBin<SpeciesItem>(serverdata, "species", VT5Bin.Kind.SPECIES)
        val protocolInfo = readListStreamingOrBin<ProtocolInfoItem>(serverdata, "protocolinfo", VT5Bin.Kind.PROTOCOL_INFO)
        val protocolSpecies = readListStreamingOrBin<ProtocolSpeciesItem>(serverdata, "protocolspecies", VT5Bin.Kind.PROTOCOL_SPECIES)
        val sites = readListStreamingOrBin<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES)
        val siteLocations = readListStreamingOrBin<SiteValueItem>(serverdata, "site_locations", VT5Bin.Kind.SITE_LOCATIONS)
        val siteHeights = readListStreamingOrBin<SiteValueItem>(serverdata, "site_heights", VT5Bin.Kind.SITE_HEIGHTS)
        val siteSpecies = readListStreamingOrBin<SiteSpeciesItem>(serverdata, "site_species", VT5Bin.Kind.SITE_SPECIES)
        val codes = readListStreamingOrBin<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES)

        val speciesById = speciesList.associateBy { it.soortid }
        val speciesByCanonical = speciesList.associate { sp -> normalizeCanonical(sp.soortnaam) to sp.soortid }

        val sitesById = sites.associateBy { it.telpostid }
        val siteLocationsBySite = siteLocations.groupBy { it.telpostid }
        val siteHeightsBySite = siteHeights.groupBy { it.telpostid }
        val siteSpeciesBySite = siteSpecies.groupBy { it.telpostid }
        val protocolSpeciesByProtocol = protocolSpecies.groupBy { it.protocolid }

        val codesByCategory = codes
            .filter { it.category != null }
            .groupBy { it.category!! }

        DataSnapshot(
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
    }

    // =====================================================================
    //  Streaming JSON + BIN readers (binaries-first, json fallback)
    // =====================================================================

    private inline fun <reified T> readListStreamingOrBin(
        dir: DocumentFile,
        baseName: String,
        expectedKind: UShort
    ): List<T> {
        // 1) Probeer BIN (snel & compact)
        dir.findChildByName("$baseName.bin")?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                return when (decoded) {
                    is Decoded.AsList<T>   -> decoded.list
                    is Decoded.AsWrapped<T>-> decoded.wrapped.json
                    is Decoded.AsSingle<T> -> listOf(decoded.value)
                }
            }
        }
        // 2) JSON streaming
        dir.findChildByName("$baseName.json")?.let { jf ->
            context.contentResolver.openInputStream(jf.uri)?.use { input ->
                input.mark(1 shl 20)
                return runCatching {
                    json.decodeFromStream<WrappedJson<T>>(input).json
                }.getOrElse {
                    input.resetSafely()
                    runCatching {
                        json.decodeFromStream<List<T>>(input)
                    }.getOrElse { emptyList() }
                }
            }
        }
        return emptyList()
    }

    private inline fun <reified T> readOneStreamingOrBin(
        dir: DocumentFile,
        baseName: String,
        expectedKind: UShort
    ): T? {
        // 1) BIN
        dir.findChildByName("$baseName.bin")?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                return when (decoded) {
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json.firstOrNull()
                    is Decoded.AsList<T>    -> decoded.list.firstOrNull()
                    is Decoded.AsSingle<T>  -> decoded.value
                }
            }
        }
        // 2) JSON streaming
        dir.findChildByName("$baseName.json")?.let { jf ->
            context.contentResolver.openInputStream(jf.uri)?.use { input ->
                input.mark(1 shl 20)
                return runCatching {
                    json.decodeFromStream<WrappedJson<T>>(input).json.firstOrNull()
                }.getOrElse {
                    input.resetSafely()
                    runCatching {
                        json.decodeFromStream<T>(input)
                    }.getOrNull()
                }
            }
        }
        return null
    }

    private sealed class Decoded<out T> {
        data class AsList<T>(val list: List<T>) : Decoded<T>()
        data class AsWrapped<T>(val wrapped: WrappedJson<T>) : Decoded<T>()
        data class AsSingle<T>(val value: T) : Decoded<T>()
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

    // =====================================================================
    //  Utils
    // =====================================================================

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

    private fun InputStream.resetSafely() {
        try { if (markSupported()) reset() } catch (_: Throwable) {}
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
