package com.yvesds.vt5.features.telling

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.R

/**
 * HuidigeStandScherm
 *
 * Rudimentair overzichtsscherm dat de huidige soorten en aantallen toont
 * in een eenvoudige tabel met kolommen: Soortnaam | Totaal Aantal | ZW | NO
 *
 * Verwacht Intent extras:
 * - EXTRA_SOORT_IDS: ArrayList<String>
 * - EXTRA_SOORT_NAMEN: ArrayList<String>
 * - EXTRA_SOORT_AANTALLEN: ArrayList<String> (counts as strings)
 */
class HuidigeStandScherm : AppCompatActivity() {

    companion object {
        const val EXTRA_SOORT_IDS = "extra_soort_ids"
        const val EXTRA_SOORT_NAMEN = "extra_soort_namen"
        const val EXTRA_SOORT_AANTALLEN = "extra_soort_aantallen"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_huidige_stand)

        val table = findViewById<TableLayout>(R.id.table_soorten)
        val totalsTv = findViewById<TextView>(R.id.tv_totals)
        val okBtn = findViewById<Button>(R.id.btn_ok_huidige_stand)

        // Header row
        val header = TableRow(this).apply {
            val lp = TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
            setPadding(8, 8, 8, 8)
        }
        header.addView(makeHeaderTextView("Soortnaam"))
        header.addView(makeHeaderTextView("Totaal Aantal"))
        header.addView(makeHeaderTextView("ZW"))
        header.addView(makeHeaderTextView("NO"))
        table.addView(header)

        // Read extras
        val ids = intent.getStringArrayListExtra(EXTRA_SOORT_IDS) ?: arrayListOf()
        val names = intent.getStringArrayListExtra(EXTRA_SOORT_NAMEN) ?: arrayListOf()
        val countsRaw = intent.getStringArrayListExtra(EXTRA_SOORT_AANTALLEN) ?: arrayListOf()

        // Safety: ensure sizes match; otherwise use shortest
        val n = listOf(ids.size, names.size, countsRaw.size).minOrNull() ?: 0

        var totalSum = 0
        var zwSum = 0
        var noSum = 0 // currently 0, but kept for future use

        for (i in 0 until n) {
            val name = names[i]
            val count = countsRaw[i].toIntOrNull() ?: 0
            totalSum += count
            zwSum += count // for now assign all to ZW
            // noSum unchanged (0)

            val row = TableRow(this).apply {
                val lp = TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT)
                layoutParams = lp
                setPadding(8, 8, 8, 8)
            }

            row.addView(makeCellTextView(name))
            row.addView(makeCellTextView(count.toString()))
            // For now place total into ZW column
            row.addView(makeCellTextView(count.toString()))
            // NO column empty for now
            row.addView(makeCellTextView(""))

            table.addView(row)
        }

        // Set totals text (use SUM symbol Σ)
        totalsTv.text = "Σ Totaal: $totalSum | ZW: $zwSum ex | NO: $noSum ex"

        okBtn.setOnClickListener {
            // Simply finish and return to TellingScherm; TellingScherm state is preserved
            finish()
        }
    }

    private fun makeHeaderTextView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setPadding(12, 8, 12, 8)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.START
        }
    }

    private fun makeCellTextView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setPadding(12, 8, 12, 8)
            gravity = Gravity.START
        }
    }
}