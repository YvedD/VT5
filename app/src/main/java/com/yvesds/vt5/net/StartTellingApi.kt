package com.yvesds.vt5.net

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bouwt het exacte JSON-body (als String) dat de server verwacht voor /api/counts_save.
 * We gebruiken expres geen project-specifieke data classes om type-mismatches te vermijden.
 */
object StartTelling {

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    }

    /**
     * Bouw het volledige JSON-array (String) met precies één metadata-header en een lege data[].
     *
     * Alle numerieke velden worden als String verstuurd (vereiste van jouw server).
     * - tijdstippen: epoch seconden (als String)
     * - temperatuur: afgerond integer (String)
     * - zicht: meters zonder decimalen (String)
     * - luchtdruk: eerste 4 cijfers (String)
     */
    fun buildJsonEnvelopeFromUi(
        tellingId: Long,
        telpostId: String,
        begintijdEpochSec: Long,
        eindtijdEpochSec: Long,

        // UI-waarden
        windrichtingLabel: String?,          // bv "N","NW","ZO", ...
        windkrachtBftOnly: String?,          // "0".."12" (enkel getal)
        temperatuurC: String?,               // "12.3" of "12" → wordt "12"
        bewolkingAchtstenOnly: String?,      // "0".."8"
        neerslagCode: String?,               // bv "geen","mist","regen",...
        zichtMeters: String?,                // "14600" of "14600.0" → wordt "14600"
        typetellingCode: String?,            // bv "all","sea","raptor",...
        telers: String?,                     // vrije tekst
        weerOpmerking: String?,              // vrije tekst
        opmerkingen: String?,                // vrije tekst
        luchtdrukHpaRaw: String?,            // bv "1013.2" → neem eerste 4 cijfers

        // defaults volgens jouw specs
        externId: String = "Android App 1.8.45",
        timezoneId: String = "Europe/Brussels",
        bron: String = "4",                  // test-bron
        uuid: String = "",                   // optioneel leeg

        uploadNowMillis: Long = System.currentTimeMillis()
    ): String {
        val uploadTime = formatUploadTime(uploadNowMillis) // "yyyy-MM-dd HH:mm:ss"

        // Normaliseer naar pure String-getallen, zonder decimalen.
        val tempStr = temperatuurC?.let { toIntStringOrEmpty(it) } ?: ""
        val zichtStr = zichtMeters?.let { toIntStringOrEmpty(it) } ?: ""
        val hpaStr = luchtdrukHpaRaw?.let { first4Digits(it) } ?: ""

        val payload: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    // Meta
                    put("externid", externId)
                    put("timezoneid", timezoneId)
                    put("bron", bron)

                    // Identifiers (als String)
                    put("_id", "") // leeg bij init
                    put("tellingid", tellingId.toString())
                    put("telpostid", telpostId)

                    // Tijden (epoch sec als String)
                    put("begintijd", begintijdEpochSec.toString())
                    put("eindtijd", eindtijdEpochSec.toString())

                    // Personen en weer
                    put("tellers", telers ?: "")
                    put("weer", weerOpmerking ?: "")

                    // Wind
                    put("windrichting", windrichtingLabel ?: "")
                    put("windkracht", windkrachtBftOnly ?: "")

                    // Temp & wolken & neerslag & zicht
                    put("temperatuur", tempStr)
                    put("bewolking", bewolkingAchtstenOnly ?: "")
                    put("bewolkinghoogte", "")     // n.v.t.
                    put("neerslag", neerslagCode ?: "")
                    put("duurneerslag", "")        // n.v.t.
                    put("zicht", zichtStr)

                    // Aanwezig/actief (n.v.t.)
                    put("tellersactief", "")
                    put("tellersaanwezig", "")

                    // Type telling
                    put("typetelling", typetellingCode ?: "all")

                    // Niet van toepassing in VT5:
                    put("metersnet", "")
                    put("geluid", "")

                    // Vrije opmerkingen
                    put("opmerkingen", opmerkingen ?: "")

                    // Online ID (leeg bij init), HYDRO (n.v.t.), luchtdruk (hpa), materiaal (n.v.t.)
                    put("onlineid", "")
                    put("HYDRO", "")
                    put("hpa", hpaStr)
                    put("equipment", "")

                    // UUID + uploadtijdstip
                    put("uuid", uuid)
                    put("uploadtijdstip", uploadTime)

                    // Voor later (nu leeg/0)
                    put("nrec", "0")
                    put("nsoort", "0")

                    // lege data array (telling starten)
                    put("data", buildJsonArray { /* leeg */ })
                }
            )
        }

        // encode JsonArray → String
        return json.encodeToString(payload)
    }

    private fun formatUploadTime(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(millis))
    }

    /** "12.9" → "13", "12" → "12", "12.0" → "12", "abc" → "" */
    private fun toIntStringOrEmpty(raw: String): String {
        val d = raw.replace(',', '.').toDoubleOrNull() ?: return digitsOnly(raw)
        return d.roundToInt().toString()
    }

    /** "14600.0" → "14600", " 14 600 " → "14600", "abc123" → "123" */
    private fun digitsOnly(raw: String): String {
        val s = raw.trim()
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch.isDigit()) sb.append(ch)
        return sb.toString()
    }

    /** "1013.8" → "1013" ; "1018" → "1018" ; "101" → "101" ; "abc1013" → "1013" */
    private fun first4Digits(raw: String): String {
        val d = digitsOnly(raw)
        return if (d.length <= 4) d else d.substring(0, 4)
    }
}
