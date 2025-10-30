package com.yvesds.vt5.features.speech

/**
 * Buffer voor spraakherkenningsresultaten voor stabielere herkenning
 * door meerdere resultaten te combineren.
 *
 * Minor improvements:
 * - Thread-safe operations (synchronized)
 * - getMostFrequentWords returns top-N words (configurable)
 */
class SpeechParsingBuffer(
    private val maxSize: Int = 5,
    private val expiryTimeMs: Long = 5000
) {
    private val items = mutableListOf<BufferedItem>()

    data class BufferedItem(
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun add(text: String) {
        val now = System.currentTimeMillis()
        synchronized(items) {
            items.removeIf { now - it.timestamp > expiryTimeMs }
            items.add(BufferedItem(text, now))
            while (items.size > maxSize) items.removeAt(0)
        }
    }

    /**
     * Return words sorted by frequency (most frequent first). Limit to topN if provided.
     */
    fun getMostFrequentWords(topN: Int = 10): List<String> {
        synchronized(items) {
            if (items.isEmpty()) return emptyList()
            val allWords = ArrayList<String>(items.size * 3)
            for (item in items) {
                val normalized = item.text.lowercase().trim()
                val words = normalized.split(Regex("\\s+"))
                allWords.addAll(words)
            }
            val wordFreq = mutableMapOf<String, Int>()
            for (w in allWords) wordFreq[w] = (wordFreq[w] ?: 0) + 1
            return wordFreq.entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(topN)
        }
    }

    fun getLatest(): String? {
        synchronized(items) { return items.lastOrNull()?.text }
    }

    fun clear() {
        synchronized(items) { items.clear() }
    }
}