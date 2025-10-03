package com.yvesds.vt5.features.opstart.usecases

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * Downloader voor server-JSON bestanden.
 *
 * Voor nu is dit een nette "stub" die altijd false teruggeeft. Zodra jij
 * de endpoints en bestandsnamen doorgeeft, vullen we:
 *  - HTTP-client (OkHttp of HttpURLConnection)
 *  - authenticatie (basic of token; we lezen uit CredentialsStore)
 *  - write via ContentResolver naar DocumentFile (serverdata-map)
 */
object ServerJsonDownloader {

    /**
     * Download alle relevante JSON-bestanden naar de gegeven 'serverdata' map.
     * Retourneert true bij succes.
     */
    fun downloadAll(context: Context, serverdataDir: DocumentFile?): Boolean {
        if (serverdataDir == null || !serverdataDir.isDirectory) return false

        // TODO: implementeren zodra endpoints bekend zijn:
        // - Voorbeeld:
        //   download("https://trektellen.nl/api/codes.json", "codes.json", serverdataDir)
        //   download("https://trektellen.nl/api/site_species.json", "site_species.json", serverdataDir)
        //   ...
        return false
    }
}
