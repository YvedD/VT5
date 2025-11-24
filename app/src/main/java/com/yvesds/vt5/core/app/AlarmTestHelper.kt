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
