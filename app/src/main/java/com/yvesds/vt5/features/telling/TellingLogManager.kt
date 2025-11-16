package com.yvesds.vt5.features.telling

import android.util.Log
import java.util.Locale

/**
 * TellingLogManager: Manages speech logs (partials and finals) for TellingScherm.
 * 
 * Responsibilities:
 * - Adding log entries to partials/finals lists
 * - Parsing display text to extract names and counts
 * - Managing log history and limits
 */
class TellingLogManager(
    private val maxLogRows: Int = 600
) {
    companion object {
        private const val TAG = "TellingLogManager"
        private val RE_ASR_PREFIX = Regex("(?i)^\\s*asr:\\s*")
        private val RE_TRIM_RAW_NUMBER = Regex("\\s+\\d+(?:[.,]\\d+)?\$")
        private val RE_TRAILING_NUMBER = Regex("^(.*?)(?:\\s+(\\d+)(?:[.,]\\d+)?)?\$")
    }

    // Storage for log entries
    private val partialsLog = mutableListOf<TellingScherm.SpeechLogRow>()
    private val finalsLog = mutableListOf<TellingScherm.SpeechLogRow>()

    /**
     * Add a log entry. Routes to partials or finals based on source.
     * 
     * Routing logic (matching commit 4e5359e behavior):
     * - bron == "final" → finals adapter (parsed/recognized species)
     * - bron == "raw", "partial", "systeem" → partials adapter (raw ASR output)
     */
    fun addLog(msgIn: String, bron: String): List<TellingScherm.SpeechLogRow>? {
        val msg = msgIn.trim()
        if (msg.isBlank()) return null

        // Filter system messages: only "Luisteren..." goes to partials
        if (bron == "systeem") {
            val lowerMsg = msg.lowercase(Locale.getDefault())
            if (lowerMsg.contains("luisteren")) {
                return addToPartials(msg, bron)
            }
            // Other system messages are ignored
            return null
        }

        // Clean up message based on source type
        val cleaned = when (bron) {
            "raw" -> {
                // Strip "asr:" prefix and trailing numbers from raw messages
                var m = msg.replace(RE_ASR_PREFIX, "")
                m = m.replace(RE_TRIM_RAW_NUMBER, "")
                m.trim()
            }
            else -> {
                // Strip "asr:" prefix from other messages
                msg.replace(RE_ASR_PREFIX, "").trim()
            }
        }

        // Route to finals or partials based on bron
        // IMPORTANT: Only "final" goes to finals, everything else (raw, partial, etc.) goes to partials
        return if (bron == "final") {
            addToFinals(cleaned, bron)
        } else {
            addToPartials(cleaned, bron)
        }
    }

    /**
     * Upsert partial log: replaces last partial or adds new.
     */
    fun upsertPartialLog(text: String): List<TellingScherm.SpeechLogRow> {
        val cleanedRaw = text.trim()
        // Ignore empty partials (common at start of capture)
        if (cleanedRaw.isBlank()) return partialsLog.toList()

        // Parse name and count from raw hypothesis
        val (nameOnly, cnt) = run {
            val m = RE_TRAILING_NUMBER.find(cleanedRaw)
            if (m != null) {
                val name = m.groups[1]?.value?.trim().orEmpty()
                val c = m.groups[2]?.value?.toIntOrNull() ?: 0
                name to c
            } else {
                cleanedRaw to 0
            }
        }

        // Compose display text: if count present, format "Name -> +N", else plain name
        val display = if (cnt > 0) "$nameOnly -> +$cnt" else nameOnly
        
        val now = System.currentTimeMillis() / 1000L  // Use seconds for consistency
        
        // Remove old partials
        partialsLog.removeIf { it.bron == "partial" }
        
        // Add new partial
        partialsLog.add(TellingScherm.SpeechLogRow(now, display, "partial"))
        
        // Trim if needed
        if (partialsLog.size > maxLogRows) {
            partialsLog.removeAt(0)
        }
        
        return partialsLog.toList()
    }

    /**
     * Add final log entry.
     */
    fun addFinalLog(text: String): List<TellingScherm.SpeechLogRow> {
        return addToFinals(text, "final")
    }

    /**
     * Parse display text to extract name and count.
     * Examples: "Buizerd 3" -> ("Buizerd", 3), "Buizerd" -> ("Buizerd", 1)
     */
    fun parseNameAndCountFromDisplay(text: String): Pair<String, Int> {
        var workingText = text.trim()
        
        // Strip "asr:" prefix if present
        workingText = workingText.replace(RE_ASR_PREFIX, "")
        
        // Try to match trailing number
        val match = RE_TRAILING_NUMBER.find(workingText)
        if (match != null) {
            val nameOnly = (match.groups[1]?.value ?: workingText).trim()
            val countStr = match.groups[2]?.value
            val count = countStr?.toIntOrNull() ?: 1
            return nameOnly to count
        }
        
        return workingText to 1
    }

    /**
     * Extract count from text.
     */
    fun extractCountFromText(text: String): Int {
        val (_, count) = parseNameAndCountFromDisplay(text)
        return count
    }

    /**
     * Get current partials log.
     */
    fun getPartials(): List<TellingScherm.SpeechLogRow> = partialsLog.toList()

    /**
     * Get current finals log.
     */
    fun getFinals(): List<TellingScherm.SpeechLogRow> = finalsLog.toList()

    /**
     * Clear all logs.
     */
    fun clearAll() {
        partialsLog.clear()
        finalsLog.clear()
    }

    // Private helpers
    
    private fun addToPartials(msg: String, bron: String): List<TellingScherm.SpeechLogRow> {
        val now = System.currentTimeMillis() / 1000L  // Use seconds for consistency
        partialsLog.add(TellingScherm.SpeechLogRow(now, msg, bron))
        
        if (partialsLog.size > maxLogRows) {
            partialsLog.removeAt(0)
        }
        
        return partialsLog.toList()
    }

    private fun addToFinals(msg: String, bron: String): List<TellingScherm.SpeechLogRow> {
        val now = System.currentTimeMillis() / 1000L  // Use seconds for consistency
        finalsLog.add(TellingScherm.SpeechLogRow(now, msg, bron))
        
        if (finalsLog.size > maxLogRows) {
            finalsLog.removeAt(0)
        }
        
        return finalsLog.toList()
    }
}
