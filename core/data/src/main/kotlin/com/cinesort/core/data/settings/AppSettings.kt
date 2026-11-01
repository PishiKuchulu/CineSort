package com.cinesort.core.data.settings

import com.cinesort.core.domain.naming.NamingOptions
import com.cinesort.core.domain.scan.ScanSettings
import com.cinesort.core.parser.TitleCaseMode
import java.util.Locale

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class RatingSourcePreference { ANY, IMDB_ONLY }

enum class MetadataUnavailableBehavior { CONTINUE, HOLD }

enum class ConflictDefault { ASK, SKIP, KEEP_BOTH }

/**
 * All non-secret settings (spec sections 9, 11). [omdbExhaustedUntil] is an
 * internal field (epoch millis) that hides the OMDb quota state from the UI.
 */
data class AppSettings(
    // General
    val languageCode: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    // Fresh-install defaults match the English default language; picking
    // Persian (onboarding or Settings) turns both on with the language.
    val persianDigits: Boolean = false,
    val jalaliDates: Boolean = false,
    // Naming (mirrors NamingOptions)
    val movieAddYear: Boolean = true,
    val movieAddGenres: Boolean = false,
    val movieAddRating: Boolean = false,
    val seriesAddYear: Boolean = false,
    val seriesAddGenres: Boolean = false,
    val seriesAddRating: Boolean = false,
    val maxGenres: Int = 4,
    val titleCaseMode: TitleCaseMode = TitleCaseMode.SMART,
    val fillMissingYear: Boolean = true,
    // Metadata
    val ratingSource: RatingSourcePreference = RatingSourcePreference.ANY,
    val metadataUnavailable: MetadataUnavailableBehavior = MetadataUnavailableBehavior.CONTINUE,
    val genreNormalization: Boolean = true,
    // Safety & behavior
    val skipPreview: Boolean = false,
    val minFileAgeSeconds: Int = 120,
    val ignoreSamples: Boolean = true,
    val moveCompanions: Boolean = true,
    val conflictDefault: ConflictDefault = ConflictDefault.ASK,
    val removeEmptyFolders: Boolean = false,
    val treatJunkAsEmpty: Boolean = false,
    // Advanced: the full active video-extension list, comma-separated.
    val videoExtensions: String = DEFAULT_VIDEO_EXTENSIONS,
    val extraNoiseWords: String = "",
    val maxScanDepth: Int = 5,
    // Internal: OMDb quota exhaustion, epoch millis (next UTC midnight).
    val omdbExhaustedUntil: Long = 0L,
) {
    companion object {
        const val DEFAULT_VIDEO_EXTENSIONS =
            "mkv,mp4,avi,mov,wmv,flv,webm,m4v,ts,m2ts,mpg,mpeg,3gp"
    }
}

fun AppSettings.toNamingOptions(): NamingOptions = NamingOptions(
    movieAddYear = movieAddYear,
    movieAddGenres = movieAddGenres,
    movieAddRating = movieAddRating,
    seriesAddYear = seriesAddYear,
    seriesAddGenres = seriesAddGenres,
    seriesAddRating = seriesAddRating,
    maxGenres = maxGenres.coerceIn(1, 6),
    fillMissingYear = fillMissingYear,
)

/** Parses the comma-separated [AppSettings.videoExtensions] list. */
fun AppSettings.videoExtensionSet(): Set<String> {
    val parsed = videoExtensions
        .split(',', ' ', ';', '\n')
        .map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .toSet()
    return parsed.ifEmpty { ScanSettings.DEFAULT_VIDEO_EXTENSIONS }
}

fun AppSettings.extraNoiseWordSet(): Set<String> =
    extraNoiseWords
        .split(',', ' ', ';', '\n')
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .toSet()

fun AppSettings.toScanSettings(): ScanSettings = ScanSettings(
    videoExtensions = videoExtensionSet(),
    ignoreSamples = ignoreSamples,
    minFileAgeSeconds = minFileAgeSeconds.coerceIn(0, 600).toLong(),
    maxDepth = maxScanDepth.coerceIn(1, 10),
    moveCompanions = moveCompanions,
)
