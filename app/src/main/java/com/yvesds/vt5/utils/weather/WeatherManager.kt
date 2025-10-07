package com.yvesds.vt5.utils.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Weer-helper: last known location, fetchen van huidige weerdata (Open-Meteo),
 * en conversies voor UI (bewolking in achtsten, zicht in meters, enz.).
 *
 * Verwacht WeatherResponse.kt met data classes WeatherResponse en Current.
 */
object WeatherManager {

    private val client by lazy { OkHttpClient() }
    private val json by lazy { Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true } }

    /** Probeer snel een lastKnownLocation te pakken (NETWORK dan GPS). */
    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(ctx: Context): Location? = withContext(Dispatchers.IO) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }
        best
    }

    /** Haal 'current' set op bij Open-Meteo (m/s voor wind). */
    suspend fun fetchCurrent(lat: Double, lon: Double): Current? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,wind_speed_10m,wind_direction_10m,cloud_cover,pressure_msl,visibility,precipitation" +
                "&windspeed_unit=ms" +              // 👈 m/s expliciet
                "&timezone=auto"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            val wr = json.decodeFromString(WeatherResponse.serializer(), body)
            wr.current
        }
    }

    /** Converteer m/s naar Beaufort 0..12 (klassieke tabel). */
    fun msToBeaufort(ms: Double?): Int {
        val v = ms ?: return 0
        // m/s bovengrenzen per Beaufort
        val thresholds = listOf(0.2, 1.5, 3.3, 5.4, 7.9, 10.7, 13.8, 17.1, 20.7, 24.4, 28.4, 32.6)
        for ((i, t) in thresholds.withIndex()) if (v <= t) return i
        return 12
    }

    /** Converteer graden naar 16-windroos label. */
    fun degTo16WindLabel(deg: Double?): String {
        if (deg == null) return "N"
        val labels = arrayOf("N","NNO","NO","ONO","O","OZO","ZO","ZZO","Z","ZZW","ZW","WZW","W","WNW","NW","NNW")
        val idx = floor(((deg + 11.25) % 360.0) / 22.5).toInt()
        return labels[idx.coerceIn(0, labels.lastIndex)]
    }

    /** Converteer cloud cover (%) → achtsten ("0".."8"). */
    fun cloudPercentToAchtsten(pct: Double?): String {
        val p = ((pct ?: 0.0).coerceIn(0.0, 100.0))
        val achtsten = ((p / 100.0) * 8.0).roundToInt().coerceIn(0, 8)
        return achtsten.toString()
    }

    /**
     * Normaliseer zichtbaarheid naar METERS (Int, geen decimalen).
     * Heuristiek:
     *  - < 1000 -> interpreteer als kilometers (bv. 14.6) → ×1000 en afronden.
     *  - anders -> interpreteer als meters (bv. 14600.0) → afronden.
     */
    fun toVisibilityMeters(value: Double?): Int? {
        value ?: return null
        return if (value < 1000.0) {
            (value * 1000.0).roundToInt()
        } else {
            value.roundToInt()
        }
    }

    /** Simple logic voor neerslag-type o.b.v. intensiteit; fallback "geen". */
    fun precipitationToCode(precipMm: Double?): String {
        val v = (precipMm ?: 0.0)
        return when {
            v < 0.05 -> "geen"
            v < 0.5  -> "motregen"
            else     -> "regen"
        }
    }
}
