package com.yvesds.vt5.hoofd

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.yvesds.vt5.R
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm

class HoofdActiviteit : AppCompatActivity() {

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
            finishAndRemoveTaskCompat()
        }
    }

    private fun finishAndRemoveTaskCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            @Suppress("DEPRECATION")
            finish()
        }
    }
}
