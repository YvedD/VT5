// VT5 - AliasIndexWriter (fixed EnsureResult returns and cleaned up)
//
// - Fixes argument order / types when returning EnsureResult.
// - Keeps detailed preflight, SAF copy, internal writes and export log behavior.
// - Writes export log both to internal filesDir/exports and (if SAF root set) Documents/VT5/exports.

package com.yvesds.vt5.features.alias

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

object AliasIndexWriter {
    private const val TAG = "AliasIndexWriter"

    private const val CSV_NAME = "aliasmapping.csv"
    private const val SAF_ASSETS_DIR = "assets"
    private const val SAF_SERVERDATA_DIR = "serverdata"
    private const val SAF_EXPORTS_DIR = "exports"
    private const val BIN_DIR = "binaries"
    private const val JSON_NAME = "alias_index.json"
    private const val JSON_GZ_NAME = "alias_index.json.gz"
    private const val CBOR_NAME = "alias_index.cbor"
    private const val CBOR_GZ_NAME = "alias_index.cbor.gz"

    private val jsonPretty = Json { prettyPrint = true }

    data class EnsureResult(
        val internalJsonGzPath: String?,
        val internalCborGzPath: String?,
        val internalWritten: Boolean,
        val safJsonWritten: Boolean,
        val safCborWritten: Boolean,
        val exportLogInternalPath: String?,
        val exportLogSafPath: String?,
        val aliasCount: Int,
        val messages: List<String>
    )

    /**
     * Inspect SAF and packaged/internal assets for presence of source files.
     */
    fun preflightCheck(context: Context, saf: SaFStorageHelper): List<String> {
        val out = mutableListOf<String>()
        val vt5Dir = saf.getVt5DirIfExists()
        if (vt5Dir == null) {
            out += "SAF VT5 root: NOT SET"
        } else {
            out += "SAF VT5 root: set"
            val assets = vt5Dir.findFile(SAF_ASSETS_DIR)
            if (assets != null && assets.isDirectory) {
                out += " - SAF assets exists"
                val c = assets.findFile(CSV_NAME)
                out += if (c != null && c.isFile) "   - $CSV_NAME found in SAF assets" else "   - $CSV_NAME NOT found in SAF assets"
            } else out += " - SAF assets missing"

            val serverdata = vt5Dir.findFile(SAF_SERVERDATA_DIR)
            if (serverdata != null && serverdata.isDirectory) {
                val list = serverdata.listFiles().mapNotNull { it.name }.sorted()
                out += " - SAF serverdata dir present, contains: ${if (list.isEmpty()) "(empty)" else list.joinToString(", ")}"
            } else out += " - SAF serverdata missing"
        }

        val internalAsset = runCatching { context.assets.open(CSV_NAME).use { true } }.getOrNull() ?: false
        out += "Packaged app asset $CSV_NAME present: $internalAsset"

        val internalFilesCsv = File(File(context.filesDir, "assets"), CSV_NAME)
        out += "Internal filesDir asset $CSV_NAME present: ${internalFilesCsv.exists()} (path: ${internalFilesCsv.absolutePath})"

        return out
    }

    /**
     * Build the alias index, write internal copies and attempt writing SAF copies.
     * Also writes a human-readable export log to internal filesDir/exports and to Documents/VT5/exports (SAF) if available.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun ensureComputedDetailed(
        context: Context,
        saf: SaFStorageHelper,
        q: Int = 3,
        minhashK: Int = 64
    ): EnsureResult {
        val messages = mutableListOf<String>()
        val binDir = File(context.filesDir, BIN_DIR).apply { mkdirs() }

        // Try SAF CSV first
        val csvBytesFromSaf: ByteArray? = runCatching {
            val vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
            val assetsDir = vt5Dir?.findFile(SAF_ASSETS_DIR)?.takeIf { it.isDirectory }
            val csvDoc = assetsDir?.findFile(CSV_NAME)?.takeIf { it.isFile }
            csvDoc?.uri?.let { uri ->
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }.getOrNull()

        if (csvBytesFromSaf != null) messages += "Found $CSV_NAME in SAF assets" else messages += "$CSV_NAME not found in SAF assets"

        // Try internal filesDir assets
        val csvBytesFromFiles: ByteArray? = runCatching {
            val localAssets = File(File(context.filesDir, "assets"), CSV_NAME)
            if (localAssets.exists() && localAssets.isFile) localAssets.readBytes() else null
        }.getOrNull()
        if (csvBytesFromFiles != null) messages += "Found $CSV_NAME in internal filesDir/assets" else messages += "$CSV_NAME not found in internal filesDir/assets"

        // Try packaged app assets
        val csvBytesFromAppAssets: ByteArray? = runCatching {
            context.assets.open(CSV_NAME).use { it.readBytes() }
        }.getOrNull()
        if (csvBytesFromAppAssets != null) messages += "Found packaged app asset $CSV_NAME" else messages += "$CSV_NAME not found in packaged app assets"

        val csvBytes = csvBytesFromSaf ?: csvBytesFromFiles ?: csvBytesFromAppAssets
        if (csvBytes == null || csvBytes.isEmpty()) {
            messages += "ERROR: No source CSV available. Aborting precompute."
            return EnsureResult(null, null, false, false, false, null, null, 0, messages)
        }

        // Inform about serverdata presence in SAF (informational)
        val vt5Dir = saf.getVt5DirIfExists()
        if (vt5Dir != null) {
            val srv = vt5Dir.findFile(SAF_SERVERDATA_DIR)
            if (srv != null && srv.isDirectory) {
                val entries = srv.listFiles().mapNotNull { it.name }.sorted()
                messages += "SAF serverdata contains: ${if (entries.isEmpty()) "(empty)" else entries.joinToString(", ")}"
            } else messages += "SAF serverdata missing"
        } else messages += "SAF root not set; cannot inspect SAF serverdata"

        // Build index
        val csvText = csvBytes.toString(Charsets.UTF_8)
        val index = try {
            PrecomputeAliasIndex.buildFromCsv(csvText, q, minhashK)
        } catch (ex: Exception) {
            messages += "ERROR building index: ${ex.message}"
            return EnsureResult(null, null, false, false, false, null, null, 0, messages)
        }
        messages += "Built index in memory with ${index.json.size} alias records"

        // Serialize
        val aliasesJsonText = PrecomputeAliasIndex.toPrettyJson(index)
        val aliasesJsonBytes = aliasesJsonText.toByteArray(Charsets.UTF_8)
        val aliasesCborBytes = PrecomputeAliasIndex.toCborBytes(index)

        // Write internal gz files
        val internalJsonGzFile = File(binDir, JSON_GZ_NAME)
        val internalCborGzFile = File(binDir, CBOR_GZ_NAME)
        try {
            atomicWrite(internalJsonGzFile, gzip(aliasesJsonBytes))
            atomicWrite(internalCborGzFile, gzip(aliasesCborBytes))
            messages += "Wrote internal gz files: ${internalJsonGzFile.absolutePath}, ${internalCborGzFile.absolutePath}"
        } catch (ex: Exception) {
            messages += "ERROR writing internal gz files: ${ex.message}"
            return EnsureResult(null, null, false, false, false, null, null, index.json.size, messages)
        }

        // SAF writes
        var safJsonOk = false
        var safCborOk = false
        try {
            safJsonOk = writeBytesToSaF(context, saf, SAF_ASSETS_DIR, JSON_GZ_NAME, gzip(aliasesJsonBytes), "application/gzip")
            safCborOk = writeBytesToSaF(context, saf, BIN_DIR, CBOR_GZ_NAME, gzip(aliasesCborBytes), "application/gzip")
            messages += "Attempted SAF writes: json=$safJsonOk, cbor=$safCborOk"
        } catch (ex: Exception) {
            messages += "Exception while copying to SAF (non-fatal): ${ex.message}"
        }

        // Write export log (both internal exports folder and SAF exports folder)
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val exportFilename = "alias_precompute_log_$timestamp.txt"
        val logText = buildString {
            append("Alias precompute log - $timestamp (UTC)\n\n")
            append("Summary:\n")
            append(" - aliases_count: ${index.json.size}\n")
            append(" - internalJsonGz: ${internalJsonGzFile.absolutePath}\n")
            append(" - internalCborGz: ${internalCborGzFile.absolutePath}\n")
            append("\nDetails:\n")
            messages.forEach { append(" - $it\n") }
        }

        // internal exports
        val internalExportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val internalExportFile = File(internalExportsDir, exportFilename)
        var internalExportPath: String? = null
        try {
            internalExportFile.writeText(logText, Charsets.UTF_8)
            internalExportPath = internalExportFile.absolutePath
            messages += "Wrote internal export log: $internalExportPath"
        } catch (ex: Exception) {
            messages += "Failed to write internal export log: ${ex.message}"
        }

        // SAF exports
        var safExportPath: String? = null
        try {
            val wrote = writeStringToSaF(context, saf, SAF_EXPORTS_DIR, exportFilename, logText, "text/plain")
            if (wrote) {
                safExportPath = "Documents/VT5/$SAF_EXPORTS_DIR/$exportFilename"
                messages += "Wrote SAF export log: $safExportPath"
            } else {
                messages += "Did not write SAF export log (SAF not set or write failed)"
            }
        } catch (ex: Exception) {
            messages += "Exception writing export log to SAF: ${ex.message}"
        }

        // Build manifest internal
        val manifestObj = buildJsonObject {
            put("version", JsonPrimitive("v1"))
            put("generated_at", JsonPrimitive(Instant.now().toString()))
            put("sources", buildJsonObject {
                put("aliasmapping.csv", buildJsonObject {
                    put("sha256", JsonPrimitive(sha256OfBytes(csvBytes)))
                    put("size", JsonPrimitive(csvBytes.size.toString()))
                })
                put("species.json", buildJsonObject { put("sha256", JsonPrimitive("0")); put("size", JsonPrimitive("0")) })
                put("site_species.json", buildJsonObject { put("sha256", JsonPrimitive("0")); put("size", JsonPrimitive("0")) })
            })
            put("indexes", buildJsonObject {
                put("aliases_flat", buildJsonObject {
                    put("path", JsonPrimitive(internalJsonGzFile.absolutePath))
                    put("sha256", JsonPrimitive(sha256OfFileBytes(internalJsonGzFile)))
                    put("size", JsonPrimitive(internalJsonGzFile.length().toString()))
                    put("aliases_count", JsonPrimitive(index.json.size.toString()))
                })
                put("species_master", buildJsonObject {
                    put("path", JsonPrimitive(internalCborGzFile.absolutePath))
                    put("sha256", JsonPrimitive(sha256OfFileBytes(internalCborGzFile)))
                    put("size", JsonPrimitive(internalCborGzFile.length().toString()))
                    put("species_count", JsonPrimitive(index.json.map { it.speciesid }.distinct().size.toString()))
                })
            })
            put("counts", buildJsonObject {
                put("aliases", JsonPrimitive(index.json.size))
                put("species", JsonPrimitive(index.json.map { it.speciesid }.distinct().size))
            })
            put("notes", JsonPrimitive("Generated on-device"))
        }
        atomicWrite(File(binDir, "manifest.json"), jsonPretty.encodeToString(JsonObject.serializer(), manifestObj).toByteArray(Charsets.UTF_8))

        return EnsureResult(
            internalJsonGzPath = internalJsonGzFile.absolutePath,
            internalCborGzPath = internalCborGzFile.absolutePath,
            internalWritten = true,
            safJsonWritten = safJsonOk,
            safCborWritten = safCborOk,
            exportLogInternalPath = internalExportPath,
            exportLogSafPath = safExportPath,
            aliasCount = index.json.size,
            messages = messages
        )
    }

    // Helpers -----------------------------------------------------

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (file.exists()) {
            val bak = File(file.parentFile, file.name + ".bak")
            try { file.renameTo(bak) } catch (_: Throwable) { /* ignore */ }
        }
        tmp.renameTo(file)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { gz -> gz.write(data) }
        return bos.toByteArray()
    }

    private fun writeBytesToSaF(context: Context, saf: SaFStorageHelper, subDirName: String, filename: String, bytes: ByteArray, mimeType: String = "application/octet-stream"): Boolean {
        val vt5Dir = saf.getVt5DirIfExists() ?: run {
            Log.w(TAG, "No SAF VT5 root set - skipping SAF write for $filename")
            return false
        }

        val subdir = vt5Dir.findFile(subDirName)?.takeIf { it.isDirectory } ?: run {
            val created = vt5Dir.createDirectory(subDirName)
            if (created == null) {
                Log.w(TAG, "Could not create SAF subdir $subDirName")
                return false
            }
            created
        }

        subdir.findFile(filename)?.delete()
        val created = subdir.createFile(mimeType, filename) ?: run {
            Log.w(TAG, "Could not create SAF file $filename in $subDirName")
            return false
        }

        return try {
            context.contentResolver.openOutputStream(created.uri)?.use { os ->
                os.write(bytes)
                os.flush()
            }
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Error writing $filename to SAF: ${ex.message}", ex)
            try { created.delete() } catch (_: Throwable) { /* ignore */ }
            false
        }
    }

    private fun writeStringToSaF(context: Context, saf: SaFStorageHelper, subDirName: String, filename: String, content: String, mimeType: String = "text/plain"): Boolean {
        return writeBytesToSaF(context, saf, subDirName, filename, content.toByteArray(Charsets.UTF_8), mimeType)
    }

    private fun sha256OfFileBytes(file: File): String {
        return runCatching {
            sha256OfBytes(file.readBytes())
        }.getOrElse { "0" }
    }

    private fun sha256OfBytes(bytes: ByteArray): String {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(bytes)
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrElse { "0" }
    }
}