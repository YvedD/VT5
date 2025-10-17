package com.yvesds.vt5.features.speech

/**
 * Datamodellen voor de fonetische indexering van soorten
 */

/**
 * Een woord met zijn fonetische representatie
 */
data class PhoneticWord(
    val original: String,    // Origineel woord
    val phonetic: String     // Fonetische code
)

/**
 * Een entry in de fonetische index
 */
data class PhoneticEntry(
    val sourceId: String,    // Soort ID
    val sourceName: String,  // Volledige soortnaam
    val words: List<PhoneticWord>  // Woorden in de naam met hun fonetische codes
) {
    // Cache voor de eerste fonetische code (voor snelle filtering)
    val firstPhoneticCode: String? get() = words.firstOrNull()?.phonetic

    // Cache voor alle fonetische codes gecombineerd (voor fuzzy matching)
    val allPhonetics: String by lazy { words.joinToString("") { it.phonetic } }
}

/**
 * Fonetische index die snelle lookup mogelijk maakt
 */
class PhoneticIndex(entries: List<PhoneticEntry>) {
    // Index op eerste fonetische code
    private val firstCodeIndex: Map<String, List<PhoneticEntry>>

    // Index op soort ID (voor snelle lookup)
    private val entriesById: Map<String, PhoneticEntry>

    // Index op eerste letter (voor snelle filtering)
    private val firstLetterIndex: Map<Char, List<PhoneticEntry>>

    // Alle entries
    private val allEntries: List<PhoneticEntry>

    init {
        // Bouw de indexen op (eenmalig bij initialisatie)
        val firstCodeMap = mutableMapOf<String, MutableList<PhoneticEntry>>()
        val firstLetterMap = mutableMapOf<Char, MutableList<PhoneticEntry>>()
        val byIdMap = mutableMapOf<String, PhoneticEntry>()

        for (entry in entries) {
            // Index op ID
            byIdMap[entry.sourceId] = entry

            // Index op eerste fonetische code
            val firstCode = entry.firstPhoneticCode
            if (firstCode != null && firstCode.isNotEmpty()) {
                // Fix voor getOrPut - vervangen door expliciete code
                if (!firstCodeMap.containsKey(firstCode)) {
                    firstCodeMap[firstCode] = mutableListOf()
                }
                firstCodeMap[firstCode]?.add(entry)
            }

            // Index op eerste letter (voor snelle filtering)
            val firstLetter = entry.sourceName.firstOrNull()
            if (firstLetter != null) {
                // Fix voor getOrPut - vervangen door expliciete code
                if (!firstLetterMap.containsKey(firstLetter)) {
                    firstLetterMap[firstLetter] = mutableListOf()
                }
                firstLetterMap[firstLetter]?.add(entry)
            }
        }

        // Zet mutable maps om naar immutable maps
        firstCodeIndex = firstCodeMap.mapValues { it.value.toList() }
        entriesById = byIdMap.toMap()
        firstLetterIndex = firstLetterMap.mapValues { it.value.toList() }
        allEntries = entries.toList()
    }

    /**
     * Zoekt kandidaten op basis van een query en actieve soorten
     * Zeer geoptimaliseerde implementatie voor snelheid
     */
    fun findCandidates(
        query: String,
        activeSpecies: Set<String>,
        maxResults: Int = 10
    ): List<PhoneticEntry> {
        if (query.isBlank()) return emptyList()

        // Normaliseer de query
        val normalized = query.trim().lowercase()
        val queryFirstChar = normalized.firstOrNull() ?: return emptyList()

        // Snelle filter op eerste letter
        val candidates = firstLetterIndex[queryFirstChar]?.filter {
            activeSpecies.contains(it.sourceName)
        } ?: return emptyList()

        if (candidates.isEmpty()) return emptyList()

        // Scores berekenen en sorteren
        val scored = candidates.map { entry ->
            val phoneticScore = ColognePhonetic.similarity(normalized, entry.sourceName)
            entry to phoneticScore
        }

        // Sorteer op score en neem de top resultaten
        return scored
            .filter { it.second >= 0.7 } // Minimum score threshold
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }
    }

    /**
     * Snelle lookup op ID
     */
    fun getById(id: String): PhoneticEntry? = entriesById[id]

    /**
     * Geeft alle entries
     */
    fun getAllEntries(): List<PhoneticEntry> = allEntries
}