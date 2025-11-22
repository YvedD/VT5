package com.yvesds.vt5.features.metadata.helpers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.net.StartTellingApi
import com.yvesds.vt5.net.TrektellenApi
import com.yvesds.vt5.net.ServerTellingEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * TellingStarter: Handles starting a new telling session.
 * 
 * Responsibilities:
 * - Build telling envelope from form data
 * - Send counts_save API request
 * - Parse and store online ID
 * - Initialize session state
 * 
 * This encapsulates the complex logic of starting a telling.
 */
class TellingStarter(
    private val context: Context,
    private val binding: SchermMetadataBinding,
    private val formManager: MetadataFormManager,
    private val prefs: SharedPreferences
) {
    
    companion object {
        private const val TAG = "TellingStarter"
        private const val PREF_ONLINE_ID = "pref_online_id"
        private const val PREF_TELLING_ID = "pref_telling_id"
        private const val PREF_SAVED_ENVELOPE_JSON = "pref_saved_envelope_json"
    }
    
    data class StartResult(
        val success: Boolean,
        val onlineId: String?,
        val errorMessage: String?
    )
    
    /**
     * Start a new telling session.
     * Returns StartResult with success status and online ID or error message.
     */
    suspend fun startTelling(
        telpostId: String,
        username: String,
        password: String,
        snapshot: DataSnapshot
    ): StartResult = withContext(Dispatchers.IO) {
        try {
            // Generate telling ID
            val tellingIdLong = try {
                VT5App.nextTellingId().toLong()
            } catch (ex: Exception) {
                Log.w(TAG, "nextTellingId->Long failed: ${ex.message}")
                (System.currentTimeMillis() / 1000L)
            }
            
            // Get form values
            val begintijdEpoch = formManager.computeBeginEpochSec()
            val eindtijdEpoch = 0L // Live mode: empty end time
            
            val windrichtingForServer = formManager.gekozenWindrichtingCode?.takeIf { it.isNotBlank() }
                ?: binding.acWindrichting.text?.toString()?.takeIf { it.isNotBlank() } ?: ""
            
            val windkrachtBft = formManager.gekozenWindkracht ?: ""
            val temperatuurC = binding.etTemperatuur.text?.toString()?.trim().orEmpty()
            val bewolkingAchtsten = formManager.gekozenBewolking ?: ""
            val neerslagCode = formManager.gekozenNeerslagCode ?: ""
            val zichtMeters = binding.etZicht.text?.toString()?.trim().orEmpty()
            val typetellingCode = formManager.gekozenTypeTellingCode ?: ""
            val tellersFromUi = formManager.getTellers()
            val weerOpmerking = binding.etWeerOpmerking.text?.toString()?.trim().orEmpty()
            val opmerkingen = formManager.getOpmerkingen()
            val luchtdrukHpaRaw = binding.etLuchtdruk.text?.toString()?.trim().orEmpty()
            
            // Build envelope (live mode = true so eindtijd == "")
            val envelope = StartTellingApi.buildEnvelopeFromUi(
                tellingId = tellingIdLong,
                telpostId = telpostId,
                begintijdEpochSec = begintijdEpoch,
                eindtijdEpochSec = eindtijdEpoch,
                windrichtingLabel = windrichtingForServer,
                windkrachtBftOnly = windkrachtBft,
                temperatuurC = temperatuurC,
                bewolkingAchtstenOnly = bewolkingAchtsten,
                neerslagCode = neerslagCode,
                zichtMeters = zichtMeters,
                typetellingCode = typetellingCode,
                telers = tellersFromUi,
                weerOpmerking = weerOpmerking,
                opmerkingen = opmerkingen,
                luchtdrukHpaRaw = luchtdrukHpaRaw,
                liveMode = true
            )
            
            // Post counts_save
            val baseUrl = "https://trektellen.nl"
            val language = "dutch"
            val versie = "1845"
            
            val (ok, resp) = try {
                TrektellenApi.postCountsSave(baseUrl, language, versie, username, password, envelope)
            } catch (ex: Exception) {
                Log.w(TAG, "postCountsSave exception: ${ex.message}", ex)
                false to (ex.message ?: "exception")
            }
            
            if (!ok) {
                Log.w(TAG, "counts_save failed: $resp")
                return@withContext StartResult(false, null, resp)
            }
            
            // Parse online ID from response
            val onlineId = parseOnlineIdFromResponse(resp)
            if (onlineId.isNullOrBlank()) {
                Log.w(TAG, "Could not parse onlineId from response: $resp")
                return@withContext StartResult(false, null, "Could not parse online ID: $resp")
            }
            
            // Store in preferences
            prefs.edit {
                putString(PREF_ONLINE_ID, onlineId)
                putString(PREF_TELLING_ID, tellingIdLong.toString())
            }
            
            // Initialize record counter
            try {
                prefs.edit {
                    putLong("pref_next_record_id_$tellingIdLong", 1L)
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed initializing next record id: ${ex.message}")
            }
            
            // Save envelope JSON for later reuse
            try {
                val envelopeJson = VT5App.json.encodeToString(
                    ListSerializer(ServerTellingEnvelope.serializer()), 
                    envelope
                )
                prefs.edit {
                    putString(PREF_SAVED_ENVELOPE_JSON, envelopeJson)
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed saving envelope JSON to prefs: ${ex.message}")
            }
            
            return@withContext StartResult(true, onlineId, null)
        } catch (e: Exception) {
            Log.e(TAG, "startTelling failed: ${e.message}", e)
            return@withContext StartResult(false, null, e.message ?: "Unknown error")
        }
    }
    
    /**
     * Parse online ID from server response.
     * Tries JSON parsing first, then falls back to regex.
     */
    private fun parseOnlineIdFromResponse(resp: String): String? {
        try {
            val el = VT5App.json.parseToJsonElement(resp)
            
            // If it's an array take first object
            val obj = when {
                el.jsonArrayOrNull() != null && el.jsonArray.size > 0 -> el.jsonArray[0]
                el.jsonObjectOrNull() != null -> el
                else -> null
            }
            
            if (obj != null) {
                val jo = if (obj.jsonObjectOrNull() != null) obj.jsonObject else el.jsonArray[0].jsonObject
                
                // Common keys
                listOf("onlineid", "onlineId", "id", "result", "online_id").forEach { key ->
                    if (jo.containsKey(key)) {
                        val v = jo[key]?.toString()?.replace("\"", "") ?: ""
                        if (v.isNotBlank()) return v
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to regex
        }
        
        // Fallback: find a sequence of 4-10 digits in the response
        val regex = Regex("""\b(\d{4,10})\b""")
        val m = regex.find(resp)
        return m?.groups?.get(1)?.value
    }
    
    private fun JsonElement.jsonArrayOrNull() = runCatching { this.jsonArray }.getOrNull()
    private fun JsonElement.jsonObjectOrNull() = runCatching { this.jsonObject }.getOrNull()
}
