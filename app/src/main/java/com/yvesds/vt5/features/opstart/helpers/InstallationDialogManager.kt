package com.yvesds.vt5.features.opstart.helpers

import android.app.Dialog
import android.graphics.Color
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yvesds.vt5.core.ui.ProgressDialogHelper

/**
 * Helper class voor dialog management tijdens installatie.
 * 
 * Verantwoordelijkheden:
 * - Progress dialogs (via ProgressDialogHelper)
 * - Info dialogs
 * - Error dialogs
 * - Dialog styling voor zonlicht leesbaarheid
 * 
 * Gebruik:
 * ```kotlin
 * val dialogManager = InstallationDialogManager(activity)
 * val progressDialog = dialogManager.showProgress("Bezig...")
 * dialogManager.updateProgress(progressDialog, "Nieuwe status...")
 * dialogManager.dismissProgress(progressDialog)
 * ```
 */
class InstallationDialogManager(
    private val activity: AppCompatActivity
) {
    /**
     * Toon een progress dialog.
     * 
     * @param message Te tonen boodschap
     * @return De gecreëerde dialog
     */
    fun showProgress(message: String): Dialog {
        return ProgressDialogHelper.show(activity, message)
    }
    
    /**
     * Update de boodschap in een progress dialog.
     * 
     * @param dialog De dialog om te updaten
     * @param message Nieuwe boodschap
     */
    fun updateProgress(dialog: Dialog, message: String) {
        ProgressDialogHelper.updateMessage(dialog, message)
    }
    
    /**
     * Dismiss een progress dialog veilig (null-safe).
     * 
     * @param dialog De dialog om te dismissen, mag null zijn
     */
    fun dismissProgress(dialog: Dialog?) {
        try {
            dialog?.dismiss()
        } catch (e: Exception) {
            // Ignore errors during dismiss
        }
    }
    
    /**
     * Toon een info dialog met OK button.
     * 
     * @param title Dialog titel
     * @param message Dialog boodschap
     * @param onDismiss Optionele callback bij sluiten
     */
    fun showInfo(
        title: String,
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                onDismiss?.invoke()
            }
            .create()
        
        dialog.show()
        styleDialogTextForSunlight(dialog)
    }
    
    /**
     * Toon een error dialog met OK button.
     * 
     * @param title Dialog titel
     * @param message Error boodschap
     * @param onDismiss Optionele callback bij sluiten
     */
    fun showError(
        title: String,
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                onDismiss?.invoke()
            }
            .create()
        
        dialog.show()
        styleDialogTextForSunlight(dialog)
    }
    
    /**
     * Toon een confirmation dialog met Ja/Nee buttons.
     * 
     * @param title Dialog titel
     * @param message Confirmation boodschap
     * @param onConfirm Callback bij "Ja"
     * @param onCancel Optionele callback bij "Nee"
     */
    fun showConfirmation(
        title: String,
        message: String,
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Ja") { _, _ ->
                onConfirm()
            }
            .setNegativeButton("Nee") { _, _ ->
                onCancel?.invoke()
            }
            .create()
        
        dialog.show()
        styleDialogTextForSunlight(dialog)
    }
    
    /**
     * Style dialog text to white voor betere leesbaarheid in zonlicht.
     * 
     * Dit is een workaround voor Material3 dark theme die zwarte tekst
     * kan tonen op donkere achtergrond, wat onleesbaar is in fel zonlicht.
     * 
     * @param dialog De AlertDialog om te stylen
     */
    fun styleDialogTextForSunlight(dialog: AlertDialog) {
        try {
            // Style title
            val titleId = activity.resources.getIdentifier("alertTitle", "id", "android")
            if (titleId > 0) {
                dialog.findViewById<TextView>(titleId)?.setTextColor(Color.WHITE)
            }
            
            // Style message
            val messageId = android.R.id.message
            dialog.findViewById<TextView>(messageId)?.setTextColor(Color.WHITE)
            
            // Style buttons
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.WHITE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.WHITE)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.WHITE)
        } catch (e: Exception) {
            // Ignore styling errors - dialog will still be functional
        }
    }
}
