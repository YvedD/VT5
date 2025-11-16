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

        // ASR messages (after stripping "asr:" prefix) go to finals
        if (msg.contains(RE_ASR_PREFIX)) {
            val stripped = msg.replace(RE_ASR_PREFIX, "")
            return addToFinals(stripped, "final")
        }

        // Raw messages
        if (bron == "raw") {
            return addToFinals(msg, bron)
        }

        // Partials
        if (bron == "partial") {
            return addToPartials(msg, bron)
        }

        // Finals (default)
        return addToFinals(msg, bron)
    }

    /**
     * Upsert partial log: replaces last partial or adds new.
     */
    fun upsertPartialLog(text: String): List<TellingScherm.SpeechLogRow> {
        val now = System.currentTimeMillis()
        
        // Remove old partials
        partialsLog.removeIf { it.bron == "partial" }
        
        // Add new partial
        partialsLog.add(TellingScherm.SpeechLogRow(now, text, "partial"))
        
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
        val now = System.currentTimeMillis()
        partialsLog.add(TellingScherm.SpeechLogRow(now, msg, bron))
        
        if (partialsLog.size > maxLogRows) {
            partialsLog.removeAt(0)
        }
        
        return partialsLog.toList()
    }

    private fun addToFinals(msg: String, bron: String): List<TellingScherm.SpeechLogRow> {
        val now = System.currentTimeMillis()
        finalsLog.add(TellingScherm.SpeechLogRow(now, msg, bron))
        
        if (finalsLog.size > maxLogRows) {
            finalsLog.removeAt(0)
        }
        
        return finalsLog.toList()
    }
}
