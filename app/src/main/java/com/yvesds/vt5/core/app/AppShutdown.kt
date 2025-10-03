package com.yvesds.vt5.core.app

import android.content.Context
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth

/**
 * Centrale, nette afsluiter:
 * - sluit OkHttp executors/connection pools
 * - plaats voor toekomstige cleanup (caches, schedulers, etc.)
 */
object AppShutdown {

    fun shutdownApp(@Suppress("UNUSED_PARAMETER") context: Context) {
        // Netwerkclients opruimen
        TrektellenAuth.shutdown()
        ServerJsonDownloader.shutdown()

        // Hier kun je later nog:
        // - lokale caches flushen
        // - geplande taken annuleren
        // - sensoren/locatie stoppen
    }
}
