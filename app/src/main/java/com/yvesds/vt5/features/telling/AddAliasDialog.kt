package com.yvesds.vt5.features.telling

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.yvesds.vt5.databinding.DialogAddAliasBinding

/**
 * Dialog to select one of the partials and assign it to a species.
 * speciesFlat: list of "id||displayName"
 */
class AddAliasDialog : DialogFragment() {

    interface AddAliasListener {
        fun onAliasAssigned(speciesId: String, aliasText: String)
    }

    var listener: AddAliasListener? = null

    companion object {
        private const val ARG_PARTIALS = "arg_partials"
        private const val ARG_SPECIES = "arg_species"

        fun newInstance(partials: List<String>, speciesFlat: List<String>): AddAliasDialog {
            val d = AddAliasDialog()
            val b = Bundle()
            b.putStringArrayList(ARG_PARTIALS, ArrayList(partials))
            b.putStringArrayList(ARG_SPECIES, ArrayList(speciesFlat))
            d.arguments = b
            return d
        }
    }

    private lateinit var vb: DialogAddAliasBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        vb = DialogAddAliasBinding.inflate(LayoutInflater.from(context))

        val partials = arguments?.getStringArrayList(ARG_PARTIALS) ?: arrayListOf()
        val speciesFlat = arguments?.getStringArrayList(ARG_SPECIES) ?: arrayListOf()

        // RadioGroup for partials
        val rg = RadioGroup(requireContext())
        rg.orientation = RadioGroup.VERTICAL
        var defaultRadioId: Int? = null
        partials.forEachIndexed { idx, p ->
            val rb = RadioButton(requireContext())
            rb.id = View.generateViewId()
            rb.text = p
            rg.addView(rb)
            if (idx == partials.lastIndex) defaultRadioId = rb.id
        }
        if (rg.childCount > 0) {
            vb.containerPartials.removeAllViews()
            vb.containerPartials.addView(rg)
            defaultRadioId?.let { rg.check(it) }
        } else {
            vb.containerPartials.visibility = View.GONE
        }

        // Autocomplete species list
        val names = speciesFlat.map { it.substringAfter("||") }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        val act = vb.actSpecies as AutoCompleteTextView
        act.setAdapter(adapter)
        act.threshold = 1

        val nameToId = HashMap<String, String>(names.size)
        speciesFlat.forEach {
            val id = it.substringBefore("||")
            val nm = it.substringAfter("||")
            nameToId[nm] = id
        }

        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Koppel alias aan soort")
            .setView(vb.root)
            .setPositiveButton("Toevoegen") { _, _ ->
                val checkedId = rg.checkedRadioButtonId
                val aliasText = if (checkedId != -1) {
                    val rb = rg.findViewById<RadioButton>(checkedId)
                    rb?.text?.toString()?.trim() ?: ""
                } else ""
                val chosenName = act.text?.toString()?.trim().orEmpty()
                val chosenId = nameToId[chosenName] ?: names.firstOrNull { it.equals(chosenName, ignoreCase = true) }?.let { nameToId[it] }

                if (aliasText.isNotBlank() && !chosenId.isNullOrBlank()) {
                    listener?.onAliasAssigned(chosenId, aliasText)
                }
            }
            .setNegativeButton("Annuleer", null)
            .create()

        return dlg
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
    }
}