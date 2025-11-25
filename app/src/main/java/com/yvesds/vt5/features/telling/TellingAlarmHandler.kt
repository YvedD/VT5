package com.yvesds.vt5.features.telling

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.app.AlarmSoundHelper
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
 * Controleert op de 59ste minuut en triggert:
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
        private const val TARGET_MINUTE = 59 // Alarm op de 59ste minuut
    }
    
    // Job voor de periodieke check
    private var alarmCheckJob: Job? = null
    
    // Houdt bij voor welk uur we al een alarm hebben getriggerd (voorkomt dubbele triggers)
    private var lastTriggeredHour: Int = -1
    
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
                val delayMs = checkAndTriggerAlarm()
                delay(delayMs)
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
     * 
     * @return milliseconden om te wachten tot de volgende check.
     *         Optimaliseert door te wachten tot vlak voor de volgende minuutwisseling.
     */
    private suspend fun checkAndTriggerAlarm(): Long {
        if (!isEnabled()) {
            // Als alarm uitgeschakeld, check elke 30 seconden of het weer ingeschakeld is
            return 30_000L
        }
        
        val calendar = Calendar.getInstance()
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentSecond = calendar.get(Calendar.SECOND)
        
        // Trigger alleen op de 59ste minuut en niet als we dit uur al getriggerd hebben
        if (currentMinute == TARGET_MINUTE && lastTriggeredHour != currentHour) {
            lastTriggeredHour = currentHour
            Log.i(TAG, "Alarm getriggerd om ${calendar.time}")
            
            // Trigger alarm op Main thread
            scope.launch(Dispatchers.Main) {
                triggerAlarm()
            }
        }
        
        // Bereken optimale wachttijd tot de volgende check
        return calculateDelayUntilNextCheck(currentMinute, currentSecond)
    }
    
    /**
     * Bereken hoeveel milliseconden te wachten tot de volgende check.
     * Als we niet op minuut 59 zijn, wacht tot vlak voor minuut 59.
     * Als we op minuut 59 zijn, wacht tot het volgende uur begint.
     */
    private fun calculateDelayUntilNextCheck(currentMinute: Int, currentSecond: Int): Long {
        return when {
            currentMinute < TARGET_MINUTE -> {
                // Wacht tot ~2 seconden voor minuut 59 (om marge te houden)
                val minutesToWait = TARGET_MINUTE - currentMinute - 1
                val secondsToWait = (60 - currentSecond) + (minutesToWait * 60)
                (secondsToWait * 1000L).coerceAtLeast(1000L)
            }
            currentMinute == TARGET_MINUTE -> {
                // Op minuut 59: wacht tot het volgende uur begint
                val secondsToWait = 60 - currentSecond + 5 // +5 voor marge
                (secondsToWait * 1000L).coerceAtLeast(1000L)
            }
            else -> {
                // Na minuut 59 (minuut 0-58 van het volgende cyclus)
                // Dit zou niet moeten gebeuren, maar voor de zekerheid
                val minutesToWait = 60 - currentMinute + TARGET_MINUTE - 1
                val secondsToWait = (60 - currentSecond) + (minutesToWait * 60)
                (secondsToWait * 1000L).coerceAtLeast(1000L)
            }
        }
    }
    
    /**
     * Trigger het alarm: speel geluid, vibreer, en roep de callback aan.
     */
    private fun triggerAlarm() {
        Log.i(TAG, "Voer alarm uit: geluid + vibratie + HuidigeStandScherm")
        
        // Speel geluid af en vibreer via gedeelde helper
        AlarmSoundHelper.playAlarmSound(context)
        AlarmSoundHelper.vibrate(context)
        
        // Roep callback aan om HuidigeStandScherm te tonen
        onAlarmTriggered?.invoke()
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
    }
}
