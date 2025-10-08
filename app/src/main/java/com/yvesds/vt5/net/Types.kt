@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.yvesds.vt5.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerTellingEnvelope(
    @SerialName("externid") val externid: String,
    @SerialName("timezoneid") val timezoneid: String,
    @SerialName("bron") val bron: String,
    @SerialName("_id") val idLocal: String,
    @SerialName("tellingid") val tellingid: String,
    @SerialName("telpostid") val telpostid: String,
    @SerialName("begintijd") val begintijd: String,
    @SerialName("eindtijd") val eindtijd: String,
    @SerialName("tellers") val tellers: String,
    @SerialName("weer") val weer: String,
    @SerialName("windrichting") val windrichting: String,
    @SerialName("windkracht") val windkracht: String,
    @SerialName("temperatuur") val temperatuur: String,
    @SerialName("bewolking") val bewolking: String,
    @SerialName("bewolkinghoogte") val bewolkinghoogte: String,
    @SerialName("neerslag") val neerslag: String,
    @SerialName("duurneerslag") val duurneerslag: String,
    @SerialName("zicht") val zicht: String,
    @SerialName("tellersactief") val tellersactief: String,
    @SerialName("tellersaanwezig") val tellersaanwezig: String,
    @SerialName("typetelling") val typetelling: String,
    @SerialName("metersnet") val metersnet: String,
    @SerialName("geluid") val geluid: String,
    @SerialName("opmerkingen") val opmerkingen: String,
    @SerialName("onlineid") val onlineid: String,
    @SerialName("HYDRO") val hydro: String,
    @SerialName("hpa") val hpa: String,
    @SerialName("equipment") val equipment: String,
    @SerialName("uuid") val uuid: String,
    @SerialName("uploadtijdstip") val uploadtijdstip: String,
    @SerialName("nrec") val nrec: String,
    @SerialName("nsoort") val nsoort: String,
    @SerialName("data") val data: List<ServerTellingDataItem>
)

@Serializable
data class ServerTellingDataItem(
    @SerialName("_id") val idLocal: String = "",
    @SerialName("tellingid") val tellingid: String = "",
    @SerialName("soortid") val soortid: String = "",
    @SerialName("aantal") val aantal: String = "",
    @SerialName("richting") val richting: String = "",
    @SerialName("aantalterug") val aantalterug: String = "",
    @SerialName("richtingterug") val richtingterug: String = "",
    @SerialName("sightingdirection") val sightingdirection: String = "",
    @SerialName("lokaal") val lokaal: String = "",
    @SerialName("aantal_plus") val aantal_plus: String = "",
    @SerialName("aantalterug_plus") val aantalterug_plus: String = "",
    @SerialName("lokaal_plus") val lokaal_plus: String = "",
    @SerialName("markeren") val markeren: String = "",
    @SerialName("markerenlokaal") val markerenlokaal: String = "",
    @SerialName("geslacht") val geslacht: String = "",
    @SerialName("leeftijd") val leeftijd: String = "",
    @SerialName("kleed") val kleed: String = "",
    @SerialName("opmerkingen") val opmerkingen: String = "",
    @SerialName("trektype") val trektype: String = "",
    @SerialName("teltype") val teltype: String = "",
    @SerialName("location") val location: String = "",
    @SerialName("height") val height: String = "",
    @SerialName("tijdstip") val tijdstip: String = "",
    @SerialName("groupid") val groupid: String = "",
    @SerialName("uploadtijdstip") val uploadtijdstip: String = "",
    @SerialName("totaalaantal") val totaalaantal: String = ""
)
