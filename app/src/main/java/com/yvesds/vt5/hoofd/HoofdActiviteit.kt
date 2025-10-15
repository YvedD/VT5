package com.yvesds.vt5.hoofd

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.R
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm

class HoofdActiviteit : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_hoofd)

        val btnInstall = findViewById<Button>(R.id.btnInstall)
        val btnVerder = findViewById<Button>(R.id.btnVerder)
        val btnAfsluiten = findViewById<Button>(R.id.btnAfsluiten)

        btnInstall.setOnClickListener {
            startActivity(Intent(this, InstallatieScherm::class.java))
            noTransitionOpen()
        }

        btnVerder.setOnClickListener {
            startActivity(Intent(this, MetadataScherm::class.java))
            noTransitionOpen()
        }

        btnAfsluiten.setOnClickListener {
            finishAffinity()
            noTransitionClose()
        }
    }

    /** Disable “open” animation in a non-deprecated way on API 34+, fallback for older. */
    private fun noTransitionOpen() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Disable “close” animation in a non-deprecated way on API 34+, fallback for older. */
    private fun noTransitionClose() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
