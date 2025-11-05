@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.yvesds.vt5.features.network

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.net.ServerTellingDataItem
import com.yvesds.vt5.net.TrektellenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DataUploader:
 * - uploadSingleObservation: post een ServerTellingDataItem naar /api/data_save/{onlineId} via TrektellenApi
 * - schrijft de volledige serverresponse naar SAF map: Documents/VT5/exports (timestamped .txt)
 * - bij failure: enqueue in SharedPreferences queue (pending_uploads)
 * - retryPendingUploads() beschikbaar om de queue opnieuw te verwerken
 * - getAndIncrementRecordId() voor per-telling _id beheer
 *
 * Gebruikt jouw SaFStorageHelper (getVt5DirIfExists / ensureFolders / findOrCreateDirectory).
 */
object DataUploader {
    private const val TAG = "DataUploader"
    private const val PREFS = "vt5_upload_prefs"
    private const val KEY_PENDING = "pending_uploads" // JSON array of objects { onlineId, payload, serverMessage, ts }

    private val json: Json by lazy { VT5App.json }

    /**
     * Upload een enkele ServerTellingDataItem.
     * Returned Triple(ok:Boolean, serverResponse:String, responseFilePath:String?)
     * responseFilePath is een human-readable path (Documents/VT5/exports/...) of "internal:/abs/path" wanneer SAF fallback gebruikt wordt.
     */
    suspend fun uploadSingleObservation(
        context: Context,
        baseUrl: String,
        onlineId: String,
        username: String,
        password: String,
        item: ServerTellingDataItem
    ): Triple<Boolean, String, String?> = withContext(Dispatchers.IO) {
        try {
            val (ok, resp) = TrektellenApi.postDataSaveSingle(baseUrl, onlineId, username, password, item)

            // Always attempt to write response & payload to SAF (or fallback)
            val writtenPath = try {
                writeResponseToSaf(context, onlineId, item, resp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing response to SAF: ${e.message}", e)
                null
            }

            if (ok) {
                Log.i(TAG, "uploadSingleObservation succeeded for $onlineId : ${item.soortid}")
                return@withContext Triple(true, resp, writtenPath)
            } else {
                Log.w(TAG, "uploadSingleObservation failed for $onlineId : resp=$resp")
                enqueuePending(context, onlineId, item, resp)
                return@withContext Triple(false, resp, writtenPath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadSingleObservation exception: ${e.message}", e)
            // enqueue for retry
            try {
                enqueuePending(context, onlineId, item, e.message ?: "exception")
            } catch (ex: Exception) {
                Log.e(TAG, "enqueuePending failed in exception handler: ${ex.message}", ex)
            }
            val writtenPath = try {
                writeResponseToSaf(context, onlineId, item, e.message ?: "exception")
            } catch (_: Exception) { null }
            return@withContext Triple(false, e.message ?: "exception", writtenPath)
        }
    }

    /** Enqueue payload (store payload JSON + last server message) for retry. */
    private fun enqueuePending(context: Context, onlineId: String, item: ServerTellingDataItem, serverMessage: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PENDING, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val obj = JSONObject()
            val payloadJsonArray = json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), listOf(item))
            obj.put("onlineId", onlineId)
            obj.put("payload", payloadJsonArray)
            obj.put("serverMessage", serverMessage)
            obj.put("ts", System.currentTimeMillis())
            arr.put(obj)
            prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
            Log.i(TAG, "Enqueued pending upload for onlineId=$onlineId")
        } catch (e: Exception) {
            Log.e(TAG, "enqueuePending failed: ${e.message}", e)
        }
    }

