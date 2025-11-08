package com.yvesds.vt5

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.speech.MatchLogWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

/**
 * VT5 – App singleton
 *
 * - Houdt een veilige Application.instance bij
 * - Biedt centrale Json/OkHttp singletons
 * - Biedt nextTellingId() (als String), oplopend en persistent via SharedPreferences
 * - Proactieve data preloading voor betere app performance
 *
 * Note: Zware dataverwerking gebeurt in een background scope, onzichtbaar voor de gebruiker.
 */
class VT5App : Application() {
    // Speciale scope die blijft bestaan gedurende de hele app-lifecycle
    private val appScope = CoroutineScope(Job() + Dispatchers.IO)
    private val TAG = "VT5App"

    override fun onCreate() {
        super.onCreate()
        try {
            MatchLogWriter.start(this)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to start MatchLogWriter in Application.onCreate: ${ex.message}", ex)
        }
        instance = this
        Log.d(TAG, "VT5App onCreate - initiating background data preload")

        // Preload data in de achtergrond - verhoogt app responsiviteit
        preloadDataAsync()
    }

    /**
     * Start een achtergrond taak om server data te preloaden
     * Dit maakt scherm-transities sneller wanneer de data nodig is
     */
    private fun preloadDataAsync() {
        appScope.launch {
            try {
                Log.d(TAG, "Starting background data preload - coroutine active")
                ServerDataCache.preload(applicationContext)
                Log.d(TAG, "Background data preload complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error during data preloading: ${e.message}", e)
            }
        }
    }

    companion object {
        // ====== App instance ======
        lateinit var instance: VT5App
            private set

        // ====== Prefs ======
        private const val PREFS = "vt5_prefs"
        private const val KEY_TELLING_ID = "telling_id"

        /** Toegang tot app-prefs. */
        fun prefs(): SharedPreferences =
            instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /**
         * Geef volgende telling-id terug als String en verhoog de teller.
         * Thread-safe via @Synchronized.
         */
        @Synchronized
        fun nextTellingId(): String {
            val p = prefs()
            val current = p.getLong(KEY_TELLING_ID, 1L)
            p.edit { putLong(KEY_TELLING_ID, current + 1L) }
            return current.toString()
        }

        // ====== Shared singletons ======
        /** Lenient JSON decoder (ignoreUnknownKeys/explicitNulls=false) */
        val json: Json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }

        /** OkHttp client met bescheiden timeouts. */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}