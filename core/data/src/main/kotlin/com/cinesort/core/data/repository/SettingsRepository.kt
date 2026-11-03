package com.cinesort.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cinesort.core.data.settings.AppSettings
import com.cinesort.core.data.settings.ConflictDefault
import com.cinesort.core.data.settings.MetadataUnavailableBehavior
import com.cinesort.core.data.settings.RatingSourcePreference
import com.cinesort.core.data.settings.ThemeMode
import com.cinesort.core.parser.TitleCaseMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cinesort_settings",
)

/** DataStore-backed settings (spec sections 11, 12). */
class SettingsRepository(context: Context) : SettingsAccess {

    private val dataStore: DataStore<Preferences> = context.applicationContext.settingsDataStore

    // Recovery signal (section-4 audit): a corrupt DataStore used to fall
    // back to defaults with only a log line — the user could not tell a
    // reset apart from their own choices. The catch below now also sets
    // the persisted flag Settings surfaces as a one-time notice.
    private val storageHealthStore = StorageHealthStore(context)

    /** One-time "storage was recovered" notice state for the Settings UI. */
    val storageHealth: StateFlow<StorageHealth> = storageHealthStore.state

    fun dismissStorageHealthNotice() = storageHealthStore.dismiss()

    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                Timber.w(error, "settings read failed, using defaults")
                storageHealthStore.markSettingsReset()
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toAppSettings() }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(block: (AppSettings) -> AppSettings) {
        // The block reads INSIDE DataStore's serialized edit transaction,
        // so concurrent update{} calls apply one at a time, each on the
        // previous one's result — no lost writes; per field, the
        // last-applied edit wins. That is the whole ordering story
        // (audit B.10: documented, no extra lock added).
        dataStore.edit { prefs ->
            prefs.writeAppSettings(block(prefs.toAppSettings()))
        }
    }

    // ------------------------------------------------------------ mapping

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            languageCode = this[Keys.LANGUAGE_CODE]?.takeIf { it.isNotEmpty() },
            themeMode = enumValueOrNull<ThemeMode>(this[Keys.THEME_MODE]) ?: defaults.themeMode,
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            persianDigits = this[Keys.PERSIAN_DIGITS] ?: defaults.persianDigits,
            jalaliDates = this[Keys.JALALI_DATES] ?: defaults.jalaliDates,
            autoCheckUpdates = this[Keys.AUTO_CHECK_UPDATES] ?: defaults.autoCheckUpdates,
            movieAddYear = this[Keys.MOVIE_ADD_YEAR] ?: defaults.movieAddYear,
            movieAddGenres = this[Keys.MOVIE_ADD_GENRES] ?: defaults.movieAddGenres,
            movieAddRating = this[Keys.MOVIE_ADD_RATING] ?: defaults.movieAddRating,
            seriesAddYear = this[Keys.SERIES_ADD_YEAR] ?: defaults.seriesAddYear,
            seriesAddGenres = this[Keys.SERIES_ADD_GENRES] ?: defaults.seriesAddGenres,
            seriesAddRating = this[Keys.SERIES_ADD_RATING] ?: defaults.seriesAddRating,
            maxGenres = this[Keys.MAX_GENRES] ?: defaults.maxGenres,
            titleCaseMode = enumValueOrNull<TitleCaseMode>(this[Keys.TITLE_CASE_MODE]) ?: defaults.titleCaseMode,
            fillMissingYear = this[Keys.FILL_MISSING_YEAR] ?: defaults.fillMissingYear,
            ratingSource = enumValueOrNull<RatingSourcePreference>(this[Keys.RATING_SOURCE])
                ?: defaults.ratingSource,
            metadataUnavailable = enumValueOrNull<MetadataUnavailableBehavior>(this[Keys.METADATA_UNAVAILABLE])
                ?: defaults.metadataUnavailable,
            genreNormalization = this[Keys.GENRE_NORMALIZATION] ?: defaults.genreNormalization,
            downloadPosters = this[Keys.DOWNLOAD_POSTERS] ?: defaults.downloadPosters,
            skipPreview = this[Keys.SKIP_PREVIEW] ?: defaults.skipPreview,
            minFileAgeSeconds = this[Keys.MIN_FILE_AGE_SECONDS] ?: defaults.minFileAgeSeconds,
            ignoreSamples = this[Keys.IGNORE_SAMPLES] ?: defaults.ignoreSamples,
            moveCompanions = this[Keys.MOVE_COMPANIONS] ?: defaults.moveCompanions,
            conflictDefault = enumValueOrNull<ConflictDefault>(this[Keys.CONFLICT_DEFAULT])
                ?: defaults.conflictDefault,
            removeEmptyFolders = this[Keys.REMOVE_EMPTY_FOLDERS] ?: defaults.removeEmptyFolders,
            treatJunkAsEmpty = this[Keys.TREAT_JUNK_AS_EMPTY] ?: defaults.treatJunkAsEmpty,
            videoExtensions = this[Keys.VIDEO_EXTENSIONS] ?: defaults.videoExtensions,
            extraNoiseWords = this[Keys.EXTRA_NOISE_WORDS] ?: defaults.extraNoiseWords,
            maxScanDepth = this[Keys.MAX_SCAN_DEPTH] ?: defaults.maxScanDepth,
            omdbExhaustedUntil = this[Keys.OMDB_EXHAUSTED_UNTIL] ?: defaults.omdbExhaustedUntil,
        )
    }

    private fun MutablePreferences.writeAppSettings(s: AppSettings) {
        val languageCode = s.languageCode
        if (languageCode == null) {
            remove(Keys.LANGUAGE_CODE)
        } else {
            this[Keys.LANGUAGE_CODE] = languageCode
        }
        this[Keys.THEME_MODE] = s.themeMode.name
        this[Keys.DYNAMIC_COLOR] = s.dynamicColor
        this[Keys.PERSIAN_DIGITS] = s.persianDigits
        this[Keys.JALALI_DATES] = s.jalaliDates
        this[Keys.AUTO_CHECK_UPDATES] = s.autoCheckUpdates
        this[Keys.MOVIE_ADD_YEAR] = s.movieAddYear
        this[Keys.MOVIE_ADD_GENRES] = s.movieAddGenres
        this[Keys.MOVIE_ADD_RATING] = s.movieAddRating
        this[Keys.SERIES_ADD_YEAR] = s.seriesAddYear
        this[Keys.SERIES_ADD_GENRES] = s.seriesAddGenres
        this[Keys.SERIES_ADD_RATING] = s.seriesAddRating
        this[Keys.MAX_GENRES] = s.maxGenres
        this[Keys.TITLE_CASE_MODE] = s.titleCaseMode.name
        this[Keys.FILL_MISSING_YEAR] = s.fillMissingYear
        this[Keys.RATING_SOURCE] = s.ratingSource.name
        this[Keys.METADATA_UNAVAILABLE] = s.metadataUnavailable.name
        this[Keys.GENRE_NORMALIZATION] = s.genreNormalization
        this[Keys.DOWNLOAD_POSTERS] = s.downloadPosters
        this[Keys.SKIP_PREVIEW] = s.skipPreview
        this[Keys.MIN_FILE_AGE_SECONDS] = s.minFileAgeSeconds
        this[Keys.IGNORE_SAMPLES] = s.ignoreSamples
        this[Keys.MOVE_COMPANIONS] = s.moveCompanions
        this[Keys.CONFLICT_DEFAULT] = s.conflictDefault.name
        this[Keys.REMOVE_EMPTY_FOLDERS] = s.removeEmptyFolders
        this[Keys.TREAT_JUNK_AS_EMPTY] = s.treatJunkAsEmpty
        this[Keys.VIDEO_EXTENSIONS] = s.videoExtensions
        this[Keys.EXTRA_NOISE_WORDS] = s.extraNoiseWords
        this[Keys.MAX_SCAN_DEPTH] = s.maxScanDepth
        this[Keys.OMDB_EXHAUSTED_UNTIL] = s.omdbExhaustedUntil
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private object Keys {
        val LANGUAGE_CODE = stringPreferencesKey("language_code")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val PERSIAN_DIGITS = booleanPreferencesKey("persian_digits")
        val JALALI_DATES = booleanPreferencesKey("jalali_dates")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val MOVIE_ADD_YEAR = booleanPreferencesKey("movie_add_year")
        val MOVIE_ADD_GENRES = booleanPreferencesKey("movie_add_genres")
        val MOVIE_ADD_RATING = booleanPreferencesKey("movie_add_rating")
        val SERIES_ADD_YEAR = booleanPreferencesKey("series_add_year")
        val SERIES_ADD_GENRES = booleanPreferencesKey("series_add_genres")
        val SERIES_ADD_RATING = booleanPreferencesKey("series_add_rating")
        val MAX_GENRES = intPreferencesKey("max_genres")
        val TITLE_CASE_MODE = stringPreferencesKey("title_case_mode")
        val FILL_MISSING_YEAR = booleanPreferencesKey("fill_missing_year")
        val RATING_SOURCE = stringPreferencesKey("rating_source")
        val METADATA_UNAVAILABLE = stringPreferencesKey("metadata_unavailable")
        val GENRE_NORMALIZATION = booleanPreferencesKey("genre_normalization")
        val DOWNLOAD_POSTERS = booleanPreferencesKey("download_posters")
        val SKIP_PREVIEW = booleanPreferencesKey("skip_preview")
        val MIN_FILE_AGE_SECONDS = intPreferencesKey("min_file_age_seconds")
        val IGNORE_SAMPLES = booleanPreferencesKey("ignore_samples")
        val MOVE_COMPANIONS = booleanPreferencesKey("move_companions")
        val CONFLICT_DEFAULT = stringPreferencesKey("conflict_default")
        val REMOVE_EMPTY_FOLDERS = booleanPreferencesKey("remove_empty_folders")
        val TREAT_JUNK_AS_EMPTY = booleanPreferencesKey("treat_junk_as_empty")
        val VIDEO_EXTENSIONS = stringPreferencesKey("video_extensions")
        val EXTRA_NOISE_WORDS = stringPreferencesKey("extra_noise_words")
        val MAX_SCAN_DEPTH = intPreferencesKey("max_scan_depth")
        val OMDB_EXHAUSTED_UNTIL = longPreferencesKey("omdb_exhausted_until")
    }
}
