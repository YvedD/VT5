/*
 * VT5 - AliasIndexRepository
 *
 * Façade om vanuit UI/UseCases:
 *  - (indien nodig) de index te laten pré-computen via AliasIndexWriter
 *    -> zoekt aliasmapping.csv via SAF (Documents/VT5/assets/) en valt terug op app-assets
 *  - de binaire index off-main te laden
 */

package com.yvesds.vt5.features.alias

import android.content.Context
import com.yvesds.vt5.core.opslag.SaFStorageHelper

class AliasIndexRepository(
    private val appContext: Context
) {

    /**
     * Zorgt dat de index aanwezig is (pré-compute indien nodig) en laadt hem daarna.
     * q: q-gram grootte
     * minhashK: aantal MinHash signatures
     */
    suspend fun ensureAndLoad(
        q: Int = 3,
        minhashK: Int = 64
    ): AliasIndex? {
        val saf = SaFStorageHelper(appContext)
        AliasIndexWriter.ensureComputed(
            context = appContext,
            saf = saf,
            q = q,
            minhashK = minhashK
        )
        return AliasIndexWriter.loadIndexFromBinaries(appContext)
    }

    /** Alleen laden (geen pré-compute). */
    suspend fun loadOnly(): AliasIndex? =
        AliasIndexWriter.loadIndexFromBinaries(appContext)
}
