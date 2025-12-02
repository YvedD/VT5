package com.yvesds.vt5.hoofd

import android.content.Context
import android.os.Bundle
import android.widget.NumberPicker
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.yvesds.vt5.R

/**
 * InstellingenScherm - Scherm voor app-instellingen
 * 
 * Biedt instellingen voor:
 * - Lettergrootte van logregels (partial/final) in TellingScherm
 * - Lettergrootte van tegels (soortnaam + aantallen) in TellingScherm
 * 
 * Instellingen worden opgeslagen via SharedPreferences voor gebruik doorheen de app.
 */
class InstellingenScherm : AppCompatActivity() {
    
    companion object {
        private const val PREFS_NAME = "vt5_prefs"
        const val PREF_LETTERGROOTTE_LOG_SP = "pref_lettergrootte_log_sp"
        const val PREF_LETTERGROOTTE_TEGELS_SP = "pref_lettergrootte_tegels_sp"
        
        // Oude key voor backwards compatibility
        @Deprecated("Gebruik PREF_LETTERGROOTTE_LOG_SP of PREF_LETTERGROOTTE_TEGELS_SP")
        const val PREF_LETTERGROOTTE_SP = "pref_lettergrootte_sp"
        
        // Lettergrootte bereik in sp
        const val MIN_LETTERGROOTTE_SP = 10
        const val MAX_LETTERGROOTTE_SP = 30
        const val DEFAULT_LETTERGROOTTE_SP = 17
        
        /**
         * Haal de huidige lettergrootte voor logregels op uit SharedPreferences
         */
        fun getLettergrootteLogSp(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Check eerst nieuwe key, dan oude key voor backwards compatibility
            return prefs.getInt(PREF_LETTERGROOTTE_LOG_SP, 
                prefs.getInt(PREF_LETTERGROOTTE_SP, DEFAULT_LETTERGROOTTE_SP))
        }
        
        /**
         * Haal de huidige lettergrootte voor tegels op uit SharedPreferences
         */
        fun getLettergroottTegelsSp(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Check eerst nieuwe key, dan oude key voor backwards compatibility
            return prefs.getInt(PREF_LETTERGROOTTE_TEGELS_SP, 
                prefs.getInt(PREF_LETTERGROOTTE_SP, DEFAULT_LETTERGROOTTE_SP))
        }
        
        /**
         * @deprecated Gebruik getLettergrootteLogSp of getLettergrootteTegelsPs
         */
        @Deprecated("Gebruik getLettergrootteLogSp of getLettergrootteTegelsPs", 
            ReplaceWith("getLettergrootteLogSp(context)"))
        fun getLettergrootteSp(context: Context): Int = getLettergrootteLogSp(context)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_instellingen)
        
        setupTerugKnop()
        setupLettergrootteNumberPickers()
    }
    
    private fun setupTerugKnop() {
        val btnTerug = findViewById<MaterialButton>(R.id.btnTerug)
        btnTerug.setOnClickListener {
            finish()
        }
    }
    
    private fun setupLettergrootteNumberPickers() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // NumberPicker voor logregels
        val npLog = findViewById<NumberPicker>(R.id.npLettergrootteLog)
        npLog.minValue = MIN_LETTERGROOTTE_SP
        npLog.maxValue = MAX_LETTERGROOTTE_SP
        npLog.wrapSelectorWheel = false
        
        // Huidige waarde laden (met backwards compatibility)
        val currentLogSp = prefs.getInt(PREF_LETTERGROOTTE_LOG_SP, 
            prefs.getInt(PREF_LETTERGROOTTE_SP, DEFAULT_LETTERGROOTTE_SP))
        npLog.value = currentLogSp.coerceIn(MIN_LETTERGROOTTE_SP, MAX_LETTERGROOTTE_SP)
        
        npLog.setOnValueChangedListener { _, _, newVal ->
            prefs.edit {
                putInt(PREF_LETTERGROOTTE_LOG_SP, newVal)
            }
        }
        
        // NumberPicker voor tegels
        val npTegels = findViewById<NumberPicker>(R.id.npLettergrootteTegels)
        npTegels.minValue = MIN_LETTERGROOTTE_SP
        npTegels.maxValue = MAX_LETTERGROOTTE_SP
        npTegels.wrapSelectorWheel = false
        
        // Huidige waarde laden (met backwards compatibility)
        val currentTegelsSp = prefs.getInt(PREF_LETTERGROOTTE_TEGELS_SP, 
            prefs.getInt(PREF_LETTERGROOTTE_SP, DEFAULT_LETTERGROOTTE_SP))
        npTegels.value = currentTegelsSp.coerceIn(MIN_LETTERGROOTTE_SP, MAX_LETTERGROOTTE_SP)
        
        npTegels.setOnValueChangedListener { _, _, newVal ->
            prefs.edit {
                putInt(PREF_LETTERGROOTTE_TEGELS_SP, newVal)
            }
        }
    }
}
