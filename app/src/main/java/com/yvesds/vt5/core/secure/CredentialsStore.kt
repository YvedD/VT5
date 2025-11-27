package com.yvesds.vt5.core.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Versleutelde opslag voor Trektellen credentials.
 * - save() overschrijft bestaande waarden (bewuste keus: gebruiker kan altijd nieuwe login invullen).
 * - Herstelt automatisch bij AEADBadTagException (na device reset of key mismatch).
 */
class CredentialsStore(context: Context) {

    companion object {
        private const val TAG = "CredentialsStore"
        private const val PREFS_FILE_NAME = "vt5_secure_prefs"
        private const val KEY_USER = "trektellen_user"
        private const val KEY_PASS = "trektellen_pass"
    }

    private val appContext = context.applicationContext

    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = createEncryptedPrefs()

    fun save(username: String, password: String) {
        val result = prefs.edit()
            .putString(KEY_USER, username)
            .putString(KEY_PASS, password)
            .commit()  // Use commit() instead of apply() for EncryptedSharedPreferences to ensure synchronous save
        Log.i(TAG, "Credentials save: ${if (result) "success" else "failed"}")
    }

    fun getUsername(): String? {
        val username = prefs.getString(KEY_USER, null)
        Log.d(TAG, "getUsername(): ${if (username.isNullOrBlank()) "absent" else "present"}")
        return username
    }
    
    fun getPassword(): String? {
        val password = prefs.getString(KEY_PASS, null)
        Log.d(TAG, "getPassword(): ${if (password.isNullOrBlank()) "absent" else "present"}")
        return password
    }

    fun hasCredentials(): Boolean = !getUsername().isNullOrEmpty() && !getPassword().isNullOrEmpty()

    fun clear() {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).commit()  // Use commit() for synchronous save
        Log.i(TAG, "Credentials cleared")
    }
    }

    /**
     * Creates EncryptedSharedPreferences, with fallback recovery if the encryption
     * keys are mismatched (e.g., after device reset or app data corruption).
     */
    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            // AEADBadTagException (subclass of GeneralSecurityException), KeyStoreException, etc.
            // This happens when the MasterKey changed (device reset) but old encrypted
            // data still exists. We must clear the corrupted data and retry.
            Log.w(TAG, "EncryptedSharedPreferences failed due to security error, clearing corrupted data: ${e.message}")
            recoverFromCorruptedPrefs()
        } catch (e: IOException) {
            // IO errors during decryption can also indicate corrupted data
            Log.w(TAG, "EncryptedSharedPreferences failed due to IO error, clearing corrupted data: ${e.message}")
            recoverFromCorruptedPrefs()
        }
    }

    /**
     * Attempts to recover from corrupted preferences by clearing the file and retrying.
     * If recovery fails, falls back to in-memory preferences.
     */
    private fun recoverFromCorruptedPrefs(): SharedPreferences {
        clearCorruptedPrefsFile()
        
        return try {
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If retry also fails, use fallback unencrypted in-memory storage
            // This is a last-resort to prevent app crashes
            Log.e(TAG, "Recovery failed, using fallback storage: ${e.message}")
            FallbackPreferences()
        }
    }

    /**
     * Deletes the corrupted SharedPreferences file from disk.
     */
    private fun clearCorruptedPrefsFile() {
        try {
            val prefsDir = File(appContext.filesDir.parent, "shared_prefs")
            val prefsFile = File(prefsDir, "$PREFS_FILE_NAME.xml")
            if (prefsFile.exists()) {
                prefsFile.delete()
                Log.i(TAG, "Deleted corrupted prefs file: ${prefsFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete corrupted prefs file: ${e.message}")
        }
    }

    /**
     * In-memory fallback when encrypted preferences cannot be created.
     * Credentials stored here will not persist across app restarts.
     */
    private class FallbackPreferences : SharedPreferences {
        private val data = mutableMapOf<String, String?>()

        override fun getAll(): Map<String, *> = data.toMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                key?.let { pending[it] = value }
                return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let { removals.add(it) }
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                data.clear()
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                removals.forEach { data.remove(it) }
                data.putAll(pending)
            }
        }
    }
}
