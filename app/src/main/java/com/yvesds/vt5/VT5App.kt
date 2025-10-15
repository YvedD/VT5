package com.yvesds.vt5

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * VT5 – App singleton
 *
 * - Houdt een veilige Application.instance bij
 * - Biedt centrale Json/OkHttp singletons
 * - Biedt nextTellingId() (als String), oplopend en persistent via SharedPreferences
 *
 * Let op: geen zware I/O of DocumentFile calls in onCreate(), om opstart traagheid te vermijden.
 */
class VT5App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Geen zware preloads hier – bewust licht houden voor snellere app-start.
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
            p.edit().putLong(KEY_TELLING_ID, current + 1L).apply()
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
