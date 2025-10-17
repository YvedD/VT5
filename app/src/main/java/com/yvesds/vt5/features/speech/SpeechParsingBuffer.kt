package com.yvesds.vt5.features.speech

/**
 * Buffer voor spraakherkenningsresultaten voor stabielere herkenning
 * door meerdere resultaten te combineren.
 */
class SpeechParsingBuffer(
    private val maxSize: Int = 5,  // Maximaal aantal items in de buffer
    private val expiryTimeMs: Long = 5000  // Verlooptijd in ms
) {
    private val items = mutableListOf<BufferedItem>()

    data class BufferedItem(
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Voegt een nieuw spraakresultaat toe aan de buffer
     */
    fun add(text: String) {
        val now = System.currentTimeMillis()

        // Verwijder verlopen items
        items.removeIf { now - it.timestamp > expiryTimeMs }

        // Voeg nieuw item toe
        items.add(BufferedItem(text, now))

        // Houd maximale grootte aan
        while (items.size > maxSize) {
            items.removeAt(0)
        }
    }

    /**
     * Haalt het meest voorkomende woord op uit de buffer
     */
    fun getMostFrequentWords(): List<String> {
        if (items.isEmpty()) return emptyList()

        // Tokenize alle items
        val allWords = mutableListOf<String>()
        for (item in items) {
            val normalized = item.text.lowercase().trim()
            val words = normalized.split(Regex("\\s+"))
            allWords.addAll(words)
        }

        // Tel frequentie van woorden
        val wordFreq = mutableMapOf<String, Int>()
        for (word in allWords) {
            wordFreq[word] = (wordFreq[word] ?: 0) + 1
        }

        // Sorteer op frequentie
        return wordFreq.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Haalt de laatst toegevoegde tekst op
     */
    fun getLatest(): String? {
        return items.lastOrNull()?.text
    }

    /**
     * Leegt de buffer
     */
    fun clear() {
        items.clear()
    }
}