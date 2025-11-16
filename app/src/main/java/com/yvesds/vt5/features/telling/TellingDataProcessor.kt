package com.yvesds.vt5.features.telling

import android.content.Context
import android.util.Log
import com.yvesds.vt5.net.ServerTellingEnvelope
import kotlinx.serialization.json.Json

/**
 * TellingDataProcessor: Handles data processing and transformations for TellingScherm.
 * 
 * Responsibilities:
 * - Parsing server responses (onlineId extraction)
 * - Applying annotations to pending records
 * - Applying saved onlineId to envelopes
 * - JSON serialization/deserialization helpers
 */
class TellingDataProcessor {
    companion object {
        private const val TAG = "TellingDataProcessor"
    }

    /**
     * Parse onlineId from server response.
     * Expected format: "onlineid=[value]" somewhere in the response.
     */
    fun parseOnlineIdFromResponse(response: String): String? {
        try {
            // Look for "onlineid=" pattern in response
            val regex = Regex("onlineid\\s*=\\s*([\\w-]+)", RegexOption.IGNORE_CASE)
            val match = regex.find(response)
            return match?.groups?.get(1)?.value
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse onlineId from response: ${e.message}", e)
            return null
        }
    }

    /**
     * Apply saved onlineId to envelope list if onlineId is blank in envelope.
     */
    fun applySavedOnlineIdToEnvelope(
        envelopeList: List<ServerTellingEnvelope>,
        savedOnlineId: String?
    ): List<ServerTellingEnvelope> {
        if (savedOnlineId.isNullOrBlank()) return envelopeList
        
        return envelopeList.map { env ->
            if (env.onlineid.isBlank()) {
                env.copy(onlineid = savedOnlineId)
            } else {
                env
            }
        }
    }

    /**
     * Parse annotations JSON and return as Map.
     */
    fun parseAnnotationsJson(annotationsJson: String): Map<String, String?>? {
        try {
            return Json.decodeFromString<Map<String, String?>>(annotationsJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse annotations JSON: ${e.message}", e)
            return null
        }
    }

    /**
     * Build a single annotation map from legacy text field.
     */
    fun buildLegacyAnnotation(text: String): Map<String, String?> {
        return mapOf("opmerkingen" to text)
    }

    /**
     * Validate and sanitize count value.
     */
    fun validateCount(count: Int): Int {
        return count.coerceAtLeast(0).coerceAtMost(99999)
    }

    /**
     * Format timestamp for display.
     */
    fun formatTimestamp(epochSeconds: Long): String {
        return try {
            val date = java.util.Date(epochSeconds * 1000)
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(date)
        } catch (e: Exception) {
            epochSeconds.toString()
        }
    }

    /**
     * Extract species ID from tile name if needed.
     */
    fun extractSpeciesIdFromTileName(tileName: String): String {
        // Species ID is often embedded in tile names
        // This is a placeholder - adjust based on actual format
        return tileName.trim()
    }

    /**
     * Build envelope summary for logging.
     */
    fun buildEnvelopeSummary(envelope: ServerTellingEnvelope): String {
        return "TellingId: ${envelope.tellingid}, OnlineId: ${envelope.onlineid}, " +
               "Records: ${envelope.nrec}, Species: ${envelope.nsoort}"
    }
}
