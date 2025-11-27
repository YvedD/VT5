package com.yvesds.vt5.features.telling

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.R
import com.yvesds.vt5.utils.SeizoenUtils

/**
 * HuidigeStandScherm
 *
 * Overzichtsscherm dat de huidige soorten en aantallen toont
 * in een eenvoudige tabel met kolommen: Soortnaam | Totaal | Hoofdrichting | Terug
 * De labels voor de richtingen zijn seizoensafhankelijk (ZW/NO of NO/ZW).
 * 
 * BELANGRIJK: De seizoensbepaling is gebaseerd op de telling-datum (begintijd),
 * NIET op de huidige systeemdatum. Dit is nodig wanneer een gebruiker een telling
 * invoert op een andere datum dan vandaag (bijv. een vergeten telling van januari
 * die in november wordt ingevoerd).
 * 
 * Seizoensregels (volgens de telling-datum):
 * - januari -> juni: aantal = 'NO', aantalterug = 'ZW'
 * - juli -> december: aantal = 'ZW', aantalterug = 'NO'
 */
class HuidigeStandScherm : AppCompatActivity() {

    companion object {
        const val EXTRA_SOORT_IDS = "extra_soort_ids"
        const val EXTRA_SOORT_NAMEN = "extra_soort_namen"
        const val EXTRA_SOORT_AANTALLEN_MAIN = "extra_soort_aantallen_main"
        const val EXTRA_SOORT_AANTALLEN_RETURN = "extra_soort_aantallen_return"
        /** Telling start time (begintijd) as epoch seconds for correct season determination */
        const val EXTRA_TELLING_BEGINTIJD_EPOCH = "extra_telling_begintijd_epoch"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_huidige_stand)

        val table = findViewById<TableLayout>(R.id.table_soorten)
        val totalsTv = findViewById<TextView>(R.id.tv_totals)
        val okBtn = findViewById<Button>(R.id.btn_ok_huidige_stand)

        // Determine season-based labels using the telling date (not current system date)
        // -1L means not provided, fall back to current date
        val begintijdEpoch = intent.getLongExtra(EXTRA_TELLING_BEGINTIJD_EPOCH, -1L)
        val isZwSeizoen = if (begintijdEpoch > 0) {
            SeizoenUtils.isZwSeizoen(begintijdEpoch)
        } else {
            SeizoenUtils.isZwSeizoen()
        }
        val mainLabel = if (isZwSeizoen) "ZW" else "NO"
        val returnLabel = if (isZwSeizoen) "NO" else "ZW"

        // Header row with dynamic labels
        val header = TableRow(this).apply {
            val lp = TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
            setPadding(8, 8, 8, 8)
        }
        header.addView(makeHeaderTextView("Soortnaam"))
        header.addView(makeHeaderTextView("Totaal"))
        header.addView(makeHeaderTextView(mainLabel))
        header.addView(makeHeaderTextView(returnLabel))
        table.addView(header)

        // Read extras
        val ids = intent.getStringArrayListExtra(EXTRA_SOORT_IDS) ?: arrayListOf()
        val names = intent.getStringArrayListExtra(EXTRA_SOORT_NAMEN) ?: arrayListOf()
        val countsMain = intent.getStringArrayListExtra(EXTRA_SOORT_AANTALLEN_MAIN) ?: arrayListOf()
        val countsReturn = intent.getStringArrayListExtra(EXTRA_SOORT_AANTALLEN_RETURN) ?: arrayListOf()

        // Safety: ensure sizes match; otherwise use shortest
        val n = listOf(ids.size, names.size, countsMain.size, countsReturn.size).minOrNull() ?: 0

        var totalSum = 0
        var mainSum = 0
        var returnSum = 0

        for (i in 0 until n) {
            val name = names[i]
            val countMain = countsMain[i].toIntOrNull() ?: 0
            val countReturn = countsReturn[i].toIntOrNull() ?: 0
            val total = countMain + countReturn
            
            totalSum += total
            mainSum += countMain
            returnSum += countReturn

            val row = TableRow(this).apply {
                val lp = TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT)
                layoutParams = lp
                setPadding(8, 8, 8, 8)
            }

            row.addView(makeCellTextView(name))
            row.addView(makeCellTextView(total.toString()))
            row.addView(makeCellTextView(countMain.toString()))
            row.addView(makeCellTextView(countReturn.toString()))

            table.addView(row)
        }

        // Set totals text with dynamic labels
        totalsTv.text = getString(R.string.huidige_stand_totals_dynamic, totalSum, mainLabel, mainSum, returnLabel, returnSum)

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