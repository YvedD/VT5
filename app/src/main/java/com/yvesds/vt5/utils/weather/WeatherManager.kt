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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

object WeatherManager {

    private val client by lazy { OkHttpClient() }
    private val json by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

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

    /** Haal de 'current' set op bij Open-Meteo (geen API key nodig). */
    suspend fun fetchCurrent(lat: Double, lon: Double): Current? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${lat}&longitude=${lon}" +
                "&current=temperature_2m,wind_speed_10m,wind_direction_10m,cloud_cover,pressure_msl,visibility,precipitation" +
                "&timezone=auto"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            val om = json.decodeFromString(OpenMeteoResponse.serializer(), body)
            om.current
        }
    }

    /** Converteer m/s naar Beaufort 0..12 (klasieke tabel). */
    fun msToBeaufort(ms: Double?): Int {
        val v = ms ?: return 0
        // grenzen in m/s (inclusief bovengrens)
        val thresholds = listOf(
            0.2, 1.5, 3.3, 5.4, 7.9, 10.7, 13.8, 17.1, 20.7, 24.4, 28.4, 32.6
        )
        for ((i, t) in thresholds.withIndex()) if (v <= t) return i
        return 12
    }

    /** Converteer graden naar 16-windroos label ("N","NNO","NO","ONO","O","OZO","ZO","ZZO","Z","ZZW","ZW","WZW","W","WNW","NW","NNW"). */
    fun degTo16WindLabel(deg: Double?): String {
        if (deg == null) return "N"
        val labels = arrayOf(
            "N","NNO","NO","ONO","O","OZO","ZO","ZZO","Z","ZZW","ZW","WZW","W","WNW","NW","NNW"
        )
        // sectorbreedte 22.5°, offset zodat 0° → "N"
        val idx = floor(((deg + 11.25) % 360) / 22.5).toInt()
        return labels[idx.coerceIn(0, labels.lastIndex)]
    }

    /** Converteer cloud cover (%) → achtsten ("0".."8"). */
    fun cloudPercentToAchtsten(pct: Int?): String {
        val p = (pct ?: 0).coerceIn(0, 100)
        val achtsten = ((p / 100.0) * 8.0).roundToInt().coerceIn(0, 8)
        return achtsten.toString()
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
