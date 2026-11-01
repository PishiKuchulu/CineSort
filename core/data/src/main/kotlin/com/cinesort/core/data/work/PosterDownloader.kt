package com.cinesort.core.data.work

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a matched poster image for the optional "poster in folder"
 * feature. Best effort by design: any failure returns null and the organize
 * run carries on — a poster must never fail a move.
 */
object PosterDownloader {

    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val TIMEOUT_MS = 15_000

    suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // TMDB's metadata API hands out w185 thumbnails; folders deserve w500.
            val effective = url.replace("image.tmdb.org/t/p/w185/", "image.tmdb.org/t/p/w500/")
            val connection = URL(effective).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "CineSort-Android")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("poster fetch HTTP %d", connection.responseCode)
                return@withContext null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) null else bytes
        } catch (e: Exception) {
            Timber.w(e, "poster fetch failed")
            null
        }
    }
}
