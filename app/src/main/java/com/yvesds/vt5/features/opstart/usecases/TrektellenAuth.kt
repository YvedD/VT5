@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.opstart.usecases

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * /api/checkuser met HTTP Basic Auth.
 */
object TrektellenAuth {

    // Laat client niet 'val' zijn zodat we hem kunnen afsluiten
    private val client = OkHttpClient()

    private val jsonPretty: Json by lazy { Json { prettyPrint = true; prettyPrintIndent = "  " } }
    private val jsonParser: Json by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

    fun checkUser(
        baseUrl: String = "https://trektellen.nl",
        username: String,
        password: String,
        language: String = "dutch",
        versie: String = "1845"
    ): Result<String> {
        return runCatching {
            val url: HttpUrl = HttpUrl.Builder()
                .scheme("https")
                .host("trektellen.nl")
                .addEncodedPathSegments("api/checkuser")
                .addQueryParameter("naam", username)
                .addQueryParameter("ww", password)
                .addQueryParameter("language", language)
                .addQueryParameter("versie", versie)
                .build()

            val raw = "$username:$password"
            val token = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
            val req: Request = Request.Builder()
                .url(url)
                .header("Authorization", "Basic $token")
                .get()
                .build()

            client.newCall(req).execute().use { resp: Response ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@runCatching "HTTP ${resp.code}: ${resp.message}\n$bodyStr"
                prettyJsonOrRaw(bodyStr)
            }
        }
    }

    private fun prettyJsonOrRaw(raw: String): String =
        try {
            val elm: JsonElement = jsonParser.parseToJsonElement(raw)
            jsonPretty.encodeToString(JsonElement.serializer(), elm)
        } catch (_: Exception) {
            raw
        }

    /** Netjes resources vrijgeven. */
    fun shutdown() {
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            client.cache?.close()
        } catch (_: Throwable) {
            // best-effort
        }
    }
}