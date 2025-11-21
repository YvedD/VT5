@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.opstart.helpers

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.serverdata.model.CheckUserItem
import com.yvesds.vt5.features.serverdata.model.WrappedJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Helper class voor server authenticatie tijdens installatie.
 * 
 * Verantwoordelijkheden:
 * - Login test uitvoeren via TrektellenAuth
 * - CheckUser response opslaan
 * - Result types voor type-safe handling
 * 
 * Gebruik:
 * ```kotlin
 * val authManager = ServerAuthenticationManager(context)
 * val result = authManager.testLogin(username, password)
 * when (result) {
 *     is AuthResult.Success -> // handle success
 *     is AuthResult.Failure -> // handle error
 * }
 * ```
 */
class ServerAuthenticationManager(
    private val context: Context
) {
    private val jsonPretty = Json { 
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    
    companion object {
        private const val TAG = "ServerAuthManager"
        const val PREF_USER_FULLNAME = "pref_user_fullname"
    }
    
    /**
     * Sealed class voor authenticatie resultaten.
     */
    sealed class AuthResult {
        /**
         * Authenticatie succesvol.
         * @param response De server response als String
         */
        data class Success(val response: String) : AuthResult()
        
        /**
         * Authenticatie mislukt.
         * @param error Foutmelding
         */
        data class Failure(val error: String) : AuthResult()
    }
    
    /**
     * Test login credentials tegen de trektellen.nl server.
     * 
     * @param username Gebruikersnaam
     * @param password Wachtwoord
     * @param language Taal (default: "dutch")
     * @param versie Versie (default: "1845")
     * @return AuthResult met succes of foutmelding
     */
    suspend fun testLogin(
        username: String,
        password: String,
        language: String = "dutch",
        versie: String = "1845"
    ): AuthResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Testing login for user: $username")
            
            val result = TrektellenAuth.checkUser(
                username = username,
                password = password,
                language = language,
                versie = versie
            )
            
            result.fold(
                onSuccess = { response ->
                    Log.d(TAG, "Login test successful")
                    AuthResult.Success(response)
                },
                onFailure = { error ->
                    Log.w(TAG, "Login test failed: ${error.message}", error)
                    AuthResult.Failure(error.message ?: "Onbekende fout bij login test")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during login test: ${e.message}", e)
            AuthResult.Failure(e.message ?: "Onbekende fout")
        }
    }
    
    /**
     * Sla checkuser response op in SAF (assetsDir/checkuser.json).
     * 
     * @param assetsDir De assets directory (Documents/VT5/assets)
     * @param response De server response als pretty JSON string
     * @return true als opslaan gelukt is
     */
    suspend fun saveCheckUserResponse(
        assetsDir: DocumentFile?,
        response: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (assetsDir == null) {
            Log.w(TAG, "Cannot save checkuser.json: assets directory is null")
            return@withContext false
        }
        
        try {
            val existingFile = assetsDir.findFile("checkuser.json")
            val file = existingFile ?: assetsDir.createFile("application/json", "checkuser.json")
            
            if (file == null) {
                Log.w(TAG, "Failed to create checkuser.json file")
                return@withContext false
            }
            
            context.contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                outputStream.write(response.toByteArray())
                Log.d(TAG, "checkuser.json saved successfully")
                true
            } ?: run {
                Log.w(TAG, "Failed to open output stream for checkuser.json")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving checkuser.json: ${e.message}", e)
            false
        }
    }
    
    /**
     * Extraheer fullname uit checkuser response en sla op in SharedPreferences.
     * Dit maakt de fullname direct beschikbaar zonder file I/O.
     * 
     * @param response De server response als JSON string
     */
    fun saveFullnameToPreferences(response: String) {
        try {
            // Reuse existing jsonPretty which has ignoreUnknownKeys enabled
            val wrapped = jsonPretty.decodeFromString<WrappedJson<CheckUserItem>>(response)
            val fullname = wrapped.json.firstOrNull()?.fullname
            
            if (!fullname.isNullOrBlank()) {
                com.yvesds.vt5.VT5App.prefs().edit {
                    putString(PREF_USER_FULLNAME, fullname)
                }
                Log.d(TAG, "Fullname saved to SharedPreferences: $fullname")
            } else {
                Log.w(TAG, "No fullname found in checkuser response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting fullname from checkuser response: ${e.message}", e)
        }
    }
    
    /**
     * Haal fullname op uit SharedPreferences.
     * 
     * @return De opgeslagen fullname, of null als niet beschikbaar
     */
    fun getFullnameFromPreferences(): String? {
        return com.yvesds.vt5.VT5App.prefs().getString(PREF_USER_FULLNAME, null)
    }
    
    /**
     * Generate ISO timestamp voor metadata.
     * 
     * @return ISO 8601 timestamp string
     */
    fun generateIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
