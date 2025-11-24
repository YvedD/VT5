package com.yvesds.vt5.core.app

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmTestHelper
 * 
 * Hulpklasse voor het testen van het uurlijkse alarm systeem.
 * Kan gebruikt worden in debug builds of in een test/instellingen scherm.
 */
object AlarmTestHelper {
    private const val TAG = "AlarmTestHelper"
    
    /**
     * Triggert het alarm handmatig voor test doeleinden.
     * Dit simuleert het ontvangen van een alarm broadcast.
     */
    fun triggerAlarmManually(context: Context) {
        try {
            val intent = Intent(context, HourlyAlarmManager.HourlyAlarmReceiver::class.java)
            context.sendBroadcast(intent)
            Log.i(TAG, "Test alarm handmatig getriggerd")
        } catch (e: Exception) {
            Log.e(TAG, "Fout bij triggeren test alarm: ${e.message}", e)
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
}
