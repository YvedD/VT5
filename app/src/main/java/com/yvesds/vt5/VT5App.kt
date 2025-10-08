package com.yvesds.vt5

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class VT5App : Application() {

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "vt5_prefs"
        private const val KEY_TELLING_ID = "key_telling_id"

        private lateinit var prefs: SharedPreferences

        /** Ophalen huidig tellingid zonder te verhogen. */
        fun peekTellingId(): Int = prefs.getInt(KEY_TELLING_ID, 1)

        /** Verhoog en retourneer nieuw tellingid. */
        fun nextTellingId(): Int {
            val next = prefs.getInt(KEY_TELLING_ID, 1) + 1
            prefs.edit().putInt(KEY_TELLING_ID, next).apply()
            return next
        }

        /** Optioneel: op een specifiek startgetal zetten. */
        fun setTellingId(value: Int) {
            prefs.edit().putInt(KEY_TELLING_ID, value.coerceAtLeast(1)).apply()
        }
    }
}
