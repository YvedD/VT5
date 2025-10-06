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
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * === VT5 – ServerDataRepository ===
 *
 * Laadstrategie:
 *  1) Probeer .bin (VT5BIN10) in SAF/Documenten/VT5/serverdata/<naam>.bin
 *  2) Val terug op .json (zelfde map), verwacht { "json": [ ... ] }
 *
 * Bin-payload: CBOR (codec=1) of JSON (codec=0), evt. GZIP gecomprimeerd.
 * Header volgens de SPEC (40 bytes, Little-Endian):
 * MAGIC(8) + headerVersion(2) + datasetKind(2) + codec(1) + compression(1)
 * + reserved16(2) + payloadLen(8) + uncompressedLen(8) + recordCount(4) + headerCrc32(4)
 */
class ServerDataRepository(
    private val context: Context,
    private val json: Json = defaultJson,
    private val cbor: Cbor = defaultCbor
) {

    private val snapshotState = MutableStateFlow(DataSnapshot())
    val snapshot: StateFlow<DataSnapshot> = snapshotState

    /**
     * Leest alle serverdata uit SAF (Documents/VT5/serverdata),
     * bouwt mappings en publiceert één immutable DataSnapshot.
     */
    suspend fun loadAllFromSaf(): DataSnapshot = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext DataSnapshot()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext DataSnapshot()

        // --- 1) Inlezen (BIN → JSON fallback) ---
        val userObj = readOne<CheckUserItem>(
            serverdata,
            baseName = "checkuser",
            expectedKind = VT5Bin.Kind.CHECK_USER
        )

        val speciesList = readList<SpeciesItem>(
            serverdata,
            baseName = "species",
            expectedKind = VT5Bin.Kind.SPECIES
        )

        val protocolInfo = readList<ProtocolInfoItem>(
            serverdata,
            baseName = "protocolinfo",
            expectedKind = VT5Bin.Kind.PROTOCOL_INFO
        )

        val protocolSpecies = readList<ProtocolSpeciesItem>(
            serverdata,
            baseName = "protocolspecies",
            expectedKind = VT5Bin.Kind.PROTOCOL_SPECIES
        )

        val sites = readList<SiteItem>(
            serverdata,
            baseName = "sites",
            expectedKind = VT5Bin.Kind.SITES
        )

        val siteLocations = readList<SiteValueItem>(
            serverdata,
            baseName = "site_locations",
            expectedKind = VT5Bin.Kind.SITE_LOCATIONS
        )

        val siteHeights = readList<SiteValueItem>(
            serverdata,
            baseName = "site_heights",
            expectedKind = VT5Bin.Kind.SITE_HEIGHTS
        )

        val siteSpecies = readList<SiteSpeciesItem>(
            serverdata,
            baseName = "site_species",
            expectedKind = VT5Bin.Kind.SITE_SPECIES
        )

        // codes is generiek; kan leeg zijn in vroege samples
        val codes = runCatching {
            readList<CodeItem>(
                serverdata,
                baseName = "codes",
                expectedKind = VT5Bin.Kind.CODES
            )
        }.getOrElse { emptyList() }

        // --- 2) Mappings bouwen ---
        val speciesById = speciesList.associateBy { it.soortid }
        val speciesByCanonical = speciesList.associate { sp ->
            normalizeCanonical(sp.soortnaam) to sp.soortid
        }

        val sitesById = sites.associateBy { it.telpostid }
        val siteLocationsBySite = siteLocations.groupBy { it.telpostid }
        val siteHeightsBySite = siteHeights.groupBy { it.telpostid }
        val siteSpeciesBySite = siteSpecies.groupBy { it.telpostid }

        val protocolSpeciesByProtocol = protocolSpecies.groupBy { it.protocolid }
        val codesByCategory = codes.groupBy { it.category ?: "uncategorized" }

        // assignedSites: voorlopig leeg; vullen zodra backend veld beschikbaar is
        val assignedSites = emptyList<String>()

        val snap = DataSnapshot(
            currentUser = userObj,
            speciesById = speciesById,
            speciesByCanonical = speciesByCanonical,
            sitesById = sitesById,
            assignedSites = assignedSites,
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

    /* ================================================================
     * =============== Binaries-first readers (SAF) ===================
     * ================================================================ */

    /** Resultaat van een succesvolle VT5 decode, typesafe. */
    private sealed class Decoded<out T> {
        data class AsList<T>(val list: List<T>) : Decoded<T>()
        data class AsWrapped<T>(val wrapped: WrappedJson<T>) : Decoded<T>()
        data class AsSingle<T>(val value: T) : Decoded<T>()
    }

    /**
     * Probeert eerst <baseName>.bin met VT5 header; val terug op <baseName>.json.
     * Normaliseert payload naar een **lijst** T.
     */
    private inline fun <reified T> readList(
        dir: DocumentFile,
        baseName: String,
        expectedKind: UShort
    ): List<T> {
        // 1) .bin
        dir.findChildByName("$baseName.bin")?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                return when (decoded) {
                    is Decoded.AsList<T> -> decoded.list
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json
                    is Decoded.AsSingle<T> -> listOf(decoded.value)
                }
            }
        }
        // 2) .json
        dir.findChildByName("$baseName.json")?.let { jf ->
            context.contentResolver.openInputStream(jf.uri)?.use { input ->
                val bytes = input.readBytes()
                // Probeer WrappedJson<List<T>>
                runCatching {
                    json.decodeFromString(
                        WrappedJson.serializer(json.serializersModule.serializer<T>()),
                        bytes.decodeToString()
                    ).json
                }.getOrElse {
                    // Probeer List<T>
                    runCatching {
                        json.decodeFromString(
                            json.serializersModule.serializer<List<T>>(),
                            bytes.decodeToString()
                        )
                    }.getOrElse {
                        // Probeer enkelvoudig T
                        listOf(
                            json.decodeFromString(
                                json.serializersModule.serializer<T>(),
                                bytes.decodeToString()
                            )
                        )
                    }
                }.let { return it }
            }
        }
        return emptyList()
    }

    /**
     * Als één object verwacht wordt:
     * - Bin: T of WrappedJson<T> of List<T> (neemt eerste)
     * - Json: WrappedJson<T> of T
     */
    private inline fun <reified T> readOne(
        dir: DocumentFile,
        baseName: String,
        expectedKind: UShort
    ): T? {
        // .bin
        dir.findChildByName("$baseName.bin")?.let { bin ->
            vt5ReadDecoded<T>(bin, expectedKind)?.let { decoded ->
                return when (decoded) {
                    is Decoded.AsWrapped<T> -> decoded.wrapped.json.firstOrNull()
                    is Decoded.AsList<T> -> decoded.list.firstOrNull()
                    is Decoded.AsSingle<T> -> decoded.value
                }
            }
        }
        // .json
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

    /**
     * Leest en decodeert een VT5 .bin bestand (Little-Endian) volgens de SPEC.
     * @return Decoded<T> — typesafe variant (list/wrapped/single)
     */
    private inline fun <reified T> vt5ReadDecoded(
        binFile: DocumentFile,
        expectedKind: UShort
    ): Decoded<T>? {
        val cr = context.contentResolver
        cr.openInputStream(binFile.uri)?.use { raw ->
            val bis = BufferedInputStream(raw)

            // 1) lees header (40 bytes)
            val headerBytes = ByteArray(VT5Bin.HEADER_SIZE)
            if (bis.read(headerBytes) != VT5Bin.HEADER_SIZE) return null

            // 2) parse + valideer header
            val hdr = VT5Header.fromBytes(headerBytes) ?: return null
            if (!hdr.magic.contentEquals(VT5Bin.MAGIC)) return null
            if (hdr.headerVersion.toInt() < VT5Bin.HEADER_VERSION.toInt()) return null
            if (hdr.datasetKind != expectedKind) return null
            if (hdr.codec != VT5Bin.Codec.JSON && hdr.codec != VT5Bin.Codec.CBOR) return null
            if (hdr.compression != VT5Bin.Compression.NONE && hdr.compression != VT5Bin.Compression.GZIP) return null

            // 3) lees payload
            val pl = hdr.payloadLen.toLong()
            if (pl < 0) return null
            val payload = ByteArray(pl.toInt())
            val read = bis.readNBytesCompat(payload)
            if (read != pl.toInt()) return null

            // 4) decompress (optioneel)
            val dataBytes = when (hdr.compression) {
                VT5Bin.Compression.GZIP -> GZIPInputStream(ByteArrayInputStream(payload)).use { it.readAllBytesCompat() }
                VT5Bin.Compression.NONE -> payload
                else -> return null
            }

            // 5) deserialiseer volgens codec
            return when (hdr.codec) {
                VT5Bin.Codec.CBOR -> {
                    // Probeer in volgorde: WrappedJson<T>, List<T>, T
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

    /* ========================= Utils ========================= */

    private fun normalizeCanonical(input: String): String {
        // Snelle diacritics-strip; goed genoeg voor NL/EN.
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
        val defaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val defaultCbor = Cbor {
            ignoreUnknownKeys = true
        }
    }
    suspend fun loadForMetadata(): DataSnapshot = withContext(Dispatchers.IO) {
        val saf = com.yvesds.vt5.core.opslag.SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext DataSnapshot()
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext DataSnapshot()

        val userObj = readOne<CheckUserItem>(serverdata, baseName = "checkuser", expectedKind = VT5Bin.Kind.CHECK_USER)
        val sites = readList<SiteItem>(serverdata, baseName = "sites", expectedKind = VT5Bin.Kind.SITES)
        val codes = runCatching {
            readList<CodeItem>(serverdata, baseName = "codes", expectedKind = VT5Bin.Kind.CODES)
        }.getOrElse { emptyList() }

        val sitesById = sites.associateBy { it.telpostid }
        val codesByCategory = codes.groupBy { it.category ?: "uncategorized" }

        val snap = DataSnapshot(
            currentUser = userObj,
            speciesById = emptyMap(),
            speciesByCanonical = emptyMap(),
            sitesById = sitesById,
            assignedSites = emptyList(),
            siteLocationsBySite = emptyMap(),
            siteHeightsBySite = emptyMap(),
            siteSpeciesBySite = emptyMap(),
            protocolsInfo = emptyList(),
            protocolSpeciesByProtocol = emptyMap(),
            codesByCategory = codesByCategory
        )
        snapshotState.value = snap
        snap
    }

}

/* ================================================================
 * ================= VT5 BIN Header & Constants ===================
 * ================================================================ */

private object VT5Bin {
    // ASCII: VT5BIN10
    val MAGIC: ByteArray = byteArrayOf(
        0x56, 0x54, 0x35, 0x42, 0x49, 0x4E, 0x31, 0x30
    )

    const val HEADER_SIZE: Int = 40 // bytes
    val HEADER_VERSION: UShort = 0x0001u

    object Codec {
        const val JSON: UByte = 0u
        const val CBOR: UByte = 1u
        // 2u = Proto (gereserveerd)
    }

    object Compression {
        const val NONE: UByte = 0u
        const val GZIP: UByte = 1u
    }

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
    val magic: ByteArray,          // 8
    val headerVersion: UShort,     // 2
    val datasetKind: UShort,       // 2
    val codec: UByte,              // 1
    val compression: UByte,        // 1
    val reserved16: UShort,        // 2
    val payloadLen: ULong,         // 8
    val uncompressedLen: ULong,    // 8
    val recordCount: UInt,         // 4
    val headerCrc32: UInt          // 4 (CRC32 van bytes [0x00..0x23])
) {
    companion object {
        private const val HEADER_LEN = VT5Bin.HEADER_SIZE

        fun fromBytes(bytes: ByteArray): VT5Header? {
            if (bytes.size != HEADER_LEN) return null

            // CRC32 over [0x00..0x23] (36 bytes), exclusief de laatste 4 bytes
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
                magic = magic,
                headerVersion = headerVersion,
                datasetKind = datasetKind,
                codec = codec,
                compression = compression,
                reserved16 = reserved16,
                payloadLen = payloadLen,
                uncompressedLen = uncompressedLen,
                recordCount = recordCount,
                headerCrc32 = headerCrc32
            )
        }
    }
}



/* -------------------- DocumentFile helper -------------------- */
private fun DocumentFile.findChildByName(name: String): DocumentFile? {
    return listFiles().firstOrNull { it.name == name }
}
