package com.yvesds.vt5.core.app

import android.content.Context
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * AlarmTestHelper
 * 
 * Hulpklasse voor het testen van het uurlijkse alarm systeem.
 * Kan gebruikt worden in debug builds of in een test/instellingen scherm.
 * 
 * Nu direct alarm geluid en vibratie afspelen (ipv via broadcast),
 * zodat het correct werkt vanuit HoofdActiviteit.
 */
object AlarmTestHelper {
    private const val TAG = "AlarmTestHelper"
    
    // MediaPlayer voor alarm geluid
    private var mediaPlayer: MediaPlayer? = null
    
    /**
     * Triggert het alarm handmatig voor test doeleinden.
     * Speelt direct geluid af en vibreert (geen broadcast nodig).
     */
    fun triggerAlarmManually(context: Context) {
        Log.i(TAG, "Test alarm handmatig getriggerd")
        playAlarmSound(context)
        vibrate(context)
    }
    
    /**
     * Speelt het alarm geluid af.
     * Gebruikt bell.mp3 uit res/raw, met fallback naar systeem notificatie geluid.
     */
    private fun playAlarmSound(context: Context) {
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
                    mp.release()
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    mp.release()
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
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
    
    /**
     * Laat het apparaat vibreren.
     * Gebruikt VibratorManager (API 31+) aangezien minSdk = 33.
     */
    private fun vibrate(context: Context) {
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
     * Geeft informatie over de huidige alarm status.
     */
    fun getAlarmStatus(context: Context): String {
        val enabled = HourlyAlarmManager.isEnabled(context)
        return if (enabled) {
            "Uurlijks alarm is INGESCHAKELD\nVolgende alarm: 59ste minuut van dit of volgend uur"
        } else {
            "Uurlijks alarm is UITGESCHAKELD"
        }
    }
    
    /**
     * Reset het alarm (herbeplant het volgende alarm).
     * Nuttig als het alarm om een of andere reden niet meer werkt.
     */
    fun resetAlarm(context: Context) {
        try {
            HourlyAlarmManager.scheduleNextAlarm(context)
            Log.i(TAG, "Alarm herstart/reset uitgevoerd")
        } catch (e: Exception) {
            Log.e(TAG, "Fout bij resetten alarm: ${e.message}", e)
        }
    }
    
    /**
     * Verificatie functie om te controleren of alles correct is ingesteld.
     * Returns een lijst met potentiële problemen, of een lege lijst als alles OK is.
     */
    fun verifySetup(context: Context): List<String> {
        val issues = mutableListOf<String>()
        
        // Check alarm status
        val enabled = HourlyAlarmManager.isEnabled(context)
        if (!enabled) {
            issues.add("⚠️ Alarm is momenteel uitgeschakeld")
        }
        
        // Check permissies
        val manifestExists = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
        
        if (!manifestExists) {
            issues.add("❌ Kan package info niet ophalen")
        }
        
        // Check of bell.mp3 beschikbaar is
        try {
            val resources = context.resources
            val bellId = resources.getIdentifier("bell", "raw", context.packageName)
            if (bellId != 0) {
                issues.add("ℹ️ Gebruikt bell.mp3 voor alarm geluid")
            } else {
                issues.add("ℹ️ bell.mp3 niet gevonden, gebruikt systeem notificatie geluid")
            }
        } catch (e: Exception) {
            issues.add("ℹ️ Kan raw resources niet controleren, gebruikt systeem notificatie geluid")
        }
        
        return if (issues.isEmpty()) {
            listOf("✅ Alarm systeem is correct geconfigureerd")
        } else {
            issues
        }
    }
}
