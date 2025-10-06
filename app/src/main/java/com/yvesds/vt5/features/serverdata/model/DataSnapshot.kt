@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.yvesds.vt5.features.serverdata.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ---------------- Wrapper { "json": [ ... ] } ---------------- */
@Serializable
data class WrappedJson<T>(
    @SerialName("json") val json: List<T> = emptyList()
)

/* ---------------- checkuser.json ---------------- */
@Serializable
data class CheckUserItem(
    val message: String? = null,
    val userid: String,
    val fullname: String,
    val password: String? = null
)

/* ---------------- species.json ---------------- */
@Serializable
data class SpeciesItem(
    val soortid: String,
    val soortnaam: String,
    val soortkey: String,
    val latin: String,
    val sortering: String
)

/* ---------------- protocolinfo.json ---------------- */
@Serializable
data class ProtocolInfoItem(
    val id: String,
    val protocolid: String,
    val veld: String,
    val waarde: String? = null,
    val tekst: String? = null,
    val sortering: String? = null
)

/* ---------------- protocolspecies.json ---------------- */
@Serializable
data class ProtocolSpeciesItem(
    val id: String,
    val protocolid: String,
    val soortid: String,
    val geslacht: String? = null,
    val leeftijd: String? = null,
    val kleed: String? = null
)

/* ---------------- sites.json ---------------- */
@Serializable
data class SiteItem(
    val telpostid: String,
    val telpostnaam: String,
    val r1: String? = null,
    val r2: String? = null,
    val typetelpost: String? = null,
    val protocolid: String? = null
)

/* ---------------- site_locations.json / site_heights.json ---------------- */
@Serializable
data class SiteValueItem(
    val telpostid: String,
    val waarde: String,
    val sortering: String? = null
)

/* ---------------- site_species.json ---------------- */
@Serializable
data class SiteSpeciesItem(
    val telpostid: String,
    val soortid: String
)

/* ---------------- codes.json ----------------
 * Mapt exact de NL-sleutels naar Kotlin velden.
 * - JSON "veld"       -> category
 * - JSON "tekstkey"   -> key
 * - JSON "waarde"     -> value
 * - JSON "tekst"      -> tekst
 * - JSON "sortering"  -> sortering
 */
@Serializable
data class CodeItem(
    @SerialName("veld")      val category: String? = null,
    @SerialName("id")        val id: String? = null,
    @SerialName("tekstkey")  val key: String? = null,
    @SerialName("waarde")    val value: String? = null,
    @SerialName("tekst")     val tekst: String? = null,
    @SerialName("sortering") val sortering: String? = null
)

/* ---------------- DataSnapshot ---------------- */
@Serializable
data class DataSnapshot(
    // User
    val currentUser: CheckUserItem? = null,

    // Species
    val speciesById: Map<String, SpeciesItem> = emptyMap(),
    val speciesByCanonical: Map<String, String> = emptyMap(),

    // Sites
    val sitesById: Map<String, SiteItem> = emptyMap(),
    val assignedSites: List<String> = emptyList(),

    // Site helpers
    val siteLocationsBySite: Map<String, List<SiteValueItem>> = emptyMap(),
    val siteHeightsBySite: Map<String, List<SiteValueItem>> = emptyMap(),
    val siteSpeciesBySite: Map<String, List<SiteSpeciesItem>> = emptyMap(),

    // Protocol
    val protocolsInfo: List<ProtocolInfoItem> = emptyList(),
    val protocolSpeciesByProtocol: Map<String, List<ProtocolSpeciesItem>> = emptyMap(),

    // Codes – reeds gegroepeerd op category = JSON "veld"
    val codesByCategory: Map<String, List<CodeItem>> = emptyMap()
)
