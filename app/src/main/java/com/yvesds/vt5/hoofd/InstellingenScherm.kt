package com.yvesds.vt5.hoofd

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.R

/**
 * InstellingenScherm - Scherm voor app-instellingen
 * 
 * Basis layout met 3 placeholders voor toekomstige instellingen.
 * Instellingen worden opgeslagen via SharedPreferences voor gebruik doorheen de app.
 */
class InstellingenScherm : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_instellingen)
        
        // Placeholders - later uit te breiden met echte instellingen
    }
}
