package com.yvesds.vt5.features.telling

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.net.ServerTellingDataItem
import com.yvesds.vt5.net.ServerTellingEnvelope
import com.yvesds.vt5.net.TrektellenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TellingAfrondHandler: Handles the complete "Afronden" (finalize) flow for TellingScherm.
 * 
 * Responsibilities:
 * - Building the final envelope with counts
 * - Uploading to server
 * - Handling server response
 * - Cleanup of temporary files and preferences
 * - Cleanup of active_telling.json (continuous backup)
 * - Error handling and user feedback
 */
class TellingAfrondHandler(
    private val context: Context,
    private val backupManager: TellingBackupManager,
    private val dataProcessor: TellingDataProcessor,
    private val envelopePersistence: TellingEnvelopePersistence? = null
) {
    companion object {
        private const val TAG = "TellingAfrondHandler"
        private const val PREFS_NAME = "vt5_prefs"
        private const val PREF_SAVED_ENVELOPE_JSON = "pref_saved_envelope_json"
        private const val PREF_ONLINE_ID = "pref_online_id"
        private const val PREF_TELLING_ID = "pref_telling_id"
        
        private val PRETTY_JSON: Json by lazy { Json { prettyPrint = true; encodeDefaults = true } }
    }

    /**
     * Result of handleAfronden operation.
     */
    sealed class AfrondResult {
        data class Success(
            val savedPrettyPath: String?,
            val auditPath: String?
        ) : AfrondResult()
        
        data class Failure(
            val title: String,
            val message: String,
            val savedPrettyPath: String? = null,
            val auditPath: String? = null
        ) : AfrondResult()
    }

    /**
     * Handle the complete Afronden (finalize and upload) flow.
     * 
     * @param pendingRecords List of records to include in the envelope
     * @param pendingBackupDocs List of backup document files to clean up on success
     * @param pendingBackupInternalPaths List of internal backup paths to clean up on success
     * @param metadataUpdates Optional metadata updates (begintijd, eindtijd, opmerkingen)
     */
    suspend fun handleAfronden(
        pendingRecords: List<ServerTellingDataItem>,
        pendingBackupDocs: List<DocumentFile>,
        pendingBackupInternalPaths: List<String>,
        metadataUpdates: MetadataUpdates? = null
    ): AfrondResult = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // 1. Load saved envelope
        val savedEnvelopeJson = prefs.getString(PREF_SAVED_ENVELOPE_JSON, null)
        if (savedEnvelopeJson.isNullOrBlank()) {
            return@withContext AfrondResult.Failure(
                title = "Geen metadata",
                message = "Er is geen opgeslagen metadata (counts_save header). Keer terug naar metadata en start een telling."
            )
        }

        val envelopeList = try {
            VT5App.json.decodeFromString(
                ListSerializer(ServerTellingEnvelope.serializer()), 
                savedEnvelopeJson
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed decoding saved envelope JSON: ${e.message}", e)
            null
        }
        
        if (envelopeList.isNullOrEmpty()) {
            return@withContext AfrondResult.Failure(
                title = "Ongeldige envelope",
                message = "Opgeslagen envelope ongeldig."
            )
        }

        // 2. Inject saved onlineId into envelope
        val savedOnlineId = prefs.getString(PREF_ONLINE_ID, "")
        val envelopeWithOnline = dataProcessor.applySavedOnlineIdToEnvelope(envelopeList, savedOnlineId)
        persistSavedEnvelopeJson(prefs, envelopeWithOnline)

        // 3. Build final envelope with times and records
        val nowEpoch = (System.currentTimeMillis() / 1000L)
        val nowEpochStr = nowEpoch.toString()
        val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val baseEnv = envelopeWithOnline[0]
        
        // Apply metadata updates if provided (begintijd, eindtijd, opmerkingen)
        // Otherwise use defaults: keep original begintijd, set eindtijd to now
        // Note: Use ifBlank to also handle empty string cases, not just null
        val effectiveBegintijd = metadataUpdates?.begintijd?.ifBlank { null } ?: baseEnv.begintijd
        val effectiveEindtijd = metadataUpdates?.eindtijd?.ifBlank { null } ?: nowEpochStr
        val effectiveOpmerkingen = metadataUpdates?.opmerkingen ?: baseEnv.opmerkingen
        
        val envWithTimes = baseEnv.copy(
            begintijd = effectiveBegintijd,
            eindtijd = effectiveEindtijd,
            opmerkingen = effectiveOpmerkingen,
            uploadtijdstip = nowFormatted
        )

        val recordsSnapshot = ArrayList(pendingRecords)
        val nrec = recordsSnapshot.size
        val nsoort = recordsSnapshot.map { it.soortid }.toSet().size

        val finalEnv = envWithTimes.copy(
            nrec = nrec.toString(), 
            nsoort = nsoort.toString(), 
            data = recordsSnapshot
        )
        val envelopeToSend = listOf(finalEnv)

        // 4. Pretty print and save envelope
        val onlineIdPref = savedOnlineId ?: ""
        val prettyJson = try {
            PRETTY_JSON.encodeToString(
                ListSerializer(ServerTellingEnvelope.serializer()), 
                envelopeToSend
            )
        } catch (e: Exception) {
            Log.w(TAG, "pretty encode failed: ${e.message}", e)
            null
        }

        var savedPrettyPath: String? = null
        if (prettyJson != null) {
            savedPrettyPath = backupManager.writePrettyEnvelopeToSaf(
                onlineIdPref.ifBlank { "unknown" }, 
                prettyJson
            ) ?: backupManager.writePrettyEnvelopeInternal(
                onlineIdPref.ifBlank { "unknown" }, 
                prettyJson
            )
        }

        // 5. Get credentials and prepare for upload
        val creds = CredentialsStore(context)
        val user = creds.getUsername().orEmpty()
        val pass = creds.getPassword().orEmpty()
        
        if (user.isBlank() || pass.isBlank()) {
            return@withContext AfrondResult.Failure(
                title = "Geen credentials",
                message = "Geen credentials beschikbaar voor upload.",
                savedPrettyPath = savedPrettyPath
            )
        }

        // 6. Upload to server
        val baseUrl = "https://trektellen.nl"
        val language = "dutch"
        val versie = "1845"

        val (ok, resp) = try {
            TrektellenApi.postCountsSave(baseUrl, language, versie, user, pass, envelopeToSend)
        } catch (ex: Exception) {
            Log.w(TAG, "postCountsSave exception: ${ex.message}", ex)
            false to (ex.message ?: "exception")
        }

        // 7. Write audit file
        val auditPath = try {
            backupManager.writeEnvelopeResponseToSaf(
                finalEnv.tellingid, 
                prettyJson ?: "{}", 
                resp
            ) ?: backupManager.writeEnvelopeResponseInternal(
                finalEnv.tellingid, 
                prettyJson ?: "{}", 
                resp
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write audit: ${e.message}", e)
            null
        }

        // 8. Handle result
        if (!ok) {
            return@withContext AfrondResult.Failure(
                title = "Upload mislukt",
                message = "Kon telling niet uploaden:\n$resp\n\n" +
                         "Envelope opgeslagen: ${savedPrettyPath ?: "niet beschikbaar"}\n" +
                         "Auditbestand: ${auditPath ?: "niet beschikbaar"}",
                savedPrettyPath = savedPrettyPath,
                auditPath = auditPath
            )
        }

        // 9. On success: parse returned onlineId and update prefs
        try {
            val returnedOnlineId = dataProcessor.parseOnlineIdFromResponse(resp)
            if (!returnedOnlineId.isNullOrBlank()) {
                prefs.edit { putString(PREF_ONLINE_ID, returnedOnlineId) }

                // Update saved envelope JSON with returned onlineId
                try {
                    val updated = envelopeWithOnline.toMutableList()
                    if (updated.isNotEmpty()) {
                        val first = updated[0].copy(onlineid = returnedOnlineId)
                        updated[0] = first
                        persistSavedEnvelopeJson(prefs, updated)
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed persisting envelope with returned onlineId: ${ex.message}", ex)
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Parsing/handling returned onlineId failed: ${ex.message}", ex)
        }

        // 10. Cleanup: remove backups and clear preferences
        try {
            pendingBackupDocs.forEach { doc -> 
                try { doc.delete() } catch (_: Exception) {} 
            }
            
            pendingBackupInternalPaths.forEach { path -> 
                try { java.io.File(path).delete() } catch (_: Exception) {} 
            }

            // Remove telling-related preferences
            prefs.edit {
                remove(PREF_ONLINE_ID)
                remove(PREF_TELLING_ID)
                remove(PREF_SAVED_ENVELOPE_JSON)
            }
            
            // Save the FINAL envelope (with correct eindtijd) to counts folder
            // This ensures the saved JSON is IDENTICAL to what was sent to the server
            try {
                val tellingId = finalEnv.tellingid
                val archiveOnlineId = savedOnlineId ?: finalEnv.onlineid
                if (prettyJson != null) {
                    envelopePersistence?.saveFinalEnvelopeToCountsDir(tellingId, archiveOnlineId, prettyJson)
                } else {
                    // Fallback: archive the old way if prettyJson is null
                    envelopePersistence?.archiveSavedEnvelope(tellingId, archiveOnlineId)
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to save final envelope to counts: ${ex.message}", ex)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup after successful Afronden failed: ${e.message}", e)
        }

        return@withContext AfrondResult.Success(
            savedPrettyPath = savedPrettyPath,
            auditPath = auditPath
        )
    }

    /**
     * Persist saved envelope JSON to preferences.
     */
    private fun persistSavedEnvelopeJson(
        prefs: SharedPreferences,
        envelopeList: List<ServerTellingEnvelope>
    ) {
        try {
            val json = VT5App.json.encodeToString(
                ListSerializer(ServerTellingEnvelope.serializer()), 
                envelopeList
            )
            prefs.edit { putString(PREF_SAVED_ENVELOPE_JSON, json) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist saved envelope JSON: ${e.message}", e)
        }
    }

    /**
     * Build envelope summary for display.
     */
    fun buildEnvelopeSummary(envelope: ServerTellingEnvelope): String {
        return dataProcessor.buildEnvelopeSummary(envelope)
    }
}
