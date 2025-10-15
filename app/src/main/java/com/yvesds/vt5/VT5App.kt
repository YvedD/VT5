package com.yvesds.vt5

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository

class VT5App : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        // (optioneel) hier zou je een eenmalige warm-up kunnen triggeren
        // ServerDataRepository.getInstance(applicationContext) // lazy init
    }

    // ---------- Publieke helpers (statics) ----------
    companion object {
        private const val PREFS_NAME = "vt5_app_prefs"
        private const val KEY_NEXT_TELLING_ID = "next_telling_id"
        private const val KEY_META_START_EPOCH = "meta_start_epoch"

        @Volatile
        private lateinit var app: VT5App

        fun ctx(): Context = app.applicationContext

        /** Lazy-gekoppelde repository (singleton). */
        fun repo(): ServerDataRepository = ServerDataRepository.getInstance(ctx())

        /** Unix epoch in seconden. */
        fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L

        /** Haal (en verhoog) het volgende lokale telling-id. Persistenter opslag via SharedPreferences. */
        @Synchronized
        fun nextTellingId(): Long {
            val sp = app.prefs()
            val current = sp.getLong(KEY_NEXT_TELLING_ID, 1L)
            val next = if (current <= 0L) 1L else current + 1L
            sp.edit().putLong(KEY_NEXT_TELLING_ID, next).apply()
            return current
        }

        /**
         * Haal de starttijd (epoch s) van de huidige metadata-sessie.
         * Als die nog niet bestond, wordt hij nu gezet op 'nu' en teruggegeven.
         */
        @Synchronized
        fun getOrInitMetadataStartEpoch(): Long {
            val sp = app.prefs()
            val existing = sp.getLong(KEY_META_START_EPOCH, 0L)
            if (existing > 0L) return existing
            val now = nowEpochSeconds()
            sp.edit().putLong(KEY_META_START_EPOCH, now).apply()
            return now
        }

        /** Handig als je na verzenden van de header opnieuw wil beginnen. */
        @Synchronized
        fun clearMetadataStartEpoch() {
            app.prefs().edit().remove(KEY_META_START_EPOCH).apply()
        }

        /** Optioneel direct zetten (bv. als je zelf het moment bepaalt). */
        @Synchronized
        fun setMetadataStartEpoch(epochSeconds: Long) {
            app.prefs().edit().putLong(KEY_META_START_EPOCH, epochSeconds).apply()
        }
    }

    // ---------- Intern ----------
    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
