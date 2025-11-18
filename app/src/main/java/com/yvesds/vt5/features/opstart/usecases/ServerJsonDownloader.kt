@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.opstart.usecases

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/**
 * Download JSON + schrijf leesbaar (.json) én binair (.bin met VT5BIN10 header + GZIP payload).
 *
 * BELANGRIJK: 'checkuser' wordt HIER NIET meer opgehaald.
 * -> checkuser wordt nu opgeslagen door TrektellenAuth.testLogin(...)
 * 
 * Phase 2 update: Supports alias_index download with binary format (VT5BIN10).
 * - alias_index uses Kind.ALIAS_INDEX (100u) for binary format validation
 * - Binary format provides faster loading than JSON/CBOR
 * - Consistent with other serverdata (species, sites, etc.)
 */
object ServerJsonDownloader {

    private val client = OkHttpClient()

    private val jsonPretty by lazy { Json { prettyPrint = true; prettyPrintIndent = "  " } }
    private val jsonLenient by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

    suspend fun downloadAll(
        context: Context,
        serverdataDir: DocumentFile?,
        binariesDir: DocumentFile?,
        username: String,
        password: String,
        language: String = "dutch",
        versie: String = "1845",
        includeAliasIndex: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        val msgs = mutableListOf<String>()

        // 'checkuser' is hier bewust NIET aanwezig; die wordt via de login-test gezet
        val targets = mutableListOf(
            "sites",
            "species",
            "site_species",
            "codes",
            "site_locations",
            "site_heights",
            "protocolinfo",
            "protocolspecies"
        )
        
        // Add alias_index if requested (Phase 2: Binary Format Rollout)
        if (includeAliasIndex) {
            targets.add("alias_index")
        }

        if (serverdataDir == null || !serverdataDir.isDirectory) {
            msgs += "❌ serverdata-map ontbreekt."
            return@withContext msgs
        }
        val binDir = (binariesDir
            ?: serverdataDir.parentFile?.findFile("binaries")?.takeIf { it.isDirectory }
            ?: serverdataDir.parentFile?.createDirectory("binaries"))

        for (name in targets) {
            val msg = runCatching {
                val bodyRaw = httpGetJsonBasicAuth(
                    endpoint = name,
                    username = username,
                    password = password,
                    language = language,
                    versie = versie
                )

                // JSON parse → pretty string (leesbaar)
                val parsed: JsonElement = parseJsonOrThrow(bodyRaw)
                val pretty: String = jsonPretty.encodeToString(JsonElement.serializer(), parsed)

                // 1) .json altijd overschrijven
                val jsonFile = createOrReplaceFile(serverdataDir, "$name.json", "application/json")
                val jsonOk = jsonFile?.let { writeText(context.contentResolver, it.uri, pretty) } == true

                // 2) .bin (VT5BIN10, JSON + GZIP payload) — optioneel
                var binOk = false
                if (binDir != null) {
                    val binFile = createOrReplaceFile(binDir, "$name.bin", "application/octet-stream")
                    if (binFile != null) {
                        val jsonBytes = bodyRaw.toByteArray(Charsets.UTF_8)
                        val gzBytes = gzip(jsonBytes)
                        val header = makeVt5BinHeader(
                            datasetKind = datasetKindFor(name),
                            codec = 0u,           // JSON
                            compression = 1u,     // gzip
                            payloadLen = gzBytes.size.toULong(),
                            uncompressedLen = jsonBytes.size.toULong(),
                            recordCount = 0xFFFF_FFFFu // unknown/hint
                        )
                        binOk = writeBin(context.contentResolver, binFile.uri, header, gzBytes)
                    }
                }

                if (jsonOk) "✔ $name — JSON: OK${if (binOk) ", BIN: OK" else ", BIN: ❌"}"
                else "❌ $name — JSON: ❌"
            }.getOrElse { e ->
                "❌ $name — ${e.message ?: e.toString()}"
            }
            msgs += msg
        }
        ServerDataCache.invalidate()
        msgs
    }

