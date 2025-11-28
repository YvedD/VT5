package com.yvesds.vt5.core.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Opslag voor Trektellen credentials.
 * - Probeert EncryptedSharedPreferences te gebruiken voor veilige opslag.
 * - Valt terug op gewone SharedPreferences als encryptie niet werkt.
 * - save() overschrijft bestaande waarden (bewuste keus: gebruiker kan altijd nieuwe login invullen).
 * 
 * BELANGRIJK: Gebruikt een gecachte SharedPreferences instantie in de companion object
 * om te garanderen dat dezelfde prefs instantie wordt gebruikt tijdens de hele levenscyclus.
 * Dit voorkomt inconsistenties tussen EncryptedSharedPreferences en fallback prefs.
 */
class CredentialsStore(context: Context) {

    companion object {
        private const val TAG = "CredentialsStore"
        private const val PREFS_FILE_NAME = "vt5_secure_prefs"
        private const val FALLBACK_PREFS_FILE_NAME = "vt5_credentials"
        private const val KEY_USER = "trektellen_user"
        private const val KEY_PASS = "trektellen_pass"
        
        // Cached SharedPreferences instantie - garandeert consistentie
        @Volatile
        private var cachedPrefs: SharedPreferences? = null
        private val lock = Any()
        
        /**
         * Get or create the SharedPreferences instance.
         * Uses double-checked locking for thread safety.
         */
        private fun getOrCreatePrefs(context: Context): SharedPreferences {
            cachedPrefs?.let { return it }
            
            synchronized(lock) {
                cachedPrefs?.let { return it }
                
                val appContext = context.applicationContext
                val prefs = createPreferencesInternal(appContext)
                cachedPrefs = prefs
                return prefs
            }
        }
        
        /**
         * Creates preferences, trying encrypted first, then falling back to regular SharedPreferences.
         */
        private fun createPreferencesInternal(appContext: Context): SharedPreferences {
            Log.i(TAG, "Creating preferences (singleton)...")
            
            // First, try EncryptedSharedPreferences
            val encryptedPrefs = tryCreateEncryptedPrefsInternal(appContext)
            if (encryptedPrefs != null) {
                return encryptedPrefs
            }
            
            // Fallback to regular SharedPreferences (credentials still saved, just not encrypted)
            Log.w(TAG, "Using fallback regular SharedPreferences (unencrypted but persistent)")
            return appContext.getSharedPreferences(FALLBACK_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        }
        
        /**
         * Attempts to create EncryptedSharedPreferences.
         * Returns null if encryption is not available on this device.
         */
        private fun tryCreateEncryptedPrefsInternal(appContext: Context): SharedPreferences? {
            // First, try to create MasterKey
            val masterKey = try {
                MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "MasterKey creation failed: ${e.javaClass.simpleName} - ${e.message}")
                return null
            }
            
            // Try to create EncryptedSharedPreferences
            return try {
                val prefs = EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                Log.i(TAG, "EncryptedSharedPreferences created successfully")
                prefs
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences creation failed: ${e.javaClass.simpleName} - ${e.message}")
                
                // Try to clear corrupted data and retry once
                clearCorruptedPrefsFileInternal(appContext)
                
                try {
                    val prefs = EncryptedSharedPreferences.create(
                        appContext,
                        PREFS_FILE_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    Log.i(TAG, "EncryptedSharedPreferences created after recovery")
                    prefs
                } catch (e2: Exception) {
                    Log.w(TAG, "EncryptedSharedPreferences recovery failed: ${e2.javaClass.simpleName} - ${e2.message}")
                    null
                }
            }
        }
        
        /**
         * Deletes the corrupted SharedPreferences file from disk.
         */
        private fun clearCorruptedPrefsFileInternal(appContext: Context) {
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
    }

    private val prefs: SharedPreferences = getOrCreatePrefs(context)

    fun save(username: String, password: String) {
        Log.i(TAG, "Saving credentials - username: ${if (username.isNotBlank()) "present" else "blank"}, password: ${if (password.isNotBlank()) "present" else "blank"}")
        val result = prefs.edit()
            .putString(KEY_USER, username)
            .putString(KEY_PASS, password)
            .commit()  // Use commit() for synchronous save
        Log.i(TAG, "Credentials save: ${if (result) "success" else "failed"}")
        
        // Verify by immediately reading back
        val savedUsername = prefs.getString(KEY_USER, null)
        val savedPassword = prefs.getString(KEY_PASS, null)
        Log.i(TAG, "Verification - username saved: ${if (savedUsername == username) "OK" else "MISMATCH"}, password saved: ${if (savedPassword == password) "OK" else "MISMATCH"}")
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
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).commit()
        Log.i(TAG, "Credentials cleared")
    }
}
