package com.yvesds.vt5.net

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object StartTellingApi {

    fun buildEnvelopeFromUi(
        tellingId: Long,
        telpostId: String,
        begintijdEpochSec: Long,
        eindtijdEpochSec: Long,
        windrichtingLabel: String?,
        windkrachtBftOnly: String?,
        temperatuurC: String?,
        bewolkingAchtstenOnly: String?,
        neerslagCode: String?,
        zichtMeters: String?,
        typetellingCode: String?,
        telers: String?,
        weerOpmerking: String?,
        opmerkingen: String?,
        luchtdrukHpaRaw: String?
    ): List<ServerTellingEnvelope> {

        fun nowStamp(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        fun digitsOnly(s: String?): String = s?.filter { it.isDigit() } ?: ""

        // eerste 4 digits van HPA als ze er zijn
        val hpa4 = digitsOnly(luchtdrukHpaRaw).let {
            if (it.length >= 4) it.substring(0, 4) else it
        }

        val env = ServerTellingEnvelope(
            externid = "Android App 1.8.45",
            timezoneid = "Europe/Brussels",
            bron = "4",
            idLocal = "",                                // ← was "_id" in JSON; veldnaam in data class = idLocal
            tellingid = tellingId.toString(),
            telpostid = telpostId,
            begintijd = begintijdEpochSec.toString(),
            eindtijd = eindtijdEpochSec.toString(),
            tellers = telers.orEmpty(),
            weer = weerOpmerking.orEmpty(),
            windrichting = (windrichtingLabel ?: "").uppercase(Locale.getDefault()),
            windkracht = (windkrachtBftOnly ?: ""),
            temperatuur = digitsOnly(temperatuurC),
            bewolking = (bewolkingAchtstenOnly ?: ""),
            bewolkinghoogte = "",
            neerslag = neerslagCode.orEmpty(),
            duurneerslag = "",
            zicht = digitsOnly(zichtMeters),
            tellersactief = "",
            tellersaanwezig = "",
            typetelling = typetellingCode.orEmpty(),
            metersnet = "",
            geluid = "",
            opmerkingen = opmerkingen.orEmpty(),
            onlineid = "",
            hydro = "",                                  // ← was "HYDRO" als JSON key; veldnaam in data class = hydro
            hpa = hpa4,
            equipment = "",
            uuid = "VT5_${tellingId}_${UUID.randomUUID()}",
            uploadtijdstip = nowStamp(),
            nrec = "0",
            nsoort = "0",
            data = emptyList()
        )

        return listOf(env)
    }
}
