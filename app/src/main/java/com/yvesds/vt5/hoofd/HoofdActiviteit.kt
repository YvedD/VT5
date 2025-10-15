package com.yvesds.vt5.hoofd

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HoofdActiviteit : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_hoofd)

        // Knoppen
        val btnInstall  = findViewById<Button>(R.id.btnInstall)
        val btnVerder   = findViewById<Button>(R.id.btnVerder)
        val btnAfsluiten= findViewById<Button>(R.id.btnAfsluiten)

        btnInstall.setOnClickListener {
            navigateTo(InstallatieScherm::class.java)
        }

        btnVerder.setOnClickListener {
            navigateTo(MetadataScherm::class.java)
        }

        btnAfsluiten.setOnClickListener {
            finishAppCompletely()
        }

        // Snelle, niet-blokkerende warm-up (sites + drie kleine codes)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                ServerDataRepository.getInstance(applicationContext)
                    .tryWarmup(listOf("wind", "neerslag", "typetelling_trek"))
            }.onFailure {
                // Geen crash; enkel informatief tijdens dev
                // Logcat kan gebruikt worden indien gewenst:
                // Log.w("HoofdActiviteit", "Warmup faalde", it)
            }
        }

        // Mini feedback (optioneel, voelbaar bij cold start)
        Toast.makeText(this, getString(R.string.titel_hoofd), Toast.LENGTH_SHORT).show()
    }

    /** Start een Activity met een nette (API-afhankelijke) fade-overgang. */
    private fun navigateTo(target: Class<out Activity>) {
        val intent = Intent(this, target)
        startActivity(intent)
        applyModernTransitionOpen()
    }

    /** Past een moderne open-transition toe (Android 14+) met backward compat fallback. */
    private fun applyModernTransitionOpen() {
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: moderne API
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    /** Past een moderne close-transition toe (Android 14+) met fallback. */
    private fun applyModernTransitionClose() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    /**
     * Sluit de app en verwijder uit Recents (indien mogelijk).
     * - API 21+: finishAndRemoveTask()
     * - anders: finishAffinity() als beste alternatief
     */
    private fun finishAppCompletely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            // Verlaat alle activiteiten in de taakstack
            finishAffinity()
        }
        // Optioneel: een minuscule fade-out bij exit (niet verplicht)
        applyModernTransitionClose()
    }
}
