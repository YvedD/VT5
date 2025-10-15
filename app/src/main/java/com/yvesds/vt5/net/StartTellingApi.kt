@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.yvesds.vt5.net

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bouwt het envelop-array (List<ServerTellingEnvelope>) voor counts_save.
 * Alle numerieke waarden worden als String gezet (server verwacht strings).
 */
object StartTellingApi {

    fun buildEnvelopeFromUi(
        tellingId: Long,
        telpostId: String,
        begintijdEpochSec: Long,
        eindtijdEpochSec: Long,
        windrichtingLabel: String?,
        windkrachtBftOnly: String?,        // "0".."12"
        temperatuurC: String?,             // int als string
        bewolkingAchtstenOnly: String?,    // "0".."8"
        neerslagCode: String?,             // bv "regen"
        zichtMeters: String?,              // int als string
        typetellingCode: String?,          // bv "all"
        telers: String?,
        weerOpmerking: String?,
        opmerkingen: String?,
        luchtdrukHpaRaw: String?           // bv "1013"
    ): List<ServerTellingEnvelope> {

        val nowStr = nowAsSqlLike()
        val externId = "Android App 1.8.45"  // conform voorbeeld
        val timezone = "Europe/Brussels"
        val bron = "4"                        // test bron

        val windkracht = (windkrachtBftOnly ?: "").ifEmpty { "" }
        val temperatuur = (temperatuurC ?: "").ifEmpty { "" }
        val bewolking = (bewolkingAchtstenOnly ?: "").ifEmpty { "" }
        val zicht = (zichtMeters ?: "").ifEmpty { "" }
        val hpa = (luchtdrukHpaRaw ?: "").ifEmpty { "" }
        val typetelling = (typetellingCode ?: "all")

        val env = ServerTellingEnvelope(
            externid = externId,
            timezoneid = timezone,
            bron = bron,
            idLocal = "",                     // "_id" leeg
            tellingid = tellingId.toString(),
            telpostid = telpostId,
            begintijd = begintijdEpochSec.toString(),
            eindtijd = eindtijdEpochSec.toString(),
            tellers = telers ?: "",
            weer = weerOpmerking ?: "",
            windrichting = windrichtingLabel ?: "",
            windkracht = windkracht,
            temperatuur = temperatuur,
            bewolking = bewolking,
            bewolkinghoogte = "",
            neerslag = neerslagCode ?: "",
            duurneerslag = "",
            zicht = zicht,
            tellersactief = "",
            tellersaanwezig = "",
            typetelling = typetelling,
            metersnet = "",
            geluid = "",
            opmerkingen = opmerkingen ?: "",
            onlineid = "",                    // server vult terug
            hydro = "",                       // "HYDRO" → "hydro"
            hpa = hpa,
            equipment = "",
            uuid = "Trektellen_Android_1.8.45_${java.util.UUID.randomUUID()}",
            uploadtijdstip = nowStr,
            nrec = "0",
            nsoort = "0",
            data = emptyList()                // lege data bij start
        )

        return listOf(env)
    }

    private fun nowAsSqlLike(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }
}


