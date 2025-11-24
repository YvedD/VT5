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
import kotlinx.coroutines.*

/**
 * Fallback VolumeKeyHandler zonder androidx.media dependency.
 *
 * - Vangt ACTION_MEDIA_BUTTON broadcasts (fallback voor sommige Bluetooth HID).
 * - Biedt isVolumeUpEvent(keyCode) voor legacy Activity.onKeyDown hooks.
 * - Kleine debounce om dubbele triggers te vermijden.
 *
 * Gebruik:
 *  - setOnVolumeUpListener { ... }
 *  - register() in onResume()
 *  - unregister() in onPause()
 */
class VolumeKeyHandler(private val activity: Activity) {

    companion object {
        private const val TAG = "VolumeKeyHandler"
        private const val DEBOUNCE_MS = 300L
    }

    private var isRegistered = false
    private var onVolumeUpListener: (() -> Unit)? = null

    // debounce tracker
    @Volatile
    private var lastTriggerAt = 0L

    // Broadcast receiver voor ACTION_MEDIA_BUTTON (fallback)
    private val mediaButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_MEDIA_BUTTON != intent.action) return

            val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
            }

            keyEvent?.let { event ->
                // alleen DOWN events en geen repeats
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (handleKeyCodes(event)) {
                        if (isOrderedBroadcast) abortBroadcast()
                    }
                }
            }
        }
    }

    /**
     * Registreer: register broadcast fallback.
     * Aanbevolen: aanroepen in Activity.onResume()
     */
    fun register() {
        if (isRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)
            filter.priority = Int.MAX_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(mediaButtonReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ContextCompat.registerReceiver(
                    activity,
                    mediaButtonReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
            isRegistered = true
        } catch (ex: Exception) {
            Log.e(TAG, "Error registering media button receiver: ${ex.message}", ex)
        }
    }

    /**
     * Unregister broadcast receiver.
     * Aanbevolen: aanroepen in Activity.onPause()
     */
    fun unregister() {
        if (!isRegistered) return
        try {
            activity.unregisterReceiver(mediaButtonReceiver)
        } catch (ex: Exception) {
            Log.w(TAG, "unregister receiver failed: ${ex.message}", ex)
        } finally {
            isRegistered = false
        }
    }

    /**
     * Stel callback in die uitgevoerd wordt bij volume-up / equivalente media knop.
     */
    fun setOnVolumeUpListener(listener: () -> Unit) {
        onVolumeUpListener = listener
    }

    /**
     * Legacy helper die gebruikt kan worden in Activity.onKeyDown om volume/media keycodes te detecteren.
     * Retourneert true als het een relevante knop is.
     */
    fun isVolumeUpEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }
    }

    /**
     * Interne handler: wanneer een relevante key gedrukt is, triggert listener met debounce.
     * Retourneert true als event afgevangen en behandeld werd.
     */
    private fun handleKeyCodes(event: KeyEvent): Boolean {
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }

        if (!handled) return false

        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < DEBOUNCE_MS) {
            return true
        }
        lastTriggerAt = now

        try {
            onVolumeUpListener?.invoke()
        } catch (ex: Exception) {
            Log.w(TAG, "onVolumeUpListener threw: ${ex.message}", ex)
        }
        return true
    }
}