package com.yvesds.vt5.features.metadata.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.utils.weather.WeatherManager
import com.yvesds.vt5.utils.weather.Current
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * WeatherDataFetcher: Handles weather data fetching and UI population.
 * 
 * Responsibilities:
 * - Check location permissions
 * - Fetch current weather data
 * - Map weather data to UI fields
 * - Build weather summary for remarks
 * 
 * This separates weather-specific logic from form management.
 */
class WeatherDataFetcher(
    private val context: Context,
    private val binding: SchermMetadataBinding,
    private val formManager: MetadataFormManager
) {
    
    companion object {
        private const val TAG = "WeatherDataFetcher"
    }
    
    /**
     * Check if location permissions are granted.
     */
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val coarse = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        return fine || coarse
    }
    
    /**
     * Fetch weather data and populate form fields.
     * Returns true if successful, false otherwise.
     */
    suspend fun fetchAndApplyWeather(snapshot: DataSnapshot): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1) Get location
            val loc = WeatherManager.getLastKnownLocation(context)
            if (loc == null) {
                Log.w(TAG, "No location available")
                return@withContext false
            }
            
            // 2) Fetch current weather
            val cur = WeatherManager.fetchCurrent(loc.latitude, loc.longitude)
            if (cur == null) {
                Log.w(TAG, "Weather fetch failed")
                return@withContext false
            }
            
            // 3) Map to UI fields
            withContext(Dispatchers.Main) {
                applyWeatherToForm(cur, snapshot)
            }
            
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndApplyWeather failed: ${e.message}", e)
            return@withContext false
        }
    }
    
    private fun applyWeatherToForm(
        cur: Current,
        snapshot: DataSnapshot
    ) {
        // Wind direction
        val windLabel = WeatherManager.degTo16WindLabel(cur.windDirection10m)
        val windCodes = snapshot.codesByCategory["wind"].orEmpty()
        val windLabelLower = windLabel.lowercase(Locale.getDefault())
        val codeForLabel = windCodes.find { it.value == windLabelLower } 
            ?: windCodes.find { it.value == "n" } // fallback to North
        
        formManager.gekozenWindrichtingCode = codeForLabel?.value ?: "n"
        binding.acWindrichting.setText(codeForLabel?.text ?: "Noord", false)
        
        // Wind force (Beaufort)
        val bft = WeatherManager.msToBeaufort(cur.windSpeed10m)
        formManager.gekozenWindkracht = bft.toString()
        val windForceDisplay = if (bft == 0) "<1bf" else "${bft}bf"
        binding.acWindkracht.setText(windForceDisplay, false)
        
        // Cloud cover (octants)
        val achtsten = WeatherManager.cloudPercentToAchtsten(cur.cloudCover)
        formManager.gekozenBewolking = achtsten
        binding.acBewolking.setText("$achtsten/8", false)
        
        // Precipitation
        val rainCode = WeatherManager.precipitationToCode(cur.precipitation)
        formManager.gekozenNeerslagCode = rainCode
        val rainCodes = snapshot.codesByCategory["neerslag"].orEmpty()
        val rainLabelByValue = rainCodes.associateBy({ it.value }, { it.text })
        val rainLabel = rainLabelByValue[rainCode] ?: rainCode
        binding.acNeerslag.setText(rainLabel, false)
        
        // Temperature
        cur.temperature2m?.let { 
            binding.etTemperatuur.setText(it.roundToInt().toString()) 
        }
        
        // Visibility
        val visMeters = WeatherManager.toVisibilityMeters(cur.visibility)
        visMeters?.let { 
            binding.etZicht.setText(it.toString()) 
        }
        
        // Pressure
        cur.pressureMsl?.let { 
            binding.etLuchtdruk.setText(it.roundToInt().toString()) 
        }
        
        // Keep weather remarks field (etWeerOpmerking) empty for manual input
        // Removed auto-generated summary as per requirement
        
        markWeatherAutoApplied()
    }
    
    private fun markWeatherAutoApplied() {
        // Only change color to indicate weather was fetched, but keep button enabled
        // so user can always fetch fresh weather data (especially for vervolgtelling)
        binding.btnWeerAuto.backgroundTintList = ColorStateList.valueOf("#117CAF".toColorInt())
    }
}
