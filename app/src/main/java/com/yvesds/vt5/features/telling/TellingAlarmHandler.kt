package com.yvesds.vt5.features.telling

import android.content.Context
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * TellingAlarmHandler
 * 
 * Beheert het uurlijkse alarm direct binnen TellingScherm.
 * Controleert elke seconde of het de 59ste minuut is en triggert:
 * - Geluid afspelen (bell.mp3 of systeem notificatie)
 * - Vibratie
 * - Callback naar TellingScherm om HuidigeStandScherm te tonen
 * 
 * Dit is een directere en betrouwbaardere aanpak dan de BroadcastReceiver-gebaseerde
 * HourlyAlarmManager, omdat het alarm getriggerd wordt terwijl de Activity actief is.
 */
class TellingAlarmHandler(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TellingAlarmHandler"
        private const val PREFS_NAME = "vt5_prefs"
        private const val PREF_HOURLY_ALARM_ENABLED = "pref_hourly_alarm_enabled"
        private const val CHECK_INTERVAL_MS = 1000L // Controleer elke seconde
        private const val TARGET_MINUTE = 59 // Alarm op de 59ste minuut
    }
    
    // Job voor de periodieke check
    private var alarmCheckJob: Job? = null
    
    // Houdt bij voor welk uur we al een alarm hebben getriggerd (voorkomt dubbele triggers)
    private var lastTriggeredHour: Int = -1
    
    // MediaPlayer voor alarm geluid
    private var mediaPlayer: MediaPlayer? = null
    
    // Callback die wordt aangeroepen wanneer het alarm afgaat
    var onAlarmTriggered: (() -> Unit)? = null
    
    /**
     * Start de periodieke controle op de 59ste minuut.
     * Moet aangeroepen worden in onCreate of onResume van TellingScherm.
     */
    fun startMonitoring() {
        if (alarmCheckJob?.isActive == true) {
            Log.i(TAG, "Alarm monitoring loopt al")
            return
        }
        
        Log.i(TAG, "Start alarm monitoring")
        alarmCheckJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                checkAndTriggerAlarm()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop de periodieke controle.
     * Moet aangeroepen worden in onPause of onDestroy van TellingScherm.
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stop alarm monitoring")
        alarmCheckJob?.cancel()
        alarmCheckJob = null
    }
    
    /**
     * Controleer of het tijd is voor het alarm en trigger indien nodig.
     */
    private suspend fun checkAndTriggerAlarm() {
        if (!isEnabled()) {
            return
        }
        
        val calendar = Calendar.getInstance()
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        
        // Trigger alleen op de 59ste minuut en niet als we dit uur al getriggerd hebben
        if (currentMinute == TARGET_MINUTE && lastTriggeredHour != currentHour) {
            lastTriggeredHour = currentHour
            Log.i(TAG, "Alarm getriggerd om ${calendar.time}")
            
            // Trigger alarm op Main thread
            scope.launch(Dispatchers.Main) {
                triggerAlarm()
            }
        }
    }
    
    /**
     * Trigger het alarm: speel geluid, vibreer, en roep de callback aan.
     */
    private fun triggerAlarm() {
        Log.i(TAG, "Voer alarm uit: geluid + vibratie + HuidigeStandScherm")
        
        // Speel geluid af
        playAlarmSound()
        
        // Vibreer
        vibrate()
        
        // Roep callback aan om HuidigeStandScherm te tonen
        onAlarmTriggered?.invoke()
    }
    
    /**
     * Speelt het alarm geluid af.
     * Gebruikt bell.mp3 uit res/raw, met fallback naar systeem notificatie geluid.
     */
    private fun playAlarmSound() {
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
    private fun vibrate() {
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
     * Controleer of het uurlijkse alarm is ingeschakeld.
     */
    private fun isEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_HOURLY_ALARM_ENABLED, true) // Standaard aan
    }
    
    /**
     * Handmatig het alarm triggeren voor test doeleinden.
     * Dit negeert de 59ste minuut check en triggert direct.
     */
    fun triggerManually() {
        Log.i(TAG, "Alarm handmatig getriggerd")
        scope.launch(Dispatchers.Main) {
            triggerAlarm()
        }
    }
    
    /**
     * Cleanup resources.
     * Moet aangeroepen worden in onDestroy van TellingScherm.
     */
    fun cleanup() {
        stopMonitoring()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
