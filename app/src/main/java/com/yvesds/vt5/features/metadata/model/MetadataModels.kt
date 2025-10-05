package com.yvesds.vt5.features.metadata.model

/**
 * Minimale header die we later 1-op-1 kunnen mappen
 * naar je upload-JSON (counts_save).
 *
 * Alle velden als String voor eenvoud/consistentie met jouw eerdere afspraak
 * ("server verwacht strings"). Je kan later in onVerder() naar exact
 * serverformaat transformeren (epoch strings, etc.).
 */
data class MetadataHeader(
    val datum: String = "",          // YYYY-MM-DD
    val tijd: String = "",           // HH:mm
    val telpostId: String = "",
    val telpostNaam: String = "",
    val tellers: String = "",

    // Weergegevens
    val weerOpmerking: String = "",
    val windrichting: String = "",
    val windkracht: String = "",
    val temperatuur: String = "",    // °C, numeriek als string
    val bewolking: String = "",      // achtsten
    val zicht: String = "",          // meters
    val neerslag: String = "",
    val luchtdruk: String = "",      // hPa

    // Overige
    val typeTelling: String = "",
    val opmerkingen: String = ""
)