    /* ---------------- HTTP (altijd Basic Auth) ---------------- */

    private fun httpGetJsonBasicAuth(
        endpoint: String,
        username: String,
        password: String,
        language: String,
        versie: String
    ): String {
        val url: HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("trektellen.nl")
            .addEncodedPathSegments("api/$endpoint")
            .addQueryParameter("language", language)
            .addQueryParameter("versie", versie)
            .build()

        val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

        val req: Request = Request.Builder()
            .url(url)
            .header("Authorization", "Basic $token")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "VT5/1.0 (Android)")
            .get()
            .build()

        client.newCall(req).execute().use { resp: Response ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${resp.message}\n$bodyStr")
            }
            return bodyStr
        }
    }

    /* ---------------- JSON helpers ---------------- */

    private fun parseJsonOrThrow(raw: String): JsonElement =
        try { jsonLenient.parseToJsonElement(raw) }
        catch (_: Exception) { throw IllegalStateException("Ongeldige JSON respons") }

    /* ---------------- SAF helpers ---------------- */

    private fun createOrReplaceFile(dir: DocumentFile, name: String, mime: String): DocumentFile? {
        dir.findFile(name)?.delete()
        return dir.createFile(mime, name)
    }

    private fun writeText(cr: ContentResolver, uri: Uri, text: String): Boolean =
        try {
            cr.openOutputStream(uri, "w")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (_: Throwable) { false }

    private fun writeBin(cr: ContentResolver, uri: Uri, header: ByteBuffer, payload: ByteArray): Boolean =
        try {
            cr.openOutputStream(uri, "w")!!.use { out ->
                out.write(header.array(), 0, header.limit())
                out.write(payload)
                out.flush()
            }
            true
        } catch (_: Throwable) { false }

    /* ---------------- VT5BIN10 writer ---------------- */

    private fun datasetKindFor(name: String): UShort = when (name.lowercase()) {
        "species"          -> 1u
        "sites"            -> 2u
        "site_locations"   -> 3u
        "site_heights"     -> 4u
        "site_species"     -> 5u
        "codes"            -> 6u
        "protocolinfo"     -> 7u
        "protocolspecies"  -> 8u
        "alias_index"      -> 100u
        else               -> 0u
    }

    /**
     * VT5BIN10 header (40 bytes, Little-Endian) volgens SPEC.
     */
    private fun makeVt5BinHeader(
        datasetKind: UShort,
        codec: UByte,
        compression: UByte,
        payloadLen: ULong,
        uncompressedLen: ULong,
        recordCount: UInt
    ): ByteBuffer {
        val HEADER_SIZE = 40
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // MAGIC
        buf.put(byteArrayOf(0x56,0x54,0x35,0x42,0x49,0x4E,0x31,0x30)) // "VT5BIN10"
        // headerVersion
        buf.putShort(0x0001)
        // datasetKind
        buf.putShort(datasetKind.toShort())
        // codec, compression
        buf.put(codec.toByte())
        buf.put(compression.toByte())
        // reserved16
        buf.putShort(0)
        // payloadLen, uncompressedLen
        buf.putLong(payloadLen.toLong())
        buf.putLong(uncompressedLen.toLong())
        // recordCount
        buf.putInt(recordCount.toInt())

        // CRC32 over [0x00..0x23]
        val tmp = buf.array().copyOfRange(0, 0x24)
        val crc = CRC32().apply { update(tmp) }.value.toUInt()

        // headerCrc32
        buf.putInt(crc.toInt())

        buf.flip()
        return buf
    }

    private fun gzip(src: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(src) }
        return baos.toByteArray()
    }

    /** Netjes resources vrijgeven. */
    fun shutdown() {
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            client.cache?.close()
        } catch (_: Throwable) {
            // best-effort
        }
    }
}

/* -------------------- DocumentFile helper -------------------- */
private fun DocumentFile.findFile(name: String): DocumentFile? =
    listFiles().firstOrNull { it.name == name }
