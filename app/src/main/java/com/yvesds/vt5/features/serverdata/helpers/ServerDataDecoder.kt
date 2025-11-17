@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.serverdata.helpers

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.features.serverdata.model.WrappedJson
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * Helper for decoding ServerData files from binary (VT5Bin) or JSON formats.
 * 
 * Responsibilities:
 * - VT5Bin binary format parsing
 * - GZIP decompression
 * - JSON/CBOR deserialization
 * - Fallback handling (bin → json)
 */
class ServerDataDecoder(
    private val context: Context,
    private val json: Json = defaultJson,
    private val cbor: Cbor = defaultCbor
) {
    
    companion object {
        val defaultJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val defaultCbor = Cbor { ignoreUnknownKeys = true }
    }
    
    // Shared buffer for better memory usage
    private val headerBuffer = ByteArray(VT5Bin.HEADER_SIZE)
    
    sealed class Decoded<out T> {
        data class AsList<T>(val list: List<T>) : Decoded<T>()
        data class AsWrapped<T>(val wrapped: WrappedJson<T>) : Decoded<T>()
        data class AsSingle<T>(val value: T) : Decoded<T>()
    }
    
    /**
     * Decode a list from a binary file.
     */
    inline fun <reified T> decodeListFromBinary(
        file: DocumentFile,
        expectedKind: UShort
    ): List<T>? {
        val decoded = decodeBinary<T>(file, expectedKind) ?: return null
        return when (decoded) {
            is Decoded.AsList<T> -> decoded.list
            is Decoded.AsWrapped<T> -> decoded.wrapped.json
            is Decoded.AsSingle<T> -> listOf(decoded.value)
        }
    }
    
    /**
     * Decode a single item from a binary file.
     */
    inline fun <reified T> decodeOneFromBinary(
        file: DocumentFile,
        expectedKind: UShort
    ): T? {
        val decoded = decodeBinary<T>(file, expectedKind) ?: return null
        return when (decoded) {
            is Decoded.AsWrapped<T> -> decoded.wrapped.json.firstOrNull()
            is Decoded.AsList<T> -> decoded.list.firstOrNull()
            is Decoded.AsSingle<T> -> decoded.value
        }
    }
    
    /**
     * Decode from VT5Bin binary format.
     */
    inline fun <reified T> decodeBinary(
        file: DocumentFile,
        expectedKind: UShort
    ): Decoded<T>? {
        context.contentResolver.openInputStream(file.uri)?.use { raw ->
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
                    VT5Bin.Compression.GZIP -> GZIPInputStream(ByteArrayInputStream(payload)).use { 
                        it.readAllBytesCompat() 
                    }
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
    
    /**
     * Decode a list from JSON file.
     */
    inline fun <reified T> decodeListFromJson(file: DocumentFile): List<T>? {
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            val text = input.readBytes().decodeToString()
            return runCatching {
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
            }
        }
        return null
    }
    
    /**
     * Decode a single item from JSON file.
     */
    inline fun <reified T> decodeOneFromJson(file: DocumentFile): T? {
        context.contentResolver.openInputStream(file.uri)?.use { input ->
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
        return null
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

/* ================= I/O utilities ================= */

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
