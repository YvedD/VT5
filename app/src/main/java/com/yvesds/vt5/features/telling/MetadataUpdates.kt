package com.yvesds.vt5.features.telling

import kotlinx.serialization.Serializable

/**
 * MetadataUpdates: Data class voor het bijwerken van metadata velden.
 * 
 * Alle velden zijn optioneel (null = niet wijzigen).
 * Wanneer een veld niet-null is, wordt dat veld bijgewerkt in de envelope.
 */
@Serializable
data class MetadataUpdates(
    val tellers: String? = null,
    val opmerkingen: String? = null,
    val windrichting: String? = null,
    val windkracht: String? = null,
    val temperatuur: String? = null,
    val bewolking: String? = null,
    val bewolkinghoogte: String? = null,
    val neerslag: String? = null,
    val duurneerslag: String? = null,
    val zicht: String? = null,
    val hpa: String? = null,
    val typetelling: String? = null,
    val weer: String? = null,
    val tellersactief: String? = null,
    val tellersaanwezig: String? = null,
    val metersnet: String? = null,
    val geluid: String? = null,
    val equipment: String? = null
)
