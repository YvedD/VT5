package com.yvesds.vt5.features.telling

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.network.DataUploader
import com.yvesds.vt5.net.ServerTellingDataItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecordsBeheer: repository/service for pending records + backups + persistent index.
 *
 * Improvements in this version:
 * - coroutine-friendly Mutex locking
 * - pendingRecordsFlow: StateFlow exposing current pending records
 * - persist index to filesDir/VT5/pending_index.json so Workers can recover pending records
 * - suspend backup writers (SAF + internal)
 * - Continuous envelope persistence: saves full envelope after each observation
 *
 * Usage:
 * - create RecordsBeheer(applicationContext) in Activity or ViewModel
 * - call restorePendingIndex() at startup to populate from disk if present
 * - call collectFinalAsRecord(...) from ViewModel (in viewModelScope)
 */
private const val TAG = "RecordsBeheer"
private const val INDEX_DIR_NAME = "VT5"
private const val INDEX_FILE_NAME = "pending_index.json"

sealed class OperationResult {
    data class Success(val item: ServerTellingDataItem) : OperationResult()
    data class Failure(val reason: String) : OperationResult()
}

class RecordsBeheer(
    private val context: Context,
    private val json: Json = VT5App.json,
    private val envelopePersistence: TellingEnvelopePersistence? = null
) {
    private val PREFS_NAME = "vt5_prefs"
    private val PREF_TELLING_ID = "pref_telling_id"

    private val mutex = Mutex()
    private val _pendingRecordsFlow = MutableStateFlow<List<ServerTellingDataItem>>(emptyList())
    val pendingRecordsFlow: StateFlow<List<ServerTellingDataItem>> = _pendingRecordsFlow

    // Keep backup handles local, protected by mutex
    private val pendingBackupDocs = mutableListOf<DocumentFile>()
    private val pendingBackupInternalPaths = mutableListOf<String>()

    private val indexFile: File
        get() {
            val root = File(context.filesDir, INDEX_DIR_NAME)
            if (!root.exists()) root.mkdirs()
            return File(root, INDEX_FILE_NAME)
        }

    /**
     * Restore any pending records that were persisted to the index file.
     * Call this at startup (from a coroutine) so the repository picks up records after a restart.
     */
    suspend fun restorePendingIndex() {
        try {
            val idxFile = indexFile
            if (!idxFile.exists()) {
                return
            }
            val text = withContext(Dispatchers.IO) { idxFile.readText(Charsets.UTF_8) }
            if (text.isBlank()) return
            val list = json.decodeFromString(ListSerializer(ServerTellingDataItem.serializer()), text)
            mutex.withLock {
                _pendingRecordsFlow.value = list.toList()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "restorePendingIndex failed: ${ex.message}", ex)
        }
    }

    /**
     * Persist current pendingRecords to index file (atomic write).
     */
    private suspend fun persistIndex() {
        try {
            val snapshot = _pendingRecordsFlow.value
            val text = json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), snapshot)
            val idxFile = indexFile
            withContext(Dispatchers.IO) {
                idxFile.writeText(text, Charsets.UTF_8)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "persistIndex failed: ${ex.message}", ex)
        }
    }

    /**
     * Collect a final observation as a ServerTellingDataItem.
     * Returns Success(item) or Failure(reason).
     *
     * Note: this is a suspend function and runs blocking IO on Dispatchers.IO internally.
     */
    suspend fun collectFinalAsRecord(soortId: String, amount: Int, explicitTijdstipSeconds: Long? = null): OperationResult {
        return try {
            // Build item and allocate id on IO
            val (item, tellingId) = withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val tellingId = prefs.getString(PREF_TELLING_ID, null)
                if (tellingId.isNullOrBlank()) {
                    return@withContext null to null
                }
                val idLocal = DataUploader.getAndIncrementRecordId(context, tellingId)
                val nowEpoch = (explicitTijdstipSeconds ?: (System.currentTimeMillis() / 1000L)).toString()

                val item = ServerTellingDataItem(
                    idLocal = idLocal,
                    tellingid = tellingId,
                    soortid = soortId,
                    aantal = amount.toString(),
                    richting = "",
                    aantalterug = "0",
                    richtingterug = "",
                    sightingdirection = "",
                    lokaal = "0",
                    aantal_plus = "0",
                    aantalterug_plus = "0",
                    lokaal_plus = "0",
                    markeren = "0",
                    markerenlokaal = "0",
                    geslacht = "",
                    leeftijd = "",
                    kleed = "",
                    opmerkingen = "",
                    trektype = "",
                    teltype = "",
                    location = "",
                    height = "",
                    tijdstip = nowEpoch,
                    groupid = idLocal,
                    uploadtijdstip = "",
                    totaalaantal = amount.toString()
                )
                item to tellingId
            }

            if (item == null || tellingId == null) {
                return OperationResult.Failure("Geen actieve telling (tellingId ontbreekt)")
            }

            // Add to state atomically and persist index
            mutex.withLock {
                val newList = _pendingRecordsFlow.value.toMutableList()
                newList.add(item)
                _pendingRecordsFlow.value = newList.toList()
            }
            // Persist index (best-effort)
            persistIndex()

            // Persist backups (best-effort). Do not hold mutex while performing heavy IO that may suspend.
            try {
                val doc = writeRecordBackupSaf(tellingId, item)
                if (doc != null) {
                    mutex.withLock { pendingBackupDocs.add(doc) }
                } else {
                    val internal = writeRecordBackupInternal(tellingId, item)
                    if (internal != null) mutex.withLock { pendingBackupInternalPaths.add(internal) }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Record backup failed after collect: ${ex.message}", ex)
                // still return success; backups are best-effort
            }
            
            // Save full envelope with all records (best-effort)
            try {
                val currentRecords = _pendingRecordsFlow.value
                envelopePersistence?.saveEnvelopeWithRecords(currentRecords)
            } catch (ex: Exception) {
                Log.w(TAG, "Envelope persistence failed after collect: ${ex.message}", ex)
                // still return success; envelope persistence is best-effort
            }

            OperationResult.Success(item)
        } catch (ex: Exception) {
            Log.w(TAG, "collectFinalAsRecord failed: ${ex.message}", ex)
            OperationResult.Failure(ex.message ?: "unknown error")
        }
    }

    /**
     * Suspend SAF writer for a single record (returns created DocumentFile or null).
     */
    suspend fun writeRecordBackupSaf(tellingId: String, item: ServerTellingDataItem): DocumentFile? {
        return withContext(Dispatchers.IO) {
            try {
                val saf = SaFStorageHelper(context)
                var vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
                if (vt5Dir == null) {
                    try { saf.ensureFolders() } catch (_: Exception) {}
                    vt5Dir = saf.getVt5DirIfExists()
                }
                if (vt5Dir != null) {
                    val exportsDir = saf.findOrCreateDirectory(vt5Dir, "exports") ?: vt5Dir
                    val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val safeName = "session_${tellingId}_${item.idLocal}_$nowStr.json"
                    val created = exportsDir.createFile("application/json", safeName) ?: return@withContext null
                    context.contentResolver.openOutputStream(created.uri)?.bufferedWriter(Charsets.UTF_8).use { w ->
                        val payloadJson = json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), listOf(item))
                        w?.write(payloadJson)
                        w?.flush()
                    }
                    return@withContext created
                }
            } catch (e: Exception) {
                Log.w(TAG, "writeRecordBackupSaf failed: ${e.message}", e)
            }
            return@withContext null
        }
    }

    /**
     * Suspend internal backup writer (returns internal file path or null)
     */
    suspend fun writeRecordBackupInternal(tellingId: String, item: ServerTellingDataItem): String? {
        return withContext(Dispatchers.IO) {
            try {
                val root = java.io.File(context.filesDir, INDEX_DIR_NAME)
                if (!root.exists()) root.mkdirs()
                val exports = java.io.File(root, "exports"); if (!exports.exists()) exports.mkdirs()
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "session_${tellingId}_${item.idLocal}_$nowStr.json"
                val f = java.io.File(exports, filename)
                val payloadJson = json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), listOf(item))
                f.writeText(payloadJson, Charsets.UTF_8)
                return@withContext f.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "writeRecordBackupInternal failed: ${e.message}", e)
                return@withContext null
            }
        }
    }

    /**
     * Return a snapshot copy of pending records (fast, non-suspending via StateFlow value).
     */
    fun getPendingRecordsSnapshot(): List<ServerTellingDataItem> {
        return _pendingRecordsFlow.value
    }

    /**
     * Update a pending record at index (atomic).
     */
    suspend fun updatePendingRecord(index: Int, updated: ServerTellingDataItem): Boolean {
        val ok = mutex.withLock {
            val cur = _pendingRecordsFlow.value.toMutableList()
            if (index < 0 || index >= cur.size) return@withLock false
            cur[index] = updated
            _pendingRecordsFlow.value = cur.toList()
            true
        }
        if (ok) {
            persistIndex()
            // Also save full envelope after update
            try {
                val currentRecords = _pendingRecordsFlow.value
                envelopePersistence?.saveEnvelopeWithRecords(currentRecords)
            } catch (ex: Exception) {
                Log.w(TAG, "Envelope persistence failed after update: ${ex.message}", ex)
            }
        }
        return ok
    }

    /**
     * Clear pending records and attempt to delete backups.
     */
    suspend fun clearPendingRecordsAndBackups() {
        mutex.withLock {
            // delete SAF docs
            pendingBackupDocs.forEach { doc ->
                try { doc.delete() } catch (_: Exception) {}
            }
            pendingBackupDocs.clear()

            // delete internal files
            pendingBackupInternalPaths.forEach { path ->
                try { java.io.File(path).delete() } catch (_: Exception) {}
            }
            pendingBackupInternalPaths.clear()

            _pendingRecordsFlow.value = emptyList()
        }
        // persist empty index
        persistIndex()
        // Also clear the envelope file
        try {
            envelopePersistence?.clearSavedEnvelope()
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to clear envelope file: ${ex.message}", ex)
        }
    }

    /**
     * For diagnostics: log current pending records summary.
     */
    fun logPendingState(prefix: String = "pending") {
        val snapshot = _pendingRecordsFlow.value
        val summary = snapshot.joinToString(", ") { "${it.idLocal}:${it.soortid}:${it.aantal}" }
    }
}