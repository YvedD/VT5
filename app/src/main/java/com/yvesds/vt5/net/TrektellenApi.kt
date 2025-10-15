package com.yvesds.vt5.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Eenvoudige client om de metadata-header te POSTen naar
 *   {baseUrl}/api/counts_save?language=...&versie=...
 *
 * Auth: Basic (zoals bij je download usecase). We sturen géén naam= of ww= in de query.
 */
object TrektellenApi {

    private val client by lazy { OkHttpClient() }
    private val JSON: String = "application/json; charset=utf-8"

    /**
     * @param jsonBody  Het *volledige JSON array* (als String), vb. van StartTelling.buildJsonEnvelopeFromUi(...)
     * @return Pair<ok:Boolean, responseBody:String>. Bij fouten krijg je de servertekst terug.
     */
    suspend fun postCountsSave(
        baseUrl: String,
        language: String,
        versie: String,
        username: String,
        password: String,
        jsonBody: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {

        val url = "$baseUrl/api/counts_save?language=$language&versie=$versie"

        val authHeader = basic(username, password)
        val body = jsonBody.toRequestBody(JSON.toMediaType())

        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .header("Content-Type", JSON)
            .post(body)
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                (resp.isSuccessful) to text
            }
        }.getOrElse { ex ->
            false to ("EXCEPTION: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }

    private fun basic(user: String, pass: String): String {
        val creds = "$user:$pass"
        val b64 = Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $b64"
    }
}
