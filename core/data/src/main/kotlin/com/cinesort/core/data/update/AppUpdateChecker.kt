package com.cinesort.core.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** A newer signed release published on GitHub, with its APK asset. */
data class AppUpdateInfo(
    val version: String,
    val tag: String,
    val apkName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** The release's published `<apk>.sha256` asset, when present. */
    val sha256Url: String? = null,
    /** The expected hash value, fetched best-effort so the update dialog
     * can display it for out-of-band comparison. */
    val expectedSha256: String? = null,
)

/** Strict `vX.Y.Z` version parsing/comparison for release tags. */
internal object VersionNumbers {

    fun parse(tag: String): List<Int>? {
        val core = tag.trim().removePrefix("v").removePrefix("V")
        val parts = core.split('.')
        if (parts.size != 3) return null
        return parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until 3) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return false
    }
}

/**
 * In-app update check against this app's own distribution channel — the
 * versioned GitHub releases of PishiKuchulu/CineSort (ported from Anvex's
 * updater). Deliberately NOT the `/releases/latest` endpoint: the list is
 * scanned for strict `vX.Y.Z` tags and the highest wins, so a rolling or
 * stray release can never shadow a versioned one. No token needed (public
 * repo); nothing leaves the device beyond the request itself.
 */
object AppUpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/PishiKuchulu/CineSort/releases?per_page=20"
    private const val USER_AGENT = "CineSort-Updater"

    /**
     * The only hosts a release-download redirect may land on: github.com
     * itself (no redirect) and GitHub's release-asset CDN hosts. Exact
     * matches only, so lookalike suffix domains fail.
     */
    internal fun isAllowedUpdateRedirectHost(host: String?): Boolean =
        host == "github.com" ||
            host == "objects.githubusercontent.com" ||
            host == "release-assets.githubusercontent.com"

    /** Fetch the expected SHA-256 text for [info] (best-effort; null on failure). */
    suspend fun fetchExpectedSha256(info: AppUpdateInfo): String? =
        withContext(Dispatchers.IO) {
            val url = info.sha256Url?.takeIf { it.isNotBlank() } ?: return@withContext null
            runCatching {
                requireCineSortReleaseUrl(url)
                httpGet(url).trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
                    ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            }.getOrNull()
        }

    /** The newest release strictly newer than [currentVersion], or null. */
    suspend fun fetchLatestNewerThan(currentVersion: String): AppUpdateInfo? =
        withContext(Dispatchers.IO) {
            val releases = JSONArray(httpGet(RELEASES_URL))
            var best: AppUpdateInfo? = null
            for (i in 0 until releases.length()) {
                val rel = releases.optJSONObject(i) ?: continue
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
                val tag = rel.optString("tag_name")
                val version = VersionNumbers.parse(tag)?.joinToString(".") ?: continue
                if (!VersionNumbers.isNewer(version, currentVersion)) continue
                val current = best
                if (current != null &&
                    !VersionNumbers.isNewer(version, current.version)
                ) {
                    continue
                }
                val asset = pickAsset(rel.optJSONArray("assets"), version) ?: continue
                val shaAsset = pickShaAsset(
                    rel.optJSONArray("assets"),
                    asset.optString("name"),
                )
                best = AppUpdateInfo(
                    version = version,
                    tag = tag,
                    apkName = asset.optString("name"),
                    downloadUrl = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size"),
                    sha256Url = shaAsset?.optString("browser_download_url")
                        ?.takeIf { it.isNotBlank() },
                )
            }
            best
        }

    private fun pickShaAsset(assets: JSONArray?, apkName: String): JSONObject? {
        if (assets == null || apkName.isBlank()) return null
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            if (asset.optString("name") == "$apkName.sha256") return asset
        }
        return null
    }

    private fun pickAsset(assets: JSONArray?, version: String): JSONObject? {
        if (assets == null) return null
        var fallback: JSONObject? = null
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            val name = asset.optString("name")
            if (name == "CineSort-$version.apk") return asset
            if (fallback == null && name.endsWith(".apk")) fallback = asset
        }
        return fallback
    }

    /**
     * Downloads [url] to [dest] (via a `.part` sibling, renamed on success),
     * reporting (downloaded, total) progress. The checksum is MANDATORY (a
     * release without a published `.sha256` asset is refused), both URLs
     * must be CineSort release-download URLs, and the hash is computed in
     * the same pass as the download. A cancelled or failed attempt leaves
     * only the `.part` file, which the caller removes.
     */
    suspend fun download(
        url: String,
        dest: File,
        sha256Url: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        requireCineSortReleaseUrl(url)
        require(!sha256Url.isNullOrBlank()) {
            "Update has no published checksum — refusing to install."
        }
        requireCineSortReleaseUrl(sha256Url)
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val code = conn.responseCode
                check(code in 200..299) { "Download failed (HTTP $code)" }
                val finalHost = conn.url.host
                check(isAllowedUpdateRedirectHost(finalHost)) {
                    "Update download redirected to an unexpected host: $finalHost"
                }
                val total = conn.contentLengthLong
                dest.parentFile?.mkdirs()
                val part = File(dest.parentFile, dest.name + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            downloaded += n
                            onProgress(downloaded, if (total > 0) total else downloaded)
                        }
                        if (total > 0) {
                            check(downloaded == total) {
                                "Download incomplete ($downloaded of $total bytes)."
                            }
                        }
                    }
                }
                val expected = httpGet(sha256Url).trim()
                    .split(Regex("\\s+")).firstOrNull()?.lowercase()
                check(!expected.isNullOrBlank() && expected.matches(Regex("[0-9a-f]{64}"))) {
                    "Could not read a valid update checksum."
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expected) {
                    "Update checksum mismatch — refusing to install a corrupted or tampered file."
                }
                if (dest.exists()) dest.delete()
                check(part.renameTo(dest)) { "Could not finalize the downloaded update" }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * The download URL must be one of this app's own release assets: the
     * release list comes from api.github.com, but its
     * `browser_download_url` fields are data, not trust.
     */
    private fun requireCineSortReleaseUrl(url: String) {
        val u = runCatching { URL(url) }.getOrNull()
        require(
            u != null && u.protocol == "https" && u.host == "github.com" &&
                u.path.startsWith("/PishiKuchulu/CineSort/releases/download/"),
        ) {
            "Unexpected update URL host or path: $url"
        }
    }

    /**
     * Android verifies the installer signature against the INSTALLED app's
     * key only at install time; compare the downloaded APK's signers with
     * the running app's signers BEFORE firing the installer, so a
     * mismatched APK never reaches it. Returns false (refuse) when either
     * side's signers cannot be read.
     */
    fun verifySameSigner(context: Context, apkFile: File): Boolean {
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, flag)
                ?: return false
            val archiveCerts = signerCertBytes(pm, archive)
            val installed = pm.getPackageInfo(context.packageName, flag)
            val installedCerts = signerCertBytes(pm, installed)
            archiveCerts.isNotEmpty() && archiveCerts == installedCerts
        }.getOrDefault(false)
    }

    private fun signerCertBytes(
        pm: PackageManager,
        info: android.content.pm.PackageInfo,
    ): Set<List<Byte>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signers = info.signingInfo?.apkContentsSigners
            if (!signers.isNullOrEmpty()) {
                return signers.map { it.toByteArray().toList() }.toSet()
            }
        }
        @Suppress("DEPRECATION")
        val legacy = info.signatures
        if (!legacy.isNullOrEmpty()) {
            return legacy.map { it.toByteArray().toList() }.toSet()
        }
        return emptySet()
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) { "Update check failed (HTTP $code)" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
