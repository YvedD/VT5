package com.yvesds.vt5.features.telling

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.R

/**
 * Rudimentair placeholder AnnotatieScherm.
 *
 * - Ontvangen intent extras:
 *    EXTRA_TEXT: de displaytekst van de final entry
 *    EXTRA_TS: timestamp (sec) van de log entry
 *
 * Voor nu slaan we annotaties niet persistent op; het scherm fungeert als placeholder en sluit met OK.
 */
class AnnotatieScherm : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_TS = "extra_ts"
    }

    private lateinit var edtNotes: EditText
    private lateinit var btnOk: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_annotatie)

        edtNotes = findViewById(R.id.edtAnnotatie)
        btnOk = findViewById(R.id.btnAnnotatieOk)
        btnCancel = findViewById(R.id.btnAnnotatieCancel)

        // Prefill hint with the item text (non-editable hint)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        edtNotes.hint = "Annotatie voor: $text"

        btnOk.setOnClickListener {
            // Voorlopig geen persistente opslag — sluiten en terug naar TellingScherm
            // (TODO: later: append as metadata to match_log or local DB)
            finish()
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }
}