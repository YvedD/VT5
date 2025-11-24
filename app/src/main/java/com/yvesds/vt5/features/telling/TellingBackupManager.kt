package com.yvesds.vt5.features.telling

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.net.ServerTellingDataItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TellingBackupManager: Handles backup operations for counting records.
 * 
 * Responsibilities:
 * - Writing record backups to SAF (Storage Access Framework)
 * - Writing record backups to internal storage (fallback)
 * - Writing envelope JSON files
 * - Writing audit/response files
 */
class TellingBackupManager(
    private val context: Context,
    private val safHelper: SaFStorageHelper
) {
    companion object {
        private const val TAG = "TellingBackupManager"
        private const val VT5_DIR = "VT5"
        private const val EXPORTS_DIR = "exports"
    }

    /**
     * Write a single record backup via SAF into Documents/VT5/exports.
     * Returns the created DocumentFile or null if failed.
     */
    fun writeRecordBackupSaf(tellingId: String, item: ServerTellingDataItem): DocumentFile? {
        try {
            var vt5Dir: DocumentFile? = safHelper.getVt5DirIfExists()
            if (vt5Dir == null) {
                try { safHelper.ensureFolders() } catch (_: Exception) {}
                vt5Dir = safHelper.getVt5DirIfExists()
            }
            if (vt5Dir == null) {
                Log.w(TAG, "VT5 root folder not available via SAF")
                return null
            }

            var exportsDir = vt5Dir.findFile(EXPORTS_DIR)
            if (exportsDir == null || !exportsDir.isDirectory) {
                exportsDir = vt5Dir.createDirectory(EXPORTS_DIR)
            }
            if (exportsDir == null) {
                Log.w(TAG, "Could not create/find exports dir in SAF")
                return null
            }

            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val filename = "${nowStr}_rec_${item.idLocal}.txt"
            
            val doc = exportsDir.createFile("text/plain", filename)
            if (doc == null) {
                Log.w(TAG, "Could not create record backup file via SAF: $filename")
                return null
            }

            val content = buildRecordBackupContent(tellingId, item)
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }

            return doc
        } catch (e: Exception) {
            Log.w(TAG, "writeRecordBackupSaf failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Write a single record backup to internal storage as fallback.
     * Returns the absolute path or null if failed.
     */
    fun writeRecordBackupInternal(tellingId: String, item: ServerTellingDataItem): String? {
        try {
            val root = File(context.filesDir, "$VT5_DIR/$EXPORTS_DIR")
            if (!root.exists()) {
                root.mkdirs()
            }

            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val filename = "${nowStr}_rec_${item.idLocal}.txt"
            val file = File(root, filename)

            val content = buildRecordBackupContent(tellingId, item)
            file.writeText(content, Charsets.UTF_8)

            return file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "writeRecordBackupInternal failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Write pretty envelope JSON to SAF as "<timestamp>_count_<onlineid>.json".
     * Returns "saf:<uri>" or null if failed.
     */
    fun writePrettyEnvelopeToSaf(onlineId: String, prettyJson: String): String? {
        try {
            var vt5Dir: DocumentFile? = safHelper.getVt5DirIfExists()
            if (vt5Dir == null) {
                try { safHelper.ensureFolders() } catch (_: Exception) {}
                vt5Dir = safHelper.getVt5DirIfExists()
            }
            if (vt5Dir == null) {
                Log.w(TAG, "VT5 root folder not available via SAF")
                return null
            }

            var exportsDir = vt5Dir.findFile(EXPORTS_DIR)
            if (exportsDir == null || !exportsDir.isDirectory) {
                exportsDir = vt5Dir.createDirectory(EXPORTS_DIR)
            }
            if (exportsDir == null) {
                Log.w(TAG, "Could not create/find exports dir in SAF")
                return null
            }

            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${nowStr}_count_$onlineId.json"
            
            val doc = exportsDir.createFile("application/json", filename)
            if (doc == null) {
                Log.w(TAG, "Could not create envelope file via SAF: $filename")
                return null
            }

            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(prettyJson.toByteArray(Charsets.UTF_8))
            }

            return "saf:${doc.uri}"
        } catch (e: Exception) {
            Log.w(TAG, "writePrettyEnvelopeToSaf failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Write pretty envelope JSON to internal storage as fallback.
     * Returns "internal:<path>" or null if failed.
     */
    fun writePrettyEnvelopeInternal(onlineId: String, prettyJson: String): String? {
        try {
            val root = File(context.filesDir, "$VT5_DIR/$EXPORTS_DIR")
            if (!root.exists()) {
                root.mkdirs()
            }

            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${nowStr}_count_$onlineId.json"
            val file = File(root, filename)

            file.writeText(prettyJson, Charsets.UTF_8)

            return "internal:${file.absolutePath}"
        } catch (e: Exception) {
            Log.w(TAG, "writePrettyEnvelopeInternal failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Write envelope response (audit) to SAF.
     * Returns path string or null if failed.
     */
    fun writeEnvelopeResponseToSaf(
        tellingId: String,
        envelopeJson: String,
        responseText: String
    ): String? {
        try {
            var vt5Dir: DocumentFile? = safHelper.getVt5DirIfExists()
            if (vt5Dir == null) {
                try { safHelper.ensureFolders() } catch (_: Exception) {}
                vt5Dir = safHelper.getVt5DirIfExists()
            }
            if (vt5Dir == null) {
                Log.w(TAG, "VT5 root folder not available via SAF")
                return null
            }

            var exportsDir = vt5Dir.findFile(EXPORTS_DIR)
            if (exportsDir == null || !exportsDir.isDirectory) {
                exportsDir = vt5Dir.createDirectory(EXPORTS_DIR)
            }
            if (exportsDir == null) {
                Log.w(TAG, "Could not create/find exports dir in SAF")
                return null
            }

            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${nowStr}_audit_${tellingId}.txt"
            
            val doc = exportsDir.createFile("text/plain", filename)
            if (doc == null) {
                Log.w(TAG, "Could not create audit file via SAF: $filename")
                return null
            }

            val content = buildAuditContent(envelopeJson, responseText)
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }

            return "saf:${doc.uri}"
        } catch (e: Exception) {
            Log.w(TAG, "writeEnvelopeResponseToSaf failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Write envelope response (audit) to internal storage as fallback.
     * Returns path string or null if failed.
     */
    fun writeEnvelopeResponseInternal(
        tellingId: String,
        envelopeJson: String,
        responseText: String
    ): String? {
        try {
            val root = File(context.filesDir, "$VT5_DIR/$EXPORTS_DIR")
            if (!root.exists()) {
                root.mkdirs()
            }

            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${nowStr}_audit_${tellingId}.txt"
            val file = File(root, filename)

            val content = buildAuditContent(envelopeJson, responseText)
            file.writeText(content, Charsets.UTF_8)

            return "internal:${file.absolutePath}"
        } catch (e: Exception) {
            Log.w(TAG, "writeEnvelopeResponseInternal failed: ${e.message}", e)
            return null
        }
    }

    // Private helpers

    private fun buildRecordBackupContent(tellingId: String, item: ServerTellingDataItem): String {
        return """
            |Record Backup
            |TellingId: $tellingId
            |IdLocal: ${item.idLocal}
            |SoortId: ${item.soortid}
            |Aantal: ${item.aantal}
            |Timestamp: ${item.tijdstip}
            |GroupId: ${item.groupid}
            |---
            |Full Item: $item
        """.trimMargin()
    }

    private fun buildAuditContent(envelopeJson: String, responseText: String): String {
        return """
            |=== Envelope JSON ===
            |$envelopeJson
            |
            |=== Server Response ===
            |$responseText
        """.trimMargin()
    }
}
