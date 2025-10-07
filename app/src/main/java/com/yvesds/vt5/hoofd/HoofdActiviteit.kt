package com.yvesds.vt5.hoofd

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.databinding.SchermHoofdBinding
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm

/**
 * Startscherm (XML, zonder Compose).
 * - Layout: res/layout/scherm_hoofd.xml  ->  SchermHoofdBinding
 * - Knoppen (exacte IDs uit jouw layout):
 *     - btnInstall   -> opent InstallatieScherm
 *     - btnVerder    -> opent MetadataScherm
 *     - btnAfsluiten -> sluit app (finishAffinity)
 *
 * Geen zware I/O of blokkerende code hier.
 */
class HoofdActiviteit : AppCompatActivity() {

    private lateinit var binding: SchermHoofdBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermHoofdBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigatie: exact jouw IDs gebruiken
        binding.btnInstall.setOnClickListener {
            startActivity(Intent(this, InstallatieScherm::class.java))
        }

        binding.btnVerder.setOnClickListener {
            startActivity(Intent(this, MetadataScherm::class.java))
        }

        binding.btnAfsluiten.setOnClickListener {
            // Sluit alle activities in deze task en verwijder de task uit Recents
            finishAndRemoveTask()
            // Als extra vangnet (oudere OEM’s)
            finishAffinity()
        }
    }
}
