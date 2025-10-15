@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.serverdata.model

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

class ServerDataRepository(
    private val context: Context,
    private val json: Json = defaultJson,
    private val cbor: Cbor = defaultCbor
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val snapshotState = MutableStateFlow(DataSnapshot())
    val snapshot: StateFlow<DataSnapshot> = snapshotState

    // ================== PUBLIC – gericht/fast loaders ==================

    /**
     * Laad enkel SITES in één beweging (bin → json fallback), en retourneer Map<telpostid, SiteItem>.
     * Resultaat is off-main.
     */
    suspend fun loadSitesOnly(): Map<String, SiteItem> = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext emptyMap()
        val serverdata = findServerDataDir(vt5Root) ?: return@withContext emptyMap()

        val sites = readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES)
        sites.associateBy { it.telpostid }
    }

    /**
     * Single-pass load van CODES, groepeer in-memory op category (of fallback).
     * Returned ook lastModified zodat VT5App een RAM-cache kan invalidaten bij wijziging.
     */
    suspend fun loadCodesAllOnceFast(): Pair<Map<String, List<CodeItem>>, Long> = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext (emptyMap<String, List<CodeItem>>() to -1L)
        val serverdata = findServerDataDir(vt5Root) ?: return@withContext (emptyMap<String, List<CodeItem>>() to -1L)

        val (codesFile, lm) = findChildByNameCachedWithLastModified(serverdata, "codes.bin")
            ?: findChildByNameCachedWithLastModified(serverdata, "codes.json")
            ?: return@withContext (emptyMap<String, List<CodeItem>>() to -1L)

        val items: List<CodeItem> = when (codesFile.name) {
            "codes.bin" -> {
                // We weten niet zeker of het JSON- of CBOR-coded bin is; vt5ReadDecoded regelt beide
                vt5ReadDecoded<CodeItem>(codesFile, VT5Bin.Kind.CODES)?.let { decoded ->
                    when (decoded) {
                        is Decoded.AsList -> decoded.list
                        is Decoded.AsWrapped -> decoded.wrapped.json
                        is Decoded.AsSingle -> listOf(decoded.value)
                    }
                } ?: emptyList()
            }
            "codes.json" -> {
                context.contentResolver.openInputStream(codesFile.uri)?.use { input ->
                    val text = input.readBytes().decodeToString()
                    runCatching {
                        json.decodeFromString(
                            WrappedJson.serializer(json.serializersModule.serializer<CodeItem>()),
                            text
                        ).json
                    }.getOrElse {
                        runCatching {
                            json.decodeFromString(
                                json.serializersModule.serializer<List<CodeItem>>(),
                                text
                            )
                        }.getOrElse {
                            listOf(
                                json.decodeFromString(
                                    json.serializersModule.serializer<CodeItem>(),
                                    text
                                )
                            )
                        }
                    }
                } ?: emptyList()
            }
            else -> emptyList()
        }

        // Groepering in-memory: category is de “veld”-categorie uit codes.json
        val grouped = items.groupBy { it.category ?: (it.key?.substringBefore('_') ?: "uncategorized") }
        (grouped to lm)
    }

    /**
     * Volledige snapshot loader (alle tabellen) – alleen gebruiken als je écht alles nodig hebt.
     * (We houden ‘m aan boord, maar in de UI flows gebruiken we de gericht/fast varianten.)
     */
    suspend fun loadAllFromSaf(): DataSnapshot = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext DataSnapshot()
        val serverdata = findServerDataDir(vt5Root) ?: return@withContext DataSnapshot()

        val userObj = readOne<CheckUserItem>(serverdata, "checkuser", VT5Bin.Kind.CHECK_USER)

        val speciesList = readList<SpeciesItem>(serverdata, "species", VT5Bin.Kind.SPECIES)
        val protocolInfo = readList<ProtocolInfoItem>(serverdata, "protocolinfo", VT5Bin.Kind.PROTOCOL_INFO)
        val protocolSpecies = readList<ProtocolSpeciesItem>(serverdata, "protocolspecies", VT5Bin.Kind.PROTOCOL_SPECIES)
        val sites = readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES)
        val siteLocations = readList<SiteValueItem>(serverdata, "site_locations", VT5Bin.Kind.SITE_LOCATIONS)
        val siteHeights = readList<SiteValueItem>(serverdata, "site_heights", VT5Bin.Kind.SITE_HEIGHTS)
        val siteSpecies = readList<SiteSpeciesItem>(serverdata, "site_species", VT5Bin.Kind.SITE_SPECIES)
        val codes = readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES)

        val speciesById = speciesList.associateBy { it.soortid }
        val speciesByCanonical = speciesList.associate { sp ->
            normalizeCanonical(sp.soortnaam) to sp.soortid
        }

        val sitesById = sites.associateBy { it.telpostid }
        val siteLocationsBySite = siteLocations.groupBy { it.telpostid }
        val siteHeightsBySite = siteHeights.groupBy { it.telpostid }
        val siteSpeciesBySite = siteSpecies.groupBy { it.telpostid }
        val protocolSpeciesByProtocol = protocolSpecies.groupBy { it.protocolid }

        val codesByCategory = codes.groupBy { it.category ?: (it.key?.substringBefore('_') ?: "uncategorized") }

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

    // ================== Internals ==================

    private fun findServerDataDir(vt5Root: DocumentFile): DocumentFile? {
        // cached lookup
        val cached = getCachedUri(KEY_SERVERDATA_URI)?.let { uri ->
            DocumentFile.fromTreeUri(context, uri)
        }?.takeIf { it != null && it.exists() && it.isDirectory && it.name == "serverdata" }
        if (cached != null) return cached

        val dir = vt5Root.listFiles().firstOrNull { it.isDirectory && it.name == "serverdata" }
        if (dir != null) cacheUri(KEY_SERVERDATA_URI, dir.uri)
        return dir
    }

    private fun findChildByNameCachedWithLastModified(dir: DocumentFile, name: String): Pair<DocumentFile, Long>? {
        // 1) probeer cached uri
        val cached = getCachedUri("uri_$name")?.let { DocumentFile.fromSingleUri(context, it) }
        if (cached != null && cached.exists() && cached.name == name) {
            val lm = cached.lastModified()
            return cached to lm
        }

        // 2) fallback: scan directory (duur), daarna cachen
        val found = dir.listFiles().firstOrNull { it.name == name } ?: return null
        cacheUri("uri_$name", found.uri)
        return found to found.lastModified()
    }

    private fun getCachedUri(key: String): Uri? =
        prefs.getString(key, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun cacheUri(key: String, uri: Uri) {
        prefs.edit().putString(key, uri.toString()).apply()
    }

    // ================== Generieke readers ==================

    private sealed class Decoded<out T> {
        data class AsList<T>(val list: List<T>) : Decoded<T>()
        data class AsWrapped<T>(val wrapped: WrappedJson<T>) : Decoded<T>()
        data class AsSingle<T>(val value: T) : Decoded<T>()
    }

    private inline fun <reified T> readList(dir: DocumentFile, baseName: String, expectedKind: UShort): List<T> {
        findChildByNameCachedWithLastModified(dir, "$baseName.bin")?.first?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                return when (decoded) {
                    is Decoded.AsList<T> -> decoded.list
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json
                    is Decoded.AsSingle<T> -> listOf(decoded.value)
                }
            }
        }
        findChildByNameCachedWithLastModified(dir, "$baseName.json")?.first?.let { jf ->
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
        findChildByNameCachedWithLastModified(dir, "$baseName.bin")?.first?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                return when (decoded) {
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json.firstOrNull()
                    is Decoded.AsList<T> -> decoded.list.firstOrNull()
                    is Decoded.AsSingle<T> -> decoded.value
                }
            }
        }
        findChildByNameCachedWithLastModified(dir, "$baseName.json")?.first?.let { jf ->
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

    // ================== Utils ==================

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
        private const val PREFS_NAME = "vt5_repo_cache"
        private const val KEY_SERVERDATA_URI = "serverdata_uri"

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
