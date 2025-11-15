package com.yvesds.vt5.hoofd

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.yvesds.vt5.R
import com.yvesds.vt5.core.app.AppShutdown
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm

/**
 * HoofdActiviteit - Hoofdscherm van VT5 app
 * 
 * Biedt drie opties:
 * 1. (Her)Installatie → InstallatieScherm
 * 2. Invoeren telpostgegevens → MetadataScherm  
 * 3. Afsluiten → Veilige app shutdown met cleanup
 */
class HoofdActiviteit : AppCompatActivity() {
    private val TAG = "HoofdActiviteit"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_hoofd)

        val btnInstall   = findViewById<MaterialButton>(R.id.btnInstall)
        val btnVerder    = findViewById<MaterialButton>(R.id.btnVerder)
        val btnAfsluiten = findViewById<MaterialButton>(R.id.btnAfsluiten)

        btnInstall.setOnClickListener {
            it.isEnabled = false
            startActivity(Intent(this, InstallatieScherm::class.java))
            it.isEnabled = true
        }

        btnVerder.setOnClickListener {
            it.isEnabled = false
            // Toon meteen de toast vóór de navigatie
            Toast.makeText(this, "Metadata laden…", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MetadataScherm::class.java))
            it.isEnabled = true
        }

        btnAfsluiten.setOnClickListener {
            it.isEnabled = false
            shutdownAndExit()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            @Suppress("DEPRECATION")
            finish()
        }
    }
}
