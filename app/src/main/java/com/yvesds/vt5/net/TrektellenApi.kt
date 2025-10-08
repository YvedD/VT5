@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.yvesds.vt5.net

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object TrektellenApi {

    private val client by lazy { OkHttpClient() }
    private val json by lazy { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    /**
     * POST /api/counts_save?language=...&versie=...
     * Basic Auth via header.
     * Geeft Pair(success, responseBody).
     */
    suspend fun postCountsSave(
        baseUrl: String,
        language: String,
        versie: String,
        username: String,
        password: String,
        envelope: List<ServerTellingEnvelope>
    ): Pair<Boolean, String> {
        val url = "$baseUrl/api/counts_save?language=$language&versie=$versie"

        val payload = json.encodeToString(envelope)
        val body = payload.toRequestBody(mediaJson)

        val creds = "$username:$password"
        val basic = "Basic " + Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", basic)
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            return (resp.isSuccessful) to respBody
        }
    }
}
