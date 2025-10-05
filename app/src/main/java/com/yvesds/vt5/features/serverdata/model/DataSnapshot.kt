package com.yvesds.vt5.features.serverdata.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * === VT5 – Serverdata modellen + DataSnapshot ===
 *
 * Opmerking top-level JSON: de voorbeelden hanteren { "json": [ ... ] }.
 * We modelleren dat expliciet met WrappedJson<T>. De loader kan ook omgaan
 * met payloads die rechtstreeks een array bevatten (List<T>) of een enkel object,
 * en normaliseert dat intern waar nodig.
 */

/* ------------ Generieke wrapper: { "json": [ ... ] } ------------ */
@Serializable
data class WrappedJson<T>(
    @SerialName("json") val json: List<T> = emptyList()
)

/* ------------ checkuser.json ------------ */
@Serializable
data class CheckUserItem(
    val message: String? = null,
    val userid: String,
    val fullname: String,
    val password: String? = null
)

/* ------------ species.json ------------ */
@Serializable
data class SpeciesItem(
    val soortid: String,
    val soortnaam: String,
    val soortkey: String,
    val latin: String,
    val sortering: String
)

/* ------------ protocolinfo.json ------------ */
@Serializable
data class ProtocolInfoItem(
    val id: String,
    val protocolid: String,
    val veld: String,
    val waarde: String? = null,
    val tekst: String? = null,
    val sortering: String? = null
)

/* ------------ protocolspecies.json ------------ */
@Serializable
data class ProtocolSpeciesItem(
    val id: String,
    val protocolid: String,
    val soortid: String,
    val geslacht: String? = null,
    val leeftijd: String? = null,
    val kleed: String? = null
)

/* ------------ sites.json ------------ */
@Serializable
data class SiteItem(
    val telpostid: String,
    val telpostnaam: String,
    val r1: String? = null,
    val r2: String? = null,
    val typetelpost: String? = null,
    val protocolid: String? = null
)

/* ------------ site_locations.json & site_heights.json ------------ */
@Serializable
data class SiteValueItem(
    val telpostid: String,
    val waarde: String,
    val sortering: String? = null
)

/* ------------ site_species.json ------------ */
@Serializable
data class SiteSpeciesItem(
    val telpostid: String,
    val soortid: String
)

/* ------------ codes.json ------------
 * Generiek model; wordt aangescherpt zodra het definitieve schema vastligt.
 */
@Serializable
data class CodeItem(
    val category: String? = null,
    val id: String? = null,
    val key: String? = null,
    val value: String? = null,
    val tekst: String? = null,
    val sortering: String? = null
)

/* ------------ DataSnapshot ------------
 * Immutable snapshot gepubliceerd via StateFlow in de repository.
 */
@Serializable
data class DataSnapshot(
    // User
    val currentUser: CheckUserItem? = null,

    // Species
    val speciesById: Map<String, SpeciesItem> = emptyMap(),
    /** canonical(lower + diacritics-vrij) -> soortid */
    val speciesByCanonical: Map<String, String> = emptyMap(),

    // Sites
    val sitesById: Map<String, SiteItem> = emptyMap(),
    /** door backend/rights gefilterde set; voorlopig leeg tot veld beschikbaar is */
    val assignedSites: List<String> = emptyList(),

    // Site helpers
    val siteLocationsBySite: Map<String, List<SiteValueItem>> = emptyMap(),
    val siteHeightsBySite: Map<String, List<SiteValueItem>> = emptyMap(),

    // Site-species relaties (optioneel te tonen in UI)
    val siteSpeciesBySite: Map<String, List<SiteSpeciesItem>> = emptyMap(),

    // Protocol
    val protocolsInfo: List<ProtocolInfoItem> = emptyList(),
    val protocolSpeciesByProtocol: Map<String, List<ProtocolSpeciesItem>> = emptyMap(),

    // Codes (generiek; verfijnbaar)
    val codesByCategory: Map<String, List<CodeItem>> = emptyMap()
)
