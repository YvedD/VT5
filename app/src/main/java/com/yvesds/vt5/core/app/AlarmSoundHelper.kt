package com.yvesds.vt5.core.app

import android.content.Context
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * AlarmSoundHelper
 * 
 * Gedeelde hulpklasse voor het afspelen van alarm geluiden en vibratie.
 * Gebruikt door zowel TellingAlarmHandler als AlarmTestHelper.
 */
object AlarmSoundHelper {
    private const val TAG = "AlarmSoundHelper"
    
    // Synchronized lock for MediaPlayer operations
    private val mediaPlayerLock = Any()
    
    // MediaPlayer voor alarm geluid
    private var mediaPlayer: MediaPlayer? = null
    
    /**
     * Speelt het alarm geluid af.
     * Gebruikt bell.mp3 uit res/raw, met fallback naar systeem notificatie geluid.
     * Thread-safe door synchronized block.
     */
    fun playAlarmSound(context: Context) {
        synchronized(mediaPlayerLock) {
            try {
                // Release vorige MediaPlayer indien aanwezig
                mediaPlayer?.release()
                mediaPlayer = null
                
                // Probeer eerst bell.mp3 uit res/raw
                var player: MediaPlayer? = null
                try {
                    val bellResourceId = context.resources.getIdentifier("bell", "raw", context.packageName)
                    if (bellResourceId != 0) {
                        player = MediaPlayer.create(context, bellResourceId)
                        Log.i(TAG, "Gebruik bell.mp3 voor alarm geluid")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bell.mp3 niet gevonden, gebruik systeem notificatie: ${e.message}")
                }
                
                // Fallback naar systeem notificatie geluid
                if (player == null) {
                    player = MediaPlayer.create(
                        context,
                        android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
                    )
                    Log.i(TAG, "Gebruik systeem notificatie geluid")
                }
                
                player?.apply {
                    setOnCompletionListener { mp ->
                        synchronized(mediaPlayerLock) {
                            mp.release()
                            if (mediaPlayer === mp) {
                                mediaPlayer = null
                            }
                        }
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        synchronized(mediaPlayerLock) {
                            mp.release()
                            if (mediaPlayer === mp) {
                                mediaPlayer = null
                            }
                        }
                        true
                    }
                    start()
                    mediaPlayer = this
                    Log.i(TAG, "Alarm geluid gestart")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fout bij afspelen alarm geluid: ${e.message}", e)
            }
        }
    }
    
    /**
     * Laat het apparaat vibreren.
     * Gebruikt VibratorManager (API 31+) aangezien minSdk = 33.
     */
    fun vibrate(context: Context) {
        try {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            Log.i(TAG, "Vibratie getriggerd")
        } catch (e: Exception) {
            Log.e(TAG, "Fout bij vibreren: ${e.message}", e)
        }
    }
    
    /**
     * Cleanup - release MediaPlayer resources.
     * Aangeroepen bij app shutdown.
     */
    fun cleanup() {
        synchronized(mediaPlayerLock) {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
}
