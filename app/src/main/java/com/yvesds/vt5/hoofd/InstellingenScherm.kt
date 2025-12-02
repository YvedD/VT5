package com.yvesds.vt5.hoofd

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.yvesds.vt5.R

/**
 * InstellingenScherm - Scherm voor app-instellingen
 * 
 * Biedt instellingen voor:
 * - Lettergrootte van logregels (partial/final) en tiles in TellingScherm
 * 
 * Instellingen worden opgeslagen via SharedPreferences voor gebruik doorheen de app.
 */
class InstellingenScherm : AppCompatActivity() {
    
    companion object {
        private const val PREFS_NAME = "vt5_prefs"
        const val PREF_LETTERGROOTTE_SP = "pref_lettergrootte_sp"
        
        // Lettergrootte opties in sp
        val LETTERGROOTTE_WAARDEN = intArrayOf(14, 17, 20, 24)
        const val DEFAULT_LETTERGROOTTE_SP = 17
        
        /**
         * Haal de huidige lettergrootte op uit SharedPreferences
         */
        fun getLettergrootteSp(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(PREF_LETTERGROOTTE_SP, DEFAULT_LETTERGROOTTE_SP)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_instellingen)
        
        setupTerugKnop()
        setupLettergrootteSpinner()
    }
    
    private fun setupTerugKnop() {
        val btnTerug = findViewById<MaterialButton>(R.id.btnTerug)
        btnTerug.setOnClickListener {
            finish()
        }
    }
    
    private fun setupLettergrootteSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinnerLettergrootte)
        val opties = resources.getStringArray(R.array.lettergrootte_opties)
        
        // Custom adapter voor witte tekst op donkere achtergrond
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, opties) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(resources.getColor(R.color.vt5_white, theme))
                view.textSize = 16f
                return view
            }
            
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(resources.getColor(R.color.vt5_white, theme))
                view.setBackgroundColor(resources.getColor(R.color.vt5_dark_gray, theme))
                view.setPadding(24, 24, 24, 24)
                view.textSize = 16f
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        // Huidige waarde selecteren
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentSp = prefs.getInt(PREF_LETTERGROOTTE_SP, DEFAULT_LETTERGROOTTE_SP)
        val currentIndex = LETTERGROOTTE_WAARDEN.indexOf(currentSp).coerceAtLeast(0)
        spinner.setSelection(currentIndex)
        
        // Listener voor wijzigingen
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val nieuweSp = LETTERGROOTTE_WAARDEN[position]
                prefs.edit {
                    putInt(PREF_LETTERGROOTTE_SP, nieuweSp)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Niets doen
            }
        }
    }
}
