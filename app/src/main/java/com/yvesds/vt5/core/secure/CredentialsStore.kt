package com.yvesds.vt5.core.secure

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Versleutelde opslag voor Trektellen credentials.
 * - save() overschrijft bestaande waarden (bewuste keus: gebruiker kan altijd nieuwe login invullen).
 */
class CredentialsStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "vt5_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USER, username)
            .putString(KEY_PASS, password)
            .apply()
    }

    fun getUsername(): String? = prefs.getString(KEY_USER, null)
    fun getPassword(): String? = prefs.getString(KEY_PASS, null)

    fun hasCredentials(): Boolean = !getUsername().isNullOrEmpty() && !getPassword().isNullOrEmpty()

    fun clear() {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    companion object {
        private const val KEY_USER = "trektellen_user"
        private const val KEY_PASS = "trektellen_pass"
    }
}
