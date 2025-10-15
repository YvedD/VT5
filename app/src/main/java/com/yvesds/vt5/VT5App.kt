@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.yvesds.vt5.features.serverdata.model.CodeItem
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class VT5App : Application() {

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "vt5_prefs"
        private lateinit var prefs: SharedPreferences

        fun prefs(): SharedPreferences = prefs

        // ====== Telling-id generatie ======
        private const val KEY_NEXT_TELLING_ID = "next_telling_id"

        @Synchronized
        fun nextTellingId(): Long {
            val id = prefs.getLong(KEY_NEXT_TELLING_ID, 1L)
            prefs.edit().putLong(KEY_NEXT_TELLING_ID, id + 1L).apply()
            return id
        }

        // ====== Metadata start epoch (optioneel) ======
        private const val KEY_META_START_EPOCH = "meta_start_epoch"

        fun setMetaStartEpoch(epochSec: Long) {
            prefs.edit().putLong(KEY_META_START_EPOCH, epochSec).apply()
        }

        fun getMetaStartEpoch(): Long =
            prefs.getLong(KEY_META_START_EPOCH, System.currentTimeMillis() / 1000L)

        // ====== Codes cache (RAM, sessie) ======
        private val codesCache = AtomicReference<Map<String, List<CodeItem>>>(emptyMap())
        private val codesLastModified = AtomicLong(-1L)

        /**
         * Zorgt ervoor dat codes exact 1x (of wanneer gewijzigd op schijf) geladen worden.
         * - Single-pass read (bin/json)
         * - In RAM gecachet per sessie
         * - LastModified vergeleken met disk om bij wijzigingen te refreshen
         */
        suspend fun getCodesMapOnce(ctx: Context, repo: ServerDataRepository): Map<String, List<CodeItem>> {
            return withContext(Dispatchers.IO) {
                val (loadedMap, lm) = repo.loadCodesAllOnceFast()
                val currentLm = codesLastModified.get()
                if (lm != currentLm || codesCache.get().isEmpty()) {
                    codesCache.set(loadedMap)
                    codesLastModified.set(lm)
                }
                codesCache.get()
            }
        }
    }
}
