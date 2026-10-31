package com.cinesort.core.domain.scan

import java.util.Locale

data class ScanSettings(
    val videoExtensions: Set<String> = DEFAULT_VIDEO_EXTENSIONS,
    val companionExtensions: Set<String> = DEFAULT_COMPANION_EXTENSIONS,
    val ignoreSamples: Boolean = true,
    val minFileAgeSeconds: Long = 120,
    val includeSubfolders: Boolean = true,
    val maxDepth: Int = 5,
    val moveCompanions: Boolean = true,
) {
    companion object {
        val DEFAULT_VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg",
            "mpeg", "3gp",
        )
        val DEFAULT_COMPANION_EXTENSIONS = setOf("srt", "ass", "ssa", "sub", "vtt", "idx")
        val TEMP_EXTENSIONS = setOf("part", "crdownload", "tmp", "download", "!ut", "aria2")
        const val SAMPLE_SIZE_LIMIT: Long = 100L * 1024 * 1024
    }
}

enum class ScanDisposition {
    VIDEO,
    COMPANION_CANDIDATE,
    IGNORED_TEMP,
    IGNORED_HIDDEN,
    IGNORED_SAMPLE,
    IGNORED_EXTENSION,
    RECENTLY_MODIFIED,
}

/** Pure scan-rule classification (spec section 5.3). Times are epoch millis. */
object ScanRules {
    fun classify(
        name: String,
        size: Long,
        lastModified: Long,
        now: Long,
        settings: ScanSettings,
    ): ScanDisposition {
        if (name.startsWith(".")) return ScanDisposition.IGNORED_HIDDEN
        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".cinesort-part")) return ScanDisposition.IGNORED_TEMP
        val ext = lower.substringAfterLast('.', "")
        if (ext in ScanSettings.TEMP_EXTENSIONS) return ScanDisposition.IGNORED_TEMP
        val isVideo = ext in settings.videoExtensions
        val isCompanion = ext in settings.companionExtensions
        if (!isVideo && !isCompanion) return ScanDisposition.IGNORED_EXTENSION
        if (isVideo) {
            if (settings.ignoreSamples && size < ScanSettings.SAMPLE_SIZE_LIMIT &&
                hasSampleToken(name)
            ) {
                return ScanDisposition.IGNORED_SAMPLE
            }
            val ageMillis = settings.minFileAgeSeconds * 1000
            if (ageMillis > 0 && now - lastModified < ageMillis) {
                return ScanDisposition.RECENTLY_MODIFIED
            }
            return ScanDisposition.VIDEO
        }
        return ScanDisposition.COMPANION_CANDIDATE
    }

    /** A `sample` token delimited by separators — not a substring of a longer word. */
    fun hasSampleToken(name: String): Boolean {
        val tokens = name.lowercase(Locale.ROOT).split(Regex("""[\s._\-()\[\]]+"""))
        return tokens.any { it == "sample" }
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /**
     * A companion shares the video's directory and its name starts with the video's base
     * name followed by `.` (spec section 5.4): `Movie.fa.srt`, `Movie.en.forced.srt`.
     */
    fun isCompanionOf(
        candidateName: String,
        videoName: String,
        settings: ScanSettings,
    ): Boolean {
        if (extensionOf(candidateName) !in settings.companionExtensions) return false
        val videoBase = videoName.substringBeforeLast('.', videoName)
        return candidateName.length > videoBase.length &&
            candidateName.startsWith(videoBase) &&
            candidateName[videoBase.length] == '.'
    }
}