    /**
     * Retry pending uploads (process queue FIFO). Call this at app start or on connectivity restore.
     * Successful uploads are removed from queue.
     */
    suspend fun retryPendingUploads(context: Context, baseUrl: String, username: String, password: String) = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PENDING, "[]") ?: "[]"
            val arr = JSONArray(raw)
            if (arr.length() == 0) return@withContext

            val remaining = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val onlineId = o.optString("onlineId")
                val payload = o.optString("payload")
                if (onlineId.isBlank() || payload.isBlank()) continue

                try {
                    val list = json.decodeFromString(ListSerializer(ServerTellingDataItem.serializer()), payload)
                    val item = list.firstOrNull()
                    if (item == null) {
                        Log.w(TAG, "retryPendingUploads: invalid payload for onlineId=$onlineId")
                        remaining.put(o)
                        continue
                    }

                    val (ok, resp) = TrektellenApi.postDataSaveSingle(baseUrl, onlineId, username, password, item)
                    // write response file for audit
                    try { writeResponseToSaf(context, onlineId, item, resp) } catch (_: Exception) {}

                    if (!ok) {
                        Log.w(TAG, "retryPendingUploads: server returned failure for $onlineId — keeping in queue")
                        remaining.put(o)
                    } else {
                        Log.i(TAG, "retryPendingUploads: success for $onlineId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "retryPendingUploads item failed: ${e.message} — keep in queue")
                    remaining.put(o)
                }
            }
            prefs.edit().putString(KEY_PENDING, remaining.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "retryPendingUploads overall failed: ${e.message}", e)
        }
    }

    /**
     * Schrijf response (en geposte payload) naar SAF Documents/VT5/exports als tekstbestand.
     * Retourneer een eenvoudige bestands-URI string (human readable) of null.
     *
     * Werkwijze:
     * 1) Probeer bestaande VT5 dir te vinden (getVt5DirIfExists())
     * 2) Als niet aanwezig: probeer ensureFolders() aan te roepen (maakt VT5 + subfolders)
     * 3) Gebruik findOrCreateDirectory(parent, "exports") om exports dir te verkrijgen
     * 4) Maak file via exportsDir.createFile(...) en schrijf payload + response
     * 5) Indien SAF volledig niet mogelijk: fallback naar internal filesDir/VT5/exports en return internal:path
     */
    private fun writeResponseToSaf(context: Context, onlineId: String, item: ServerTellingDataItem, responseText: String): String? {
        try {
            val saf = SaFStorageHelper(context)

            // 1) get existing VT5 dir or try to ensure folders
            var vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
            if (vt5Dir == null) {
                // Try to create the tree (idempotent)
                try {
                    saf.ensureFolders()
                } catch (ex: Exception) {
                    Log.w(TAG, "SaF ensureFolders() failed: ${ex.message}")
                }
                vt5Dir = saf.getVt5DirIfExists()
            }

            // 2) If we have vt5Dir, find or create exports
            if (vt5Dir != null) {
                val exportsDir = saf.findOrCreateDirectory(vt5Dir, "exports") ?: vt5Dir
                // Compose filename
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safeName = "upload_resp_${onlineId}_$nowStr.txt"
                // If file exists, remove or reuse; prefer to create new unique file
                val created = exportsDir.createFile("text/plain", safeName)
                if (created != null) {
                    context.contentResolver.openOutputStream(created.uri)?.use { out ->
                        OutputStreamWriter(out, StandardCharsets.UTF_8).use { w ->
                            w.write("=== Payload ===\n")
                            val payloadJson = json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), listOf(item))
                            w.write(payloadJson)
                            w.write("\n\n=== Server response ===\n")
                            w.write(responseText)
                            w.flush()
                        }
                    }
                    val human = "Documents/VT5/exports/$safeName"
                    Log.i(TAG, "Wrote response file to SAF: $human")
                    return human
                } else {
                    Log.w(TAG, "createFile returned null for $safeName")
                }
            }

            // 3) Fallback to internal filesDir/VT5/exports
            try {
                val fallbackRoot = java.io.File(context.filesDir, "VT5")
                if (!fallbackRoot.exists()) fallbackRoot.mkdirs()
                val fallbackExports = java.io.File(fallbackRoot, "exports")
                if (!fallbackExports.exists()) fallbackExports.mkdirs()
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "upload_resp_${onlineId}_$nowStr.txt"
                val target = java.io.File(fallbackExports, fileName)
                target.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write("=== Payload ===\n")
                    val payloadJson = json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), listOf(item))
                    w.write(payloadJson)
                    w.write("\n\n=== Server response ===\n")
                    w.write(responseText)
                    w.flush()
                }
                val internal = "internal:${target.absolutePath}"
                Log.i(TAG, "Wrote response file to internal fallback: $internal")
                return internal
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback writeResponseToSaf failed: ${ex.message}", ex)
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeResponseToSaf overall failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Helper: per-telling incremental _id generator (returns string id)
     * - stores next id under prefs key: "pref_next_record_id_<tellingId>"
     */
    fun getAndIncrementRecordId(context: Context, tellingId: String): String {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = "pref_next_record_id_$tellingId"
            val current = prefs.getLong(key, 1L)
            val next = current
            prefs.edit().putLong(key, current + 1L).apply()
            return next.toString()
        } catch (e: Exception) {
            Log.w(TAG, "getAndIncrementRecordId failed: ${e.message}", e)
            // fallback to timestamp-based id
            return (System.currentTimeMillis() / 1000L).toString()
        }
    }
}