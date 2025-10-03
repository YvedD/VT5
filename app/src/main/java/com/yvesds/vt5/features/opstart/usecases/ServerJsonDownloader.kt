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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/**
 * Download JSON + schrijf leesbaar (.json) én binair (.bin met MAGIC header + GZIP payload).
 */
object ServerJsonDownloader {

    // idem: niet-lazy zodat shutdown altijd kan
    private val client = OkHttpClient()

    private val jsonPretty: Json by lazy { Json { prettyPrint = true; prettyPrintIndent = "  " } }
    private val jsonLenient: Json by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

    suspend fun downloadAll(
        context: Context,
        serverdataDir: DocumentFile?,
        binariesDir: DocumentFile?,
        username: String,
        password: String,
        language: String = "dutch",
        versie: String = "1845"
    ): List<String> = withContext(Dispatchers.IO) {
        val msgs = mutableListOf<String>()
        val targets = listOf(
            "checkuser",
            "sites",
            "species",
            "site_species",
            "codes",
            "site_extra_vragen",
            "site_extra_data_vragen",
            "site_locations",
            "site_heights",
            "protocolinfo",
            "protocolspecies"
        )

        if (serverdataDir == null || !serverdataDir.isDirectory) {
            msgs += "❌ serverdata-map ontbreekt."
            return@withContext msgs
        }
        val binDir = (binariesDir
            ?: serverdataDir.parentFile?.findFile("binaries")?.takeIf { it.isDirectory }
            ?: serverdataDir.parentFile?.createDirectory("binaries"))

        for (name in targets) {
            val msg = runCatching {
                val bodyRaw = httpGetJson(
                    username = username,
                    password = password,
                    endpoint = name,
                    language = language,
                    versie = versie
                )

                val parsed: JsonElement = parseJsonOrThrow(bodyRaw)
                val pretty: String = jsonPretty.encodeToString(JsonElement.serializer(), parsed)

                val jsonFile = createOrReplaceFile(serverdataDir, "$name.json", "application/json")
                val jsonOk = jsonFile?.let { writeText(context.contentResolver, it.uri, pretty) } == true

                var binOk = false
                if (binDir != null) {
                    val binFile = createOrReplaceFile(binDir, "$name.bin", "application/octet-stream")
                    if (binFile != null) {
                        val jsonBytes = bodyRaw.toByteArray(Charsets.UTF_8)
                        val gzBytes = gzip(jsonBytes)
                        val header = makeMagicHeader(
                            magic = "VT5JSON",
                            version = 1,
                            origJson = jsonBytes,
                            compressedLen = gzBytes.size
                        )
                        binOk = writeMagicBin(context.contentResolver, binFile.uri, header, gzBytes)
                    }
                }

                if (jsonOk) "✔ $name — JSON: OK${if (binOk) ", BIN(.bin): OK" else ", BIN(.bin): ❌"}"
                else "❌ $name — JSON: ❌"
            }.getOrElse { e ->
                "❌ $name — ${e.message ?: e.toString()}"
            }
            msgs += msg
        }
        msgs
    }

    /* ---------------- Intern ---------------- */

    private fun httpGetJson(
        username: String,
        password: String,
        endpoint: String,
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
            .get()
            .build()

        client.newCall(req).execute().use { resp: Response ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.message}\n$bodyStr")
            return bodyStr
        }
    }

    private fun parseJsonOrThrow(raw: String): JsonElement =
        try { jsonLenient.parseToJsonElement(raw) }
        catch (e: Exception) { throw IllegalStateException("Ongeldige JSON respons") }

    private fun createOrReplaceFile(dir: DocumentFile, name: String, mime: String): DocumentFile? {
        dir.findFile(name)?.delete()
        return dir.createFile(mime, name)
    }

    private fun writeText(cr: ContentResolver, uri: Uri, text: String): Boolean =
        try {
            cr.openOutputStream(uri, "w")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (_: Throwable) { false }

    private fun writeMagicBin(cr: ContentResolver, uri: Uri, header: ByteBuffer, payload: ByteArray): Boolean =
        try {
            cr.openOutputStream(uri, "w")!!.use { out ->
                out.write(header.array(), 0, header.limit())
                out.write(payload)
                out.flush()
            }
            true
        } catch (_: Throwable) { false }

    private fun makeMagicHeader(
        magic: String,
        version: Int,
        origJson: ByteArray,
        compressedLen: Int,
        gzipFlag: Int = 1
    ): ByteBuffer {
        val MAGIC_LEN = 8
        val SECTION_COUNT = 1
        val headerSize = MAGIC_LEN + 4 + 8 + 8 + 4 + 4 + 4 + (8 + 8) // = 56
        val buf = ByteBuffer.allocate(headerSize).order(ByteOrder.BIG_ENDIAN)

        val magicBytes = magic.toByteArray(Charsets.US_ASCII)
        val fixed = ByteArray(MAGIC_LEN)
        System.arraycopy(magicBytes, 0, fixed, 0, minOf(magicBytes.size, MAGIC_LEN))
        buf.put(fixed)

        buf.putInt(version)
        buf.putLong(origJson.size.toLong())
        buf.putLong(System.currentTimeMillis())

        val crc = java.util.zip.CRC32().apply { update(origJson) }.value.toInt()
        buf.putInt(crc)

        buf.putInt(gzipFlag)
        buf.putInt(SECTION_COUNT)

        val offset0 = headerSize.toLong()
        buf.putLong(offset0)
        buf.putLong(compressedLen.toLong())

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
