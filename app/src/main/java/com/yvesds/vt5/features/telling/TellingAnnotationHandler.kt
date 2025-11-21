package com.yvesds.vt5.features.telling

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.net.ServerTellingDataItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * TellingAnnotationHandler: Manages annotation workflow for observations.
 * 
 * Responsibilities:
 * - Launch AnnotatieScherm for adding annotations
 * - Process annotation results from AnnotatieScherm
 * - Apply annotations to pending records
 * - Backup updated records
 */
class TellingAnnotationHandler(
    private val activity: AppCompatActivity,
    private val backupManager: TellingBackupManager,
    private val prefsName: String
) {
    companion object {
        private const val TAG = "TellingAnnotationHandler"
        private const val PREF_TELLING_ID = "pref_telling_id"
    }

    // Callback for annotation application
    var onAnnotationApplied: ((String) -> Unit)? = null
    var onGetFinalsList: (() -> List<TellingScherm.SpeechLogRow>)? = null
    var onGetPendingRecords: (() -> List<ServerTellingDataItem>)? = null
    var onUpdatePendingRecord: ((Int, ServerTellingDataItem) -> Unit)? = null

    // Activity result launcher
    private lateinit var annotationLauncher: ActivityResultLauncher<Intent>

    /**
     * Register the activity result launcher.
     * Must be called during Activity onCreate before super.onCreate().
     */
    fun registerLauncher() {
        annotationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleAnnotationResult(result.resultCode, result.data)
        }
    }

    /**
     * Launch AnnotatieScherm for a specific log row.
     */
    fun launchAnnotatieScherm(text: String, timestamp: Long, rowPosition: Int) {
        val intent = Intent(activity, AnnotatieScherm::class.java).apply {
            putExtra(AnnotatieScherm.EXTRA_TEXT, text)
            putExtra(AnnotatieScherm.EXTRA_TS, timestamp)
            putExtra("extra_row_pos", rowPosition)
        }
        annotationLauncher.launch(intent)
    }

    /**
     * Handle result from AnnotatieScherm.
     */
    private fun handleAnnotationResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return

        val annotationsJson = data.getStringExtra(AnnotatieScherm.EXTRA_ANNOTATIONS_JSON)
        val legacyText = data.getStringExtra(AnnotatieScherm.EXTRA_TEXT)
        val legacyTs = data.getLongExtra(AnnotatieScherm.EXTRA_TS, 0L)
        val rowPos = data.getIntExtra("extra_row_pos", -1)

        if (!annotationsJson.isNullOrBlank()) {
            try {
                applyAnnotationsToPendingRecord(annotationsJson, rowTs = legacyTs, rowPos = rowPos)
            } catch (ex: Exception) {
                Log.w(TAG, "applyAnnotationsToPendingRecord failed: ${ex.message}", ex)
            }
        } else {
            // Legacy fallback: if legacyText present, store as opmerkingen
            if (!legacyText.isNullOrBlank() && legacyTs > 0L) {
                try {
                    val singleMapJson = kotlinx.serialization.json.Json.encodeToString(
                        mapOf("opmerkingen" to legacyText)
                    )
                    applyAnnotationsToPendingRecord(singleMapJson, rowTs = legacyTs, rowPos = rowPos)
                } catch (ex: Exception) {
                    Log.w(TAG, "legacy apply failed: ${ex.message}", ex)
                }
            }
        }

        // UI feedback: summarize annotation
        runCatching {
            val summary = if (!annotationsJson.isNullOrBlank()) {
                val map = kotlinx.serialization.json.Json.decodeFromString<Map<String, String?>>(annotationsJson)
                map.entries.joinToString(", ") { (k, v) -> "$k=${v ?: ""}" }
            } else {
                legacyText ?: ""
            }
            if (summary.isNotBlank()) {
                onAnnotationApplied?.invoke("Annotatie toegepast: $summary")
            }
        }.onFailure { ex ->
            Log.w(TAG, "summarizing annotation failed: ${ex.message}", ex)
        }
    }

    /**
     * Apply annotations JSON to matching pending record.
     */
    private fun applyAnnotationsToPendingRecord(
        annotationsJson: String,
        rowTs: Long = 0L,
        rowPos: Int = -1
    ) {
        try {
            val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val map: Map<String, String?> = try {
                parser.decodeFromString(annotationsJson)
            } catch (ex: Exception) {
                Log.w(TAG, "failed to decode annotations JSON: ${ex.message}", ex)
                return
            }

            val pendingRecords = onGetPendingRecords?.invoke() ?: run {
                Log.w(TAG, "no pending records available")
                return
            }

            if (pendingRecords.isEmpty()) {
                Log.w(TAG, "no pendingRecords to apply annotations to")
                return
            }

            // Find matching record by position or timestamp
            var idx = -1
            if (rowPos >= 0) {
                val finalsList = onGetFinalsList?.invoke() ?: emptyList()
                val finalRowTs = finalsList.getOrNull(rowPos)?.ts
                if (finalRowTs != null) {
                    idx = pendingRecords.indexOfFirst { it.tijdstip == finalRowTs.toString() }
                }
            }

            // Fallback: try by explicit rowTs if provided
            if (idx == -1 && rowTs > 0L) {
                idx = pendingRecords.indexOfFirst { it.tijdstip == rowTs.toString() }
            }

            if (idx == -1) {
                Log.w(TAG, "no matching pending record found (rowPos=$rowPos, rowTs=$rowTs)")
                return
            }

            if (idx < 0 || idx >= pendingRecords.size) {
                Log.w(TAG, "computed index out of bounds: $idx")
                return
            }

            val old = pendingRecords[idx]

            // Create updated copy with annotations
            val updated = old.copy(
                leeftijd = map["leeftijd"] ?: old.leeftijd,
                geslacht = map["geslacht"] ?: old.geslacht,
                kleed = map["kleed"] ?: old.kleed,
                location = map["location"] ?: old.location,
                height = map["height"] ?: old.height,
                lokaal = map["lokaal"] ?: old.lokaal,
                markeren = map["markeren"] ?: old.markeren,
                markerenlokaal = map["markerenlokaal"] ?: old.markerenlokaal,
                aantal = map["aantal"] ?: old.aantal,
                aantalterug = map["aantalterug"] ?: old.aantalterug,
                opmerkingen = map["opmerkingen"] ?: map["remarks"] ?: old.opmerkingen
            )

            // Update in-memory pending record via callback
            onUpdatePendingRecord?.invoke(idx, updated)

            Log.d(TAG, "Applied annotations to pendingRecords[$idx]: $map")

            // Write backup
            try {
                val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val tellingId = prefs.getString(PREF_TELLING_ID, null)
                if (!tellingId.isNullOrBlank()) {
                    backupManager.writeRecordBackupSaf(tellingId, updated)
                }
            } catch (ex: Exception) {
                Log.w(TAG, "backup write failed: ${ex.message}", ex)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "applyAnnotationsToPendingRecord failed: ${ex.message}", ex)
        }
    }
}
