package com.yvesds.vt5.core.app

import android.content.Context
import android.util.Log
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.speech.MatchLogWriter

/**
 * Centrale, nette afsluiter voor de VT5-app.
 * 
 * Best practices toegepast:
 * - Sluit alle netwerk clients (OkHttp connection pools, executors)
 * - Flush logging/schrijfbuffers
 * - Cancel achtergrond-taken
 * - Voorkomt geheugenlekken door expliciete cleanup
 * - Thread-safe singleton pattern
 * 
 * Gebruik: Roep aan vanuit hoofdactiviteit vóór finishAndRemoveTask()
 */
object AppShutdown {
    private const val TAG = "AppShutdown"
    
    @Volatile
    private var isShuttingDown = false

    /**
     * Voert een volledige, veilige afsluiting van de app uit.
     * Deze methode is idempotent - meerdere aanroepen zijn veilig.
     * 
     * @param context Android context voor eventuele cleanup operaties
     */
    fun shutdownApp(context: Context) {
        // Prevent concurrent shutdown attempts
        synchronized(this) {
            if (isShuttingDown) {
                Log.d(TAG, "Shutdown already in progress, skipping")
                return
            }
            isShuttingDown = true
        }

        Log.i(TAG, "Starting graceful app shutdown")

        try {
            // 1. Stop any pending logs/writes
            try {
                MatchLogWriter.stop()
                Log.d(TAG, "MatchLogWriter stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop MatchLogWriter: ${e.message}", e)
            }

            // 2. Shutdown network clients (closes connection pools, stops executors)
            try {
                TrektellenAuth.shutdown()
                Log.d(TAG, "TrektellenAuth shutdown complete")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to shutdown TrektellenAuth: ${e.message}", e)
            }

            try {
                ServerJsonDownloader.shutdown()
                Log.d(TAG, "ServerJsonDownloader shutdown complete")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to shutdown ServerJsonDownloader: ${e.message}", e)
            }

            // 3. Shutdown shared OkHttp client from VT5App
            try {
                VT5App.http.dispatcher.executorService.shutdown()
                VT5App.http.connectionPool.evictAll()
                VT5App.http.cache?.close()
                Log.d(TAG, "VT5App.http client shutdown complete")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to shutdown VT5App.http: ${e.message}", e)
            }

            // 4. Future expansion points (commented out for now):
            // - Cancel WorkManager jobs: WorkManager.getInstance(context).cancelAllWork()
            // - Stop location updates if active
            // - Release sensor listeners
            // - Clear image caches (if any)
            // - Cancel pending notifications

            Log.i(TAG, "Graceful app shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during app shutdown: ${e.message}", e)
        } finally {
            isShuttingDown = false
        }
    }
}
