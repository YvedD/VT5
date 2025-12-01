package com.yvesds.vt5.features.telling

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.yvesds.vt5.R
import com.yvesds.vt5.databinding.DialogAfrondConfirmBinding
import java.util.Calendar
import java.util.Date

/**
 * AfrondConfirmDialog: Confirmation dialog for finalizing a telling.
 * 
 * Shows editable fields for:
 * - Begintijd (start time) - using NumberPicker spinners
 * - Eindtijd (end time) - using NumberPicker spinners  
 * - Opmerkingen (remarks)
 * 
 * The original values from the metadata are shown as defaults.
 * Changes are passed back to the caller via the listener interface.
 */
class AfrondConfirmDialog : DialogFragment() {

    companion object {
        private const val TAG = "AfrondConfirmDialog"
        private const val ARG_BEGINTIJD_EPOCH = "arg_begintijd_epoch"
        private const val ARG_EINDTIJD_EPOCH = "arg_eindtijd_epoch"
        private const val ARG_OPMERKINGEN = "arg_opmerkingen"

        /**
         * Create a new instance of the dialog with the current metadata values.
         * 
         * @param begintijdEpoch Start time as epoch seconds (String)
         * @param eindtijdEpoch End time as epoch seconds (String), can be empty for "now"
         * @param opmerkingen Current remarks
         */
        fun newInstance(
            begintijdEpoch: String,
            eindtijdEpoch: String,
            opmerkingen: String
        ): AfrondConfirmDialog {
            return AfrondConfirmDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BEGINTIJD_EPOCH, begintijdEpoch)
                    putString(ARG_EINDTIJD_EPOCH, eindtijdEpoch)
                    putString(ARG_OPMERKINGEN, opmerkingen)
                }
            }
        }
    }

    /**
     * Listener interface for dialog result.
     */
    interface AfrondConfirmListener {
        /**
         * Called when user confirms the dialog.
         * 
         * @param begintijdEpoch Updated start time as epoch seconds string
         * @param eindtijdEpoch Updated end time as epoch seconds string
         * @param opmerkingen Updated remarks
         */
        fun onAfrondConfirmed(begintijdEpoch: String, eindtijdEpoch: String, opmerkingen: String)
        
        /**
         * Called when user cancels the dialog.
         */
        fun onAfrondCancelled()
    }

    var listener: AfrondConfirmListener? = null

    private lateinit var binding: DialogAfrondConfirmBinding
    
    // Time values in hours and minutes
    private var begintijdHour = 0
    private var begintijdMinute = 0
    private var eindtijdHour = 0
    private var eindtijdMinute = 0
    
    // Date for epoch conversion (from begintijd) - initialized with current date as safe default
    private var dateYear = Calendar.getInstance().get(Calendar.YEAR)
    private var dateMonth = Calendar.getInstance().get(Calendar.MONTH)
    private var dateDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogAfrondConfirmBinding.inflate(LayoutInflater.from(context))

        val begintijdEpochStr = arguments?.getString(ARG_BEGINTIJD_EPOCH) ?: ""
        val eindtijdEpochStr = arguments?.getString(ARG_EINDTIJD_EPOCH) ?: ""
        val opmerkingen = arguments?.getString(ARG_OPMERKINGEN) ?: ""

        // Parse begintijd epoch to get date and time
        parseEpochToDateTime(begintijdEpochStr, isBegin = true)
        
        // Parse eindtijd epoch (or use current time if empty)
        if (eindtijdEpochStr.isBlank() || eindtijdEpochStr == "0") {
            val now = Calendar.getInstance()
            eindtijdHour = now.get(Calendar.HOUR_OF_DAY)
            eindtijdMinute = now.get(Calendar.MINUTE)
        } else {
            parseEpochToDateTime(eindtijdEpochStr, isBegin = false)
        }

        // Format times for display
        updateTimeDisplay()

        // Set opmerkingen
        binding.etOpmerkingen.setText(opmerkingen)

        // Setup click listeners for time fields
        binding.etBegintijd.setOnClickListener { showTimeSpinnerDialog(isBegin = true) }
        binding.etEindtijd.setOnClickListener { showTimeSpinnerDialog(isBegin = false) }

        val dlg = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_confirm_finish))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.afrond_confirm_upload)) { _, _ ->
                // Convert times back to epoch and call listener
                val newBegintijdEpoch = computeEpochFromTime(begintijdHour, begintijdMinute)
                val newEindtijdEpoch = computeEpochFromTime(eindtijdHour, eindtijdMinute)
                val newOpmerkingen = binding.etOpmerkingen.text?.toString()?.trim() ?: ""
                
                listener?.onAfrondConfirmed(
                    newBegintijdEpoch.toString(),
                    newEindtijdEpoch.toString(),
                    newOpmerkingen
                )
            }
            .setNegativeButton(getString(R.string.dlg_cancel)) { _, _ ->
                listener?.onAfrondCancelled()
            }
            .create()

        return dlg
    }

    /**
     * Parse epoch seconds to date and time components.
     */
    private fun parseEpochToDateTime(epochStr: String, isBegin: Boolean) {
        try {
            val epochSeconds = epochStr.toLongOrNull()
            if (epochSeconds == null) {
                Log.w(TAG, "Failed to parse epoch string to Long: '$epochStr', using current time as fallback")
                setFallbackTime(isBegin)
                return
            }
            val date = Date(epochSeconds * 1000L)
            val cal = Calendar.getInstance()
            cal.time = date

            if (isBegin) {
                dateYear = cal.get(Calendar.YEAR)
                dateMonth = cal.get(Calendar.MONTH)
                dateDay = cal.get(Calendar.DAY_OF_MONTH)
                begintijdHour = cal.get(Calendar.HOUR_OF_DAY)
                begintijdMinute = cal.get(Calendar.MINUTE)
            } else {
                eindtijdHour = cal.get(Calendar.HOUR_OF_DAY)
                eindtijdMinute = cal.get(Calendar.MINUTE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse epoch: ${e.message}")
            setFallbackTime(isBegin)
        }
    }
    
    /**
     * Set fallback time values to current time.
     */
    private fun setFallbackTime(isBegin: Boolean) {
        val now = Calendar.getInstance()
        if (isBegin) {
            dateYear = now.get(Calendar.YEAR)
            dateMonth = now.get(Calendar.MONTH)
            dateDay = now.get(Calendar.DAY_OF_MONTH)
            begintijdHour = now.get(Calendar.HOUR_OF_DAY)
            begintijdMinute = now.get(Calendar.MINUTE)
        } else {
            eindtijdHour = now.get(Calendar.HOUR_OF_DAY)
            eindtijdMinute = now.get(Calendar.MINUTE)
        }
    }

    /**
     * Update the time display fields.
     */
    private fun updateTimeDisplay() {
        val begintijdStr = formatTime(begintijdHour, begintijdMinute)
        val eindtijdStr = formatTime(eindtijdHour, eindtijdMinute)
        binding.etBegintijd.setText(begintijdStr)
        binding.etEindtijd.setText(eindtijdStr)
    }

    /**
     * Format hour and minute to HH:mm string.
     */
    private fun formatTime(hour: Int, minute: Int): String {
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    /**
     * Compute epoch seconds from hour and minute, using the stored date.
     */
    private fun computeEpochFromTime(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, dateYear)
        cal.set(Calendar.MONTH, dateMonth)
        cal.set(Calendar.DAY_OF_MONTH, dateDay)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000L
    }

    /**
     * Show time spinner dialog for selecting hour and minute.
     * Uses the same style as MetadataFormManager for consistency.
     */
    private fun showTimeSpinnerDialog(isBegin: Boolean) {
        val currentHour = if (isBegin) begintijdHour else eindtijdHour
        val currentMinute = if (isBegin) begintijdMinute else eindtijdMinute

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 8)
        }

        val hourPicker = NumberPicker(requireContext()).apply {
            minValue = 0
            maxValue = 23
            value = currentHour
            wrapSelectorWheel = true
        }
        
        val minutePicker = NumberPicker(requireContext()).apply {
            minValue = 0
            maxValue = 59
            value = currentMinute
            wrapSelectorWheel = true
            setFormatter { v -> v.toString().padStart(2, '0') }
        }

        // Auto-adjust hour when rolling over from 59 to 0 or 0 to 59
        var lastMinute = currentMinute
        minutePicker.setOnValueChangedListener { _, _, newVal ->
            if (newVal == 0 && lastMinute == 59) {
                hourPicker.value = (hourPicker.value + 1) % 24
            } else if (newVal == 59 && lastMinute == 0) {
                hourPicker.value = if (hourPicker.value == 0) 23 else hourPicker.value - 1
            }
            lastMinute = newVal
        }

        row.addView(hourPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(minutePicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.afrond_time_picker_title))
            .setView(row)
            .setPositiveButton("OK") { _, _ ->
                if (isBegin) {
                    begintijdHour = hourPicker.value
                    begintijdMinute = minutePicker.value
                } else {
                    eindtijdHour = hourPicker.value
                    eindtijdMinute = minutePicker.value
                }
                updateTimeDisplay()
            }
            .setNegativeButton(getString(R.string.dlg_cancel), null)
            .show()
    }
}
