@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.yvesds.vt5.utils.weather


import kotlinx.serialization.Serializable

/**
 * Minimale Open-Meteo response (alleen wat we gebruiken).
 * Endpoint (voorbeeld):
 * https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&
 *   current=temperature_2m,wind_speed_10m,wind_direction_10m,cloud_cover,pressure_msl,visibility,precipitation&timezone=auto
 */
@Serializable
data class OpenMeteoResponse(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val current: Current? = null
)

@Serializable
data class Current(
    val time: String? = null,
    val temperature_2m: Double? = null,   // °C
    val wind_speed_10m: Double? = null,   // m/s
    val wind_direction_10m: Double? = null, // graden (0..360)
    val cloud_cover: Int? = null,         // %
    val pressure_msl: Double? = null,     // hPa
    val visibility: Int? = null,          // meter
    val precipitation: Double? = null     // mm (actuele intensiteit)
)
