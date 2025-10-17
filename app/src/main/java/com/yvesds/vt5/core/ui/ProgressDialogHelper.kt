package com.yvesds.vt5.core.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import android.widget.TextView
import androidx.annotation.StringRes
import com.yvesds.vt5.R
import com.yvesds.vt5.databinding.DialogProgressBinding

/**
 * Helper klasse voor een consistente progress dialog doorheen de app.
 *
 * Gebruik:
 * ```
 * // Tonen
 * val progressDialog = ProgressDialogHelper.show(context, "Bezig met laden...")
 *
 * // Verbergen
 * progressDialog.dismiss()
 * ```
 *
 * Of met een coroutine scope:
 * ```
 * ProgressDialogHelper.withProgress(this, "Bezig met laden...") {
 *     // Langdurige operatie
 *     val result = withContext(Dispatchers.IO) {
 *         // I/O operatie
 *     }
 * }
 * ```
 */
object ProgressDialogHelper {

    /**
     * Toont een progress dialog met de opgegeven tekst.
     *
     * @param context De context om de dialog te tonen
     * @param message De tekst die getoond moet worden
     * @return De gecreëerde dialog
     */
    fun show(context: Context, message: String): Dialog {
        val binding = DialogProgressBinding.inflate(LayoutInflater.from(context))

        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(false)
            setContentView(binding.root)
        }

        binding.tvMessage.text = message
        dialog.show()

        return dialog
    }

    /**
     * Toont een progress dialog met de opgegeven tekst resource.
     *
     * @param context De context om de dialog te tonen
     * @param messageResId Resource ID van de tekst die getoond moet worden
     * @return De gecreëerde dialog
     */
    fun show(context: Context, @StringRes messageResId: Int): Dialog {
        return show(context, context.getString(messageResId))
    }

    /**
     * Update de boodschap in een bestaande dialog
     *
     * @param dialog De dialog om te updaten
     * @param message De nieuwe boodschap
     */
    fun updateMessage(dialog: Dialog, message: String) {
        dialog.findViewById<TextView>(R.id.tvMessage)?.text = message
    }

    /**
     * Voert een suspend functie uit met een progress dialog getoond.
     *
     * @param context De context om de dialog te tonen
     * @param message De tekst die getoond moet worden
     * @param block De suspend functie die uitgevoerd moet worden
     */
    suspend inline fun <T> withProgress(
        context: Context,
        message: String,
        crossinline block: suspend () -> T
    ): T {
        val dialog = show(context, message)
        try {
            return block()
        } finally {
            dialog.dismiss()
        }
    }

    /**
     * Voert een suspend functie uit met een progress dialog getoond.
     *
     * @param context De context om de dialog te tonen
     * @param messageResId Resource ID van de tekst die getoond moet worden
     * @param block De suspend functie die uitgevoerd moet worden
     */
    suspend inline fun <T> withProgress(
        context: Context,
        @StringRes messageResId: Int,
        crossinline block: suspend () -> T
    ): T {
        return withProgress(context, context.getString(messageResId), block)
    }
}