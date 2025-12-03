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
        
        // Direction constants based on seasonal patterns
        private const val RICHTING_ZW = "w"  // West/Southwest (seasonal direction)
        private const val RICHTING_NO = "o"  // East/Northeast (counter-seasonal direction)
        
        // Regex to extract trailing number from text like "Buizerd 5"
        // Group 1: species name (everything before the number)
        // Group 2: count (the trailing number)
        private val RE_TRAILING_NUMBER = Regex("""^(.+?)\s+(\d+)\s*$""")
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
     * Prefills count fields with existing record values if a matching pending record is found.
     * Falls back to parsing the count from the text if no record is found.
     */
    fun launchAnnotatieScherm(text: String, timestamp: Long, rowPosition: Int) {
        val intent = Intent(activity, AnnotatieScherm::class.java).apply {
            putExtra(AnnotatieScherm.EXTRA_TEXT, text)
            putExtra(AnnotatieScherm.EXTRA_TS, timestamp)
            putExtra("extra_row_pos", rowPosition)
            
            // Find matching pending record to prefill count fields
            val pendingRecords = onGetPendingRecords?.invoke() ?: emptyList()
            val finalsList = onGetFinalsList?.invoke() ?: emptyList()
            
            // Find matching record by position
            val finalRowTs = finalsList.getOrNull(rowPosition)?.ts
            val matchingRecord = if (finalRowTs != null) {
                pendingRecords.firstOrNull { it.tijdstip == finalRowTs.toString() }
            } else {
                // Fallback: try by explicit timestamp
                pendingRecords.firstOrNull { it.tijdstip == timestamp.toString() }
            }
            
            // Prefill count values - either from record or parse from text
            if (matchingRecord != null) {
                // Use record values
                putExtra(AnnotatieScherm.EXTRA_RECORD_AANTAL, matchingRecord.aantal)
                putExtra(AnnotatieScherm.EXTRA_RECORD_AANTALTERUG, matchingRecord.aantalterug)
                putExtra(AnnotatieScherm.EXTRA_LOKAAL, matchingRecord.lokaal)
            } else {
                // Fallback: parse count from text (e.g., "Buizerd 5" -> 5)
                val countFromText = parseCountFromText(text)
                if (countFromText > 0) {
                    putExtra(AnnotatieScherm.EXTRA_RECORD_AANTAL, countFromText.toString())
                }
            }
        }
        annotationLauncher.launch(intent)
    }
    
    /**
     * Parse count from text like "Buizerd 5" -> 5
     */
    private fun parseCountFromText(text: String): Int {
        val match = RE_TRAILING_NUMBER.find(text.trim())
        return match?.groups?.get(2)?.value?.toIntOrNull() ?: 0
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
            } else {
                Log.w(TAG, "!!! CRITICAL: rowPos is -1, cannot match record by position !!!")
            }

            // Fallback: try by explicit rowTs if provided
            if (idx == -1 && rowTs > 0L) {
                idx = pendingRecords.indexOfFirst { it.tijdstip == rowTs.toString() }
                if (idx >= 0) {
                }
            }

            if (idx == -1) {
                Log.e(TAG, "!!! CRITICAL ERROR: no matching pending record found !!!")
                Log.e(TAG, "rowPos=$rowPos, rowTs=$rowTs")
                Log.e(TAG, "pendingRecords.size=${pendingRecords.size}")
                Log.e(TAG, "This means annotations will NOT be applied to any record!")
                return
            }

            if (idx < 0 || idx >= pendingRecords.size) {
                Log.w(TAG, "computed index out of bounds: $idx")
                return
            }

            val old = pendingRecords[idx]

            // DEBUG: Log annotation extraction from map
            
            // Update annotation fields from the map
            val newLeeftijd = map["leeftijd"] ?: old.leeftijd
            val newGeslacht = map["geslacht"] ?: old.geslacht
            val newKleed = map["kleed"] ?: old.kleed
            val newLocation = map["location"] ?: old.location
            val newHeight = map["height"] ?: old.height
            
            // DEBUG: Log kleed value specifically
            val newLokaal = map["lokaal"] ?: old.lokaal
            val newLokaalPlus = map["lokaal_plus"] ?: old.lokaal_plus
            val newMarkeren = map["markeren"] ?: old.markeren
            val newMarkerenLokaal = map["markerenlokaal"] ?: old.markerenlokaal
            val newAantal = map["aantal"] ?: old.aantal
            val newAantalterug = map["aantalterug"] ?: old.aantalterug
            val newOpmerkingen = map["opmerkingen"] ?: map["remarks"] ?: old.opmerkingen
            val newTeltype = map["teltype_C"] ?: old.teltype
            
            // Handle sightingdirection from compass selection
            val newSightingDirection = map["sightingdirection"] ?: old.sightingdirection
            
            // DEBUG: Log count overrides
            if (map.containsKey("aantal")) {
            }
            if (map.containsKey("aantalterug")) {
            }
            if (map.containsKey("lokaal")) {
            }
            
            // Handle direction fields based on ZW/NO checkboxes (legacy)
            var newRichting = old.richting
            var newRichtingterug = old.richtingterug
            
            // If ZW checkbox is checked, set main direction (legacy)
            if (map["ZW"] == "1") {
                newRichting = RICHTING_ZW
            }
            // If NO checkbox is checked, set return direction (legacy)
            if (map["NO"] == "1") {
                newRichtingterug = RICHTING_NO
            }
            
            // Calculate totaalaantal: sum of aantal + aantalterug + lokaal
            val aantalInt = newAantal.toIntOrZero()
            val aantalterugInt = newAantalterug.toIntOrZero()
            val lokaalInt = newLokaal.toIntOrZero()
            val newTotaalaantal = (aantalInt + aantalterugInt + lokaalInt).toString()
            
            // DEBUG: Log totaalaantal calculation
            
            // Set uploadtijdstip to current timestamp in "YYYY-MM-DD HH:MM:SS" format
            val newUploadtijdstip = getCurrentTimestamp()

            // Create updated copy with all annotations
            val updated = old.copy(
                leeftijd = newLeeftijd,
                geslacht = newGeslacht,
                kleed = newKleed,
                location = newLocation,
                height = newHeight,
                lokaal = newLokaal,
                lokaal_plus = newLokaalPlus,
                markeren = newMarkeren,
                markerenlokaal = newMarkerenLokaal,
                aantal = newAantal,
                aantalterug = newAantalterug,
                richting = newRichting,
                richtingterug = newRichtingterug,
                sightingdirection = newSightingDirection,
                opmerkingen = newOpmerkingen,
                teltype = newTeltype,
                totaalaantal = newTotaalaantal,
                uploadtijdstip = newUploadtijdstip
            )

            // DEBUG: Log updated record fields

            // Update in-memory pending record via callback
            onUpdatePendingRecord?.invoke(idx, updated)


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
    
    /**
     * Helper extension to safely convert string to int, returning 0 if conversion fails.
     */
    private fun String.toIntOrZero(): Int = this.toIntOrNull() ?: 0
    
    /**
     * Get current timestamp in "YYYY-MM-DD HH:MM:SS" format.
     * Uses SimpleDateFormat for compatibility with minSdk 33.
     */
    private fun getCurrentTimestamp(): String {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return dateFormat.format(java.util.Date())
    }
}
