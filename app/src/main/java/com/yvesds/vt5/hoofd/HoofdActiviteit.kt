package com.yvesds.vt5.hoofd

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.yvesds.vt5.R
import com.yvesds.vt5.core.app.AppShutdown
import com.yvesds.vt5.core.app.HourlyAlarmManager
import com.yvesds.vt5.core.app.AlarmTestHelper
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm
import com.yvesds.vt5.features.telling.TellingBeheerScherm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * HoofdActiviteit - Hoofdscherm van VT5 app
 * 
 * Biedt drie opties:
 * 1. (Her)Installatie → InstallatieScherm
 * 2. Invoeren telpostgegevens → MetadataScherm  
 * 3. Afsluiten → Veilige app shutdown met cleanup
 * 4. Bewerk tellingen → TellingBeheerScherm
 */
class HoofdActiviteit : AppCompatActivity() {
    private val TAG = "HoofdActiviteit"

    @OptIn(ExperimentalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_hoofd)

        val btnInstall   = findViewById<MaterialButton>(R.id.btnInstall)
        val btnVerder    = findViewById<MaterialButton>(R.id.btnVerder)
        val btnAfsluiten = findViewById<MaterialButton>(R.id.btnAfsluiten)
        val btnBewerkTellingen = findViewById<MaterialButton>(R.id.btnBewerkTellingen)
        
        // Alarm sectie - altijd zichtbaar
        setupAlarmSection()

        btnInstall.setOnClickListener {
            it.isEnabled = false
            startActivity(Intent(this, InstallatieScherm::class.java))
            it.isEnabled = true
        }

        btnVerder.setOnClickListener {
            it.isEnabled = false
            // OPTIMIZATION: Trigger preload during toast display for faster MetadataScherm startup
            Toast.makeText(this, getString(R.string.hoofd_metadata_loading), Toast.LENGTH_SHORT).show()
            
            // Start preloading minimal data in background
            lifecycleScope.launch {
                try {
                    val repo = com.yvesds.vt5.features.serverdata.model.ServerDataRepository(this@HoofdActiviteit)
                    withContext(Dispatchers.IO) {
                        // Trigger background preload (non-blocking)
                        repo.loadMinimalData()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background preload failed (non-critical): ${e.message}")
                }
            }
            
            startActivity(Intent(this, MetadataScherm::class.java))
            it.isEnabled = true
        }

        btnAfsluiten.setOnClickListener {
            it.isEnabled = false
            shutdownAndExit()
        }
        
        // Bewerk tellingen knop
        btnBewerkTellingen.setOnClickListener {
            it.isEnabled = false
            startActivity(Intent(this, TellingBeheerScherm::class.java))
            it.isEnabled = true
        }
    }

    /**
     * Voert een veilige shutdown uit:
     * - Roept AppShutdown.shutdownApp() voor cleanup
     * - Verwijdert de app uit 'recente apps' (finishAndRemoveTask)
     * - Thread-safe en voorkomt geheugenlekken
     */
    private fun shutdownAndExit() {
        Log.i(TAG, "User initiated app shutdown")
        
        try {
            // Voer alle cleanup uit (netwerk clients, logs, etc.)
            AppShutdown.shutdownApp(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown cleanup: ${e.message}", e)
        }
        
        // Verwijder de app uit recente apps en sluit af
        finishAndRemoveTaskCompat()
    }

    /**
     * Compatibility wrapper voor finishAndRemoveTask()
     * - API 21+: finishAndRemoveTask() verwijdert app uit recente apps
     * - API < 21: Fallback naar finish()
     */
    private fun finishAndRemoveTaskCompat() {
        finishAndRemoveTask()
    }
    
    /**
     * Setup alarm sectie
     * Altijd zichtbaar, niet alleen in debug builds
     */
    private fun setupAlarmSection() {
        val tvAlarmStatus = findViewById<TextView>(R.id.tvAlarmStatus)
        val btnTestAlarm = findViewById<MaterialButton>(R.id.btnTestAlarm)
        val btnToggleAlarm = findViewById<MaterialButton>(R.id.btnToggleAlarm)
        
        // Update alarm status
        updateAlarmStatus()
        
        // Test alarm button
        btnTestAlarm?.setOnClickListener {
            it.isEnabled = false
            AlarmTestHelper.triggerAlarmManually(this)
            Toast.makeText(this, "Test alarm getriggerd!", Toast.LENGTH_SHORT).show()
            it.postDelayed({ it.isEnabled = true }, 1000)
        }
        
        // Toggle alarm button
        btnToggleAlarm?.setOnClickListener {
            it.isEnabled = false
            val currentlyEnabled = HourlyAlarmManager.isEnabled(this)
            HourlyAlarmManager.setEnabled(this, !currentlyEnabled)
            updateAlarmStatus()
            Toast.makeText(
                this,
                if (!currentlyEnabled) "Alarm ingeschakeld" else "Alarm uitgeschakeld",
                Toast.LENGTH_SHORT
            ).show()
            it.postDelayed({ it.isEnabled = true }, 500)
        }
    }
    
    /**
     * Update de alarm status tekst
     * Toont eenvoudig "Uurlijks alarm is ingeschakeld/uitgeschakeld"
     */
    private fun updateAlarmStatus() {
        val tvAlarmStatus = findViewById<TextView>(R.id.tvAlarmStatus)
        val enabled = HourlyAlarmManager.isEnabled(this)
        
        tvAlarmStatus?.text = if (enabled) {
            getString(R.string.hoofd_alarm_enabled)
        } else {
            getString(R.string.hoofd_alarm_disabled)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Update status wanneer we terugkomen naar dit scherm
        updateAlarmStatus()
    }
}
