package com.yvesds.vt5.features.speech

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Handler voor volume-key events die het triggeren van spraakherkenning mogelijk maakt.
 * Vangt volume key events af van de Bluetooth HID op de verrekijker.
 */
class VolumeKeyHandler(private val activity: Activity) {

    companion object {
        private const val TAG = "VolumeKeyHandler"
    }

    private var isRegistered = false
    private var onVolumeUpListener: (() -> Unit)? = null

    // Broadcast receiver voor media button events
    private val mediaButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
                // Gebruik de type-safe variant van getParcelableExtra voor verschillende API levels
                val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                }

                keyEvent?.let { event ->
                    if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
                        Log.d(TAG, "Volume up key detected via broadcast")
                        onVolumeUpListener?.invoke()
                        // Consume the event
                        if (isOrderedBroadcast) {
                            abortBroadcast()
                        }
                    }
                }
            }
        }
    }

    /**
     * Registreert de volume key handler om events te ontvangen
     */
    fun register() {
        if (!isRegistered) {
            try {
                // Register for media button events
                val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)
                filter.priority = Integer.MAX_VALUE

                // Fix voor Android 13+ vereiste flag
                activity.registerReceiver(mediaButtonReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

                isRegistered = true
                Log.d(TAG, "VolumeKeyHandler registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error registering volume key handler: ${e.message}", e)
            }
        }
    }

    /**
     * Verwijdert de volume key handler
     */
    fun unregister() {
        if (isRegistered) {
            try {
                activity.unregisterReceiver(mediaButtonReceiver)
                isRegistered = false
                Log.d(TAG, "VolumeKeyHandler unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering volume key handler: ${e.message}", e)
            }
        }
    }

    /**
     * Stelt de callback in die wordt uitgevoerd wanneer een volume up event wordt gedetecteerd
     */
    fun setOnVolumeUpListener(listener: () -> Unit) {
        onVolumeUpListener = listener
    }

    /**
     * Hulpmethode om te controleren of een key event een volume up betreft
     * Dit kan gebruikt worden in de activity's onKeyDown methode
     */
    fun isVolumeUpEvent(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
    }
}