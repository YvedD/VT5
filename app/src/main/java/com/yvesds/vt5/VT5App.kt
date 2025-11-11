package com.yvesds.vt5

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.speech.MatchLogWriter
import com.yvesds.vt5.features.speech.AliasMatcher
import com.yvesds.vt5.features.alias.AliasManager
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        // Best-effort: preload alias indexes so first recognitions don't block on CBOR/SAN load.
        // We deliberately split IO checks and CPU-bound work:
        //  - small SAF existence checks run on IO
        //  - heavy index building / CPU work runs on Default
        appScope.launch {
            try {
                val saf = SaFStorageHelper(applicationContext)
                // Quick IO check: is Documents/VT5 present? (run on IO)
                val vt5Exists = withContext(Dispatchers.IO) { saf.getVt5DirIfExists() != null }

                // Run index preloads on Default so CPU-bound work uses Default threads.
                // Note: ensureLoaded / ensureIndexLoadedSuspend internally use appropriate dispatchers
                // for their IO vs CPU phases; calling from Default ensures CPU phases run on Default.
                withContext(Dispatchers.Default) {
                    try {
                        AliasMatcher.ensureLoaded(applicationContext, saf)
                        Log.d(TAG, "AliasMatcher.ensureLoaded: background preload complete")
                    } catch (ex: Exception) {
                        Log.w(TAG, "AliasMatcher.ensureLoaded failed (background): ${ex.message}", ex)
                    }

                    try {
                        // AliasManager.ensureIndexLoadedSuspend does IO & CPU; calling from Default lets its CPU parts run there.
                        AliasManager.ensureIndexLoadedSuspend(applicationContext, saf)
                        Log.d(TAG, "AliasManager.ensureIndexLoadedSuspend: background preload complete")
                    } catch (ex: Exception) {
                        Log.w(TAG, "AliasManager.ensureIndexLoadedSuspend failed (background): ${ex.message}", ex)
                    }
                }

                // If vt5 wasn't present we still tried the safe preloads; nothing to block startup on.
                if (!vt5Exists) {
                    Log.d(TAG, "VT5 SAF root not present during preload; preloads attempted best-effort")
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Background alias index preload skipped: ${ex.message}", ex)
            }
        }
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