package com.yvesds.vt5.features.telling

import kotlinx.serialization.Serializable

/**
 * TellingFileInfo: Informatie over een opgeslagen telling bestand.
 * 
 * Wordt gebruikt voor het weergeven van een lijst met beschikbare tellingen
 * in de counts map (Documents/VT5/counts/).
 */
@Serializable
data class TellingFileInfo(
    /** Bestandsnaam (bijv. "telling_1_12345_20251129_140000.json") */
    val filename: String,
    
    /** Telling ID uit de envelope (indien beschikbaar) */
    val tellingId: String? = null,
    
    /** Online ID uit de envelope (indien beschikbaar) */
    val onlineId: String? = null,
    
    /** Telpost naam of ID (indien beschikbaar) */
    val telpost: String? = null,
    
    /** Datum/tijd string uit bestandsnaam (indien beschikbaar) */
    val timestamp: String? = null,
    
    /** Aantal records in de telling */
    val nrec: Int = 0,
    
    /** Aantal soorten in de telling */
    val nsoort: Int = 0,
    
    /** Bestandsgrootte in bytes */
    val fileSize: Long = 0,
    
    /** Laatste wijzigingsdatum (epoch milliseconds) */
    val lastModified: Long = 0,
    
    /** Of dit het actieve telling bestand is */
    val isActive: Boolean = false
)
