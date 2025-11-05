package com.yvesds.vt5.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * API-client voor Trektellen.
 *
 * - Auth: HTTP Basic (zelfde als in InstallatieScherm login-test)
 * - Endpoint: /api/counts_save?language=dutch&versie=1845
 * - Body: JSON array met 1 envelope (ServerTellingEnvelope)
 */
object TrektellenApi {

    private val client: OkHttpClient by lazy { OkHttpClient() }
    private val json: Json by lazy { Json { ignoreUnknownKeys = true; explicitNulls = false } }

    /**
     * POST /api/counts_save
     *
     * @param baseUrl  bv. "https://trektellen.nl"
     * @param language bv. "dutch"
     * @param versie   bv. "1845"
     * @param username basic-auth user (uit CredentialsStore / checkuser.json)
     * @param password basic-auth pass (uit CredentialsStore / checkuser.json)
     * @param envelope lijst met precies 1 ServerTellingEnvelope (metadata + lege data)
     *
     * @return Pair(ok, responseBodyText)
     */
    suspend fun postCountsSave(
        baseUrl: String,
        language: String,
        versie: String,
        username: String,
        password: String,
        envelope: List<ServerTellingEnvelope>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val url = try {
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("counts_save")
                .addQueryParameter("language", language)
                .addQueryParameter("versie", versie)
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext false to "Bad baseUrl: $baseUrl (${e.message})"
        }

        // JSON body (array met 1 envelope)
        val bodyJson = json.encodeToString(
            ListSerializer(ServerTellingEnvelope.serializer()),
            envelope
        )

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody: RequestBody = bodyJson.toRequestBody(mediaType)

        val authHeader = Credentials.basic(username, password)

        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .post(requestBody)
            .build()

        client.newCall(req).execute().use { resp ->
            val respText = resp.body?.string().orEmpty()
            (resp.isSuccessful) to respText
        }
    }

    /**
     * POST /api/data_save/{onlineId}
     *
     * Post a JSON array with a single ServerTellingDataItem to the data_save endpoint.
     *
     * @return Pair(ok, responseBodyText)
     */
    suspend fun postDataSaveSingle(
        baseUrl: String,
        onlineId: String,
        username: String,
        password: String,
        item: ServerTellingDataItem
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val url = try {
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("data_save")
                .addPathSegment(onlineId)
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext false to "Bad baseUrl/onlineId: $baseUrl / $onlineId (${e.message})"
        }

        val bodyJson = json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), listOf(item))

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody: RequestBody = bodyJson.toRequestBody(mediaType)

        val authHeader = Credentials.basic(username, password)

        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .post(requestBody)
            .build()

        client.newCall(req).execute().use { resp ->
            val respText = resp.body?.string().orEmpty()
            (resp.isSuccessful) to respText
        }
    }
}