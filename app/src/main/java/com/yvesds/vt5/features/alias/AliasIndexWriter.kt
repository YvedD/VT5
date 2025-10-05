/*
 * VT5 - AliasIndexWriter
 *
 * Doel:
 *  - Zoekt aliasmapping.csv in deze VOLGORDE:
 *      (1) SAF: Documents/VT5/assets/aliasmapping.csv    ← jouw toestelbestand
 *      (2) Internal files:  filesDir/assets/aliasmapping.csv (optioneel)
 *      (3) Packaged app assets: src/main/assets/aliasmapping.csv (fallback)
 *  - Precompute → schrijft naar filesDir/binaries:
 *      alias_index.json, alias_index.json.gz
 *      alias_index.cbor, alias_index.cbor.gz
 *  - Skip als CBOR.gz al bestaat (fast path).
 *  - Laden voor spraak (prefer CBOR.gz > JSON.gz > CBOR > JSON).
 */

package com.yvesds.vt5.features.alias

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object AliasIndexWriter {

    private const val CSV_NAME = "aliasmapping.csv"          // EXACTE NAAM
    private const val SAF_ASSETS_DIR = "assets"              // SAF submap
    private const val BIN_DIR = "binaries"
    private const val JSON_NAME = "alias_index.json"
    private const val JSON_GZ_NAME = "alias_index.json.gz"
    private const val CBOR_NAME = "alias_index.cbor"
    private const val CBOR_GZ_NAME = "alias_index.cbor.gz"

    // Eén gedeelde JSON (compact) voor performance/consistency.
    private val jsonCompact: Json = Json { encodeDefaults = true }

    /**
     * Zorgt dat de precomputed binaire aanwezig is.
     *
     * Zoekvolgorde CSV:
     *  (1) SAF: Documents/VT5/assets/aliasmapping.csv
     *  (2) Internal: filesDir/assets/aliasmapping.csv (als je dit ooit daar plaatst)
     *  (3) App assets: src/main/assets/aliasmapping.csv (fallback)
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun ensureComputed(
        context: Context,
        saf: SaFStorageHelper,
        q: Int = 3,
        minhashK: Int = 64
    ): Pair<String, String> = withContext(Dispatchers.Default) {

        val binDir = File(context.filesDir, BIN_DIR).apply { mkdirs() }
        val jsonGz = File(binDir, JSON_GZ_NAME)
        val cborGz = File(binDir, CBOR_GZ_NAME)

        // Fast path
        if (cborGz.exists()) {
            return@withContext jsonGz.absolutePath to cborGz.absolutePath
        }

        // 1) Probeer SAF: Documents/VT5/assets/aliasmapping.csv
        val csvBytesFromSaf: ByteArray? = runCatching {
            val vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
            val assetsDir = vt5Dir?.findFile(SAF_ASSETS_DIR)?.takeIf { it.isDirectory }
            val csvDoc = assetsDir?.findFile(CSV_NAME)?.takeIf { it.isFile }
            csvDoc?.uri?.let { uri ->
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }.getOrNull()

        // 2) Probeer interne filesDir/assets/aliasmapping.csv (optioneel pad)
        val csvBytesFromFiles: ByteArray? = runCatching {
            val localAssets = File(File(context.filesDir, "assets"), CSV_NAME)
            if (localAssets.exists() && localAssets.isFile) localAssets.readBytes() else null
        }.getOrNull()

        // 3) Fallback: packaged app assets (src/main/assets/aliasmapping.csv)
        val csvBytesFromAppAssets: ByteArray? = runCatching {
            context.assets.open(CSV_NAME).use { it.readBytes() }
        }.getOrNull()

        val csvBytes = csvBytesFromSaf ?: csvBytesFromFiles ?: csvBytesFromAppAssets
        require(!(csvBytes == null || csvBytes.isEmpty())) {
            """
            Kon '$CSV_NAME' niet vinden/lezen.
            Geprobeerd op:
             - SAF: Documents/VT5/$SAF_ASSETS_DIR/$CSV_NAME
             - Internal: filesDir/assets/$CSV_NAME
             - App assets: src/main/assets/$CSV_NAME
            """.trimIndent()
        }

        val csvText = csvBytes.toString(Charsets.UTF_8)

        // Precompute
        val index = PrecomputeAliasIndex.buildFromCsv(csvText, q, minhashK)

        // Serialiseer
        val jsonPrettyBytes = PrecomputeAliasIndex.toPrettyJson(index).toByteArray(Charsets.UTF_8)
        val cborBytes = PrecomputeAliasIndex.toCborBytes(index)

        // Schrijf on-compressed
        File(binDir, JSON_NAME).writeBytes(jsonPrettyBytes)
        File(binDir, CBOR_NAME).writeBytes(cborBytes)

        // Schrijf .gz varianten
        jsonGz.writeBytes(gzip(jsonPrettyBytes))
        cborGz.writeBytes(gzip(cborBytes))

        jsonGz.absolutePath to cborGz.absolutePath
    }

    /**
     * Laadt de AliasIndex uit binaries, voorkeur: CBOR.gz → JSON.gz → CBOR → JSON.
     * Draait off-main.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadIndexFromBinaries(context: Context): AliasIndex? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, BIN_DIR)
        val cborGz = File(dir, CBOR_GZ_NAME)
        val jsonGz = File(dir, JSON_GZ_NAME)
        val cbor = File(dir, CBOR_NAME)
        val json = File(dir, JSON_NAME)

        when {
            cborGz.exists() -> {
                runCatching {
                    val bytes = gunzip(cborGz.readBytes())
                    Cbor.decodeFromByteArray(AliasIndex.serializer(), bytes)
                }.getOrNull()
            }
            jsonGz.exists() -> {
                runCatching {
                    val bytes = gunzip(jsonGz.readBytes())
                    Json.decodeFromString(AliasIndex.serializer(), bytes.toString(Charsets.UTF_8))
                }.getOrNull()
            }
            cbor.exists() -> {
                runCatching {
                    val bytes = cbor.readBytes()
                    Cbor.decodeFromByteArray(AliasIndex.serializer(), bytes)
                }.getOrNull()
            }
            json.exists() -> {
                runCatching {
                    val text = json.readText(Charsets.UTF_8)
                    Json.decodeFromString(AliasIndex.serializer(), text)
                }.getOrNull()
            }
            else -> null
        }
    }

    // ---------- GZip helpers ----------

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { gz -> gz.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }
}
