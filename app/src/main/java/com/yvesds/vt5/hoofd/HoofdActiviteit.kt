package com.yvesds.vt5.hoofd

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.core.app.AppShutdown
import com.yvesds.vt5.databinding.SchermHoofdBinding
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm

class HoofdActiviteit : AppCompatActivity() {

    private lateinit var binding: SchermHoofdBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermHoofdBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInstall.setOnClickListener {
            startActivity(Intent(this, InstallatieScherm::class.java))
        }

        binding.btnVerder.setOnClickListener {
            startActivity(Intent(this, MetadataScherm::class.java))
        }

        binding.btnAfsluiten.setOnClickListener {
            AppShutdown.shutdownApp(this)
            (this as? Activity)?.finishAndRemoveTask()
        }
    }
}
