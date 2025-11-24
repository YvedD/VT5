package com.yvesds.vt5.core.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.edit
import com.yvesds.vt5.R
import com.yvesds.vt5.features.telling.HuidigeStandScherm
import com.yvesds.vt5.features.telling.TellingScherm
import java.util.Calendar

/**
 * HourlyAlarmManager
 * 
 * Beheert een alarm dat elk uur op de 59ste minuut afgaat.
 * Speelt een geluid af en toont optioneel het HuidigeStandScherm als een telling actief is.
 * 
 * Features:
 * - Exacte alarm scheduling op 59ste minuut van elk uur
 * - Configureerbaar via SharedPreferences (aan/uit)
 * - Geluid + vibratie
 * - Automatische integratie met actieve tellingen
 */
object HourlyAlarmManager {
    private const val TAG = "HourlyAlarmManager"
    
    // SharedPreferences keys
    private const val PREFS_NAME = "vt5_prefs"
    private const val PREF_HOURLY_ALARM_ENABLED = "pref_hourly_alarm_enabled"
    private const val PREF_TELLING_ID = "pref_telling_id"
    
    // Request code for PendingIntent
    private const val ALARM_REQUEST_CODE = 1001
    
    /**
     * Controleert of het uurlijkse alarm is ingeschakeld
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_HOURLY_ALARM_ENABLED, true) // Standaard aan
    }
    
    /**
     * Schakelt het uurlijkse alarm in of uit
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean(PREF_HOURLY_ALARM_ENABLED, enabled)
        }
        
        if (enabled) {
            scheduleNextAlarm(context)
            Log.i(TAG, "Uurlijks alarm ingeschakeld")
        } else {
            cancelAlarm(context)
            Log.i(TAG, "Uurlijks alarm uitgeschakeld")
        }
    }
    
    /**
     * Plant het volgende alarm op de 59ste minuut van het huidige of volgende uur
     */
    fun scheduleNextAlarm(context: Context) {
        if (!isEnabled(context)) {
            Log.d(TAG, "Alarm is uitgeschakeld, overslaan scheduling")
            return
        }
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, HourlyAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Bereken de volgende 59ste minuut
        val calendar = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.MINUTE, 59)
            
            // Als we al voorbij de 59ste minuut zijn, ga naar het volgende uur
            if (before(Calendar.getInstance())) {
                add(Calendar.HOUR_OF_DAY, 1)
            }
        }
        
        val triggerTime = calendar.timeInMillis
        
        // Gebruik exact alarm voor betrouwbare timing
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            
            Log.i(TAG, "Volgend alarm gepland voor: ${calendar.time}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Geen toestemming voor exacte alarms: ${e.message}", e)
        }
    }
    
    /**
     * Annuleert het geplande alarm
     */
    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, HourlyAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Alarm geannuleerd")
    }
    
    /**
     * Controleert of een telling momenteel actief is
     */
    private fun isTellingActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tellingId = prefs.getString(PREF_TELLING_ID, null)
        return !tellingId.isNullOrEmpty()
    }
    
    /**
     * BroadcastReceiver die het alarm afhandelt
     */
    class HourlyAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Uurlijks alarm ontvangen")
            
            // Speel geluid en vibreer
            playAlarmSound(context)
            vibrate(context)
            
            // Als een telling actief is, toon HuidigeStandScherm
            if (isTellingActive(context)) {
                showHuidigeStandScherm(context)
            }
            
            // Plan het volgende alarm
            scheduleNextAlarm(context)
        }
        
        /**
         * Speelt het alarm geluid af
         */
        private fun playAlarmSound(context: Context) {
            try {
                // Probeer eerst custom alarm geluid
                var mediaPlayer: MediaPlayer? = null
                try {
                    mediaPlayer = MediaPlayer.create(context, R.raw.hourly_alarm)
                } catch (e: Exception) {
                    Log.w(TAG, "Custom alarm geluid niet gevonden, gebruik systeem notificatie: ${e.message}")
                }
                
                // Fallback naar systeem notificatie geluid
                if (mediaPlayer == null) {
                    mediaPlayer = MediaPlayer.create(
                        context,
                        android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
                    )
                }
                
                mediaPlayer?.apply {
                    setOnCompletionListener { mp ->
                        mp.release()
                    }
                    start()
                }
                Log.d(TAG, "Alarm geluid afgespeeld")
            } catch (e: Exception) {
                Log.e(TAG, "Fout bij afspelen alarm geluid: ${e.message}", e)
            }
        }
        
        /**
         * Laat het apparaat vibreren
         */
        private fun vibrate(context: Context) {
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
                Log.d(TAG, "Vibratie uitgevoerd")
            } catch (e: Exception) {
                Log.e(TAG, "Fout bij vibreren: ${e.message}", e)
            }
        }
        
        /**
         * Toont het HuidigeStandScherm met de huidige telling data
         * 
         * Deze functie brengt TellingScherm naar de voorgrond en triggert
         * het tonen van HuidigeStandScherm via een speciale Intent extra.
         */
        private fun showHuidigeStandScherm(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val tellingId = prefs.getString(PREF_TELLING_ID, null)
                
                if (tellingId.isNullOrEmpty()) {
                    Log.w(TAG, "Geen actieve telling gevonden")
                    return
                }
                
                // Breng TellingScherm naar voren met een speciale flag
                // TellingScherm's onNewIntent zal dit oppakken en HuidigeStandScherm tonen
                val intent = Intent(context, com.yvesds.vt5.features.telling.TellingScherm::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                           Intent.FLAG_ACTIVITY_SINGLE_TOP or
                           Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("SHOW_HUIDIGE_STAND", true)
                }
                
                context.startActivity(intent)
                Log.i(TAG, "TellingScherm naar voorgrond gebracht met HuidigeStand trigger")
            } catch (e: Exception) {
                Log.e(TAG, "Fout bij tonen van telling scherm: ${e.message}", e)
            }
        }
    }
    
    /**
     * BroadcastReceiver voor het herstarten van het alarm na device reboot
     */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                Log.i(TAG, "Device opgestart, alarm herschedulen")
                scheduleNextAlarm(context)
            }
        }
    }
}
