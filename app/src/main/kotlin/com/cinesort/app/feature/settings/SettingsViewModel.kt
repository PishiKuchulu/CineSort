package com.cinesort.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.core.data.db.TitleOverrideEntity
import com.cinesort.core.data.di.ApplicationScope
import com.cinesort.core.data.metadata.MetadataService
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.SecretStore
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.repository.StorageHealth
import com.cinesort.core.data.settings.AppSettings
import com.cinesort.core.domain.journal.JournalStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class KeyTestUi(
    val success: Boolean,
    val message: String,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val tmdbKey: String = "",
    val omdbKey: String = "",
    /** Effective value of the extensions field (local while typing). */
    val videoExtensions: String = "",
    /** Effective value of the noise-words field (local while typing). */
    val extraNoiseWords: String = "",
    val tmdbTest: KeyTestUi? = null,
    val omdbTest: KeyTestUi? = null,
    val isTestingTmdb: Boolean = false,
    val isTestingOmdb: Boolean = false,
    val overrides: List<TitleOverrideEntity> = emptyList(),
    val destinationName: String? = null,
    val cacheCleared: Boolean = false,
    /** One-time "storage was recovered" notice (section-4 audit). */
    val storageHealth: StorageHealth = StorageHealth(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val libraryRepository: LibraryRepository,
    private val metadataService: MetadataService,
    private val journalStore: JournalStore,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val tmdbTest = MutableStateFlow<KeyTestUi?>(null)
    private val omdbTest = MutableStateFlow<KeyTestUi?>(null)
    // Local text-field state for API keys (review M8): the field shows this
    // override while typing; the encrypted store is written debounced, so a
    // keystroke no longer round-trips through DataStore + Tink and back into
    // the field (cursor jumps, dropped characters).
    private val tmdbKeyField = MutableStateFlow<String?>(null)
    private val omdbKeyField = MutableStateFlow<String?>(null)
    private var tmdbSaveJob: Job? = null
    private var omdbSaveJob: Job? = null
    // The latest key values the debounce has not written yet (review M-I).
    // Kept outside the jobs so an explicit flush — or onCleared — can write
    // them even when the viewModelScope job is cancelled first.
    private var pendingTmdbKey: String? = null
    private var pendingOmdbKey: String? = null
    // The two Advanced free-text fields get the same treatment as the API
    // keys (audit B.10): they used to write settings on EVERY keystroke,
    // and the flow echo re-set the field — the user's edited suffix visibly
    // vanished for a frame after each character.
    private val extensionsField = MutableStateFlow<String?>(null)
    private val noiseWordsField = MutableStateFlow<String?>(null)
    private var extensionsSaveJob: Job? = null
    private var noiseWordsSaveJob: Job? = null
    private var pendingExtensions: String? = null
    private var pendingNoiseWords: String? = null
    private val testingTmdb = MutableStateFlow(false)
    private val testingOmdb = MutableStateFlow(false)
    private val cacheCleared = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        secretStore.tmdbKey,
        secretStore.omdbKey,
        libraryRepository.overrides,
        libraryRepository.destination,
    ) { settings, tmdbKey, omdbKey, overrides, destination ->
        SettingsUiState(
            settings = settings,
            tmdbKey = tmdbKey,
            omdbKey = omdbKey,
            videoExtensions = settings.videoExtensions,
            extraNoiseWords = settings.extraNoiseWords,
            overrides = overrides,
            destinationName = destination?.name,
        )
    }.combine(tmdbTest) { state, test -> state.copy(tmdbTest = test) }
        .combine(omdbTest) { state, test -> state.copy(omdbTest = test) }
        .combine(testingTmdb) { state, testing -> state.copy(isTestingTmdb = testing) }
        .combine(testingOmdb) { state, testing -> state.copy(isTestingOmdb = testing) }
        .combine(cacheCleared) { state, cleared -> state.copy(cacheCleared = cleared) }
        .combine(tmdbKeyField) { state, field ->
            if (field != null) state.copy(tmdbKey = field) else state
        }
        .combine(omdbKeyField) { state, field ->
            if (field != null) state.copy(omdbKey = field) else state
        }
        .combine(extensionsField) { state, field ->
            if (field != null) state.copy(videoExtensions = field) else state
        }
        .combine(noiseWordsField) { state, field ->
            if (field != null) state.copy(extraNoiseWords = field) else state
        }
        .combine(settingsRepository.storageHealth) { state, health ->
            state.copy(storageHealth = health)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    fun update(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(block) }
    }

    /** Dismisses the one-time storage-recovery notice (section-4 audit). */
    fun dismissStorageHealthNotice() {
        settingsRepository.dismissStorageHealthNotice()
    }

    /** Extensions field edit: local state now, settings write debounced (audit B.10). */
    fun onVideoExtensionsChange(value: String) {
        extensionsField.value = value
        pendingExtensions = value
        extensionsSaveJob?.cancel()
        extensionsSaveJob = viewModelScope.launch {
            delay(KEY_SAVE_DEBOUNCE_MS)
            flushExtensions()
        }
    }

    /** Noise-words field edit: local state now, settings write debounced (audit B.10). */
    fun onExtraNoiseWordsChange(value: String) {
        noiseWordsField.value = value
        pendingNoiseWords = value
        noiseWordsSaveJob?.cancel()
        noiseWordsSaveJob = viewModelScope.launch {
            delay(KEY_SAVE_DEBOUNCE_MS)
            flushNoiseWords()
        }
    }

    private suspend fun flushExtensions() {
        val value = pendingExtensions ?: return
        pendingExtensions = null
        settingsRepository.update { it.copy(videoExtensions = value) }
        extensionsField.value = null
    }

    private suspend fun flushNoiseWords() {
        val value = pendingNoiseWords ?: return
        pendingNoiseWords = null
        settingsRepository.update { it.copy(extraNoiseWords = value) }
        noiseWordsField.value = null
    }

    /** Writes any debounced Advanced-field edits right away (leaving the screen). */
    suspend fun flushPendingSettingsFields() {
        extensionsSaveJob?.cancel()
        noiseWordsSaveJob?.cancel()
        flushExtensions()
        flushNoiseWords()
    }

    /** Edits a saved correction's title/year in place (audit A.1-5). */
    fun updateOverride(id: Long, title: String, year: String) {
        viewModelScope.launch {
            libraryRepository.updateOverride(id, title.trim(), year.trim())
        }
    }

    fun onTmdbKeyChange(value: String) {
        tmdbTest.value = null
        tmdbKeyField.value = value
        pendingTmdbKey = value
        tmdbSaveJob?.cancel()
        tmdbSaveJob = viewModelScope.launch {
            delay(KEY_SAVE_DEBOUNCE_MS)
            flushTmdbKey()
        }
    }

    fun onOmdbKeyChange(value: String) {
        omdbTest.value = null
        omdbKeyField.value = value
        pendingOmdbKey = value
        omdbSaveJob?.cancel()
        omdbSaveJob = viewModelScope.launch {
            delay(KEY_SAVE_DEBOUNCE_MS)
            flushOmdbKey()
        }
    }

    fun clearTmdbKey() {
        tmdbTest.value = null
        tmdbSaveJob?.cancel()
        pendingTmdbKey = null
        tmdbKeyField.value = ""
        viewModelScope.launch {
            secretStore.setTmdbKey("")
            tmdbKeyField.value = null
        }
    }

    fun clearOmdbKey() {
        omdbTest.value = null
        omdbSaveJob?.cancel()
        pendingOmdbKey = null
        omdbKeyField.value = ""
        viewModelScope.launch {
            secretStore.setOmdbKey("")
            omdbKeyField.value = null
        }
    }

    private suspend fun flushTmdbKey() {
        val value = pendingTmdbKey ?: return
        pendingTmdbKey = null
        secretStore.setTmdbKey(value)
        tmdbKeyField.value = null
    }

    private suspend fun flushOmdbKey() {
        val value = pendingOmdbKey ?: return
        pendingOmdbKey = null
        secretStore.setOmdbKey(value)
        omdbKeyField.value = null
    }

    /**
     * Writes any debounced key edits to the encrypted store right away
     * (review M-I). Anything about to read the stored key — the Test
     * actions here, a scan elsewhere — must see the key as typed, not the
     * one from before the debounce window.
     */
    suspend fun flushPendingKeys() {
        tmdbSaveJob?.cancel()
        omdbSaveJob?.cancel()
        flushTmdbKey()
        flushOmdbKey()
    }

    override fun onCleared() {
        // viewModelScope is cancelled as part of clearing, so the flush
        // runs on the application scope: leaving this screen inside the
        // 800 ms debounce window no longer loses the typed key (review M-I)
        // — or the typed Advanced-field edit (audit B.10).
        appScope.launch {
            flushPendingKeys()
            flushPendingSettingsFields()
        }
        super.onCleared()
    }

    fun testTmdbKey() {
        val key = uiState.value.tmdbKey
        if (key.isBlank()) return
        viewModelScope.launch {
            // Test the key as typed: flush the debounced save first so the
            // stored key (what a scan reads) matches it (review M-I).
            flushPendingKeys()
            testingTmdb.value = true
            try {
                val result = metadataService.testTmdbKey(key)
                tmdbTest.value = result.fold(
                    onSuccess = { KeyTestUi(success = true, message = it) },
                    onFailure = {
                        KeyTestUi(success = false, message = it.message ?: it.javaClass.simpleName)
                    },
                )
            } finally {
                testingTmdb.value = false
            }
        }
    }

    fun testOmdbKey() {
        val key = uiState.value.omdbKey
        if (key.isBlank()) return
        viewModelScope.launch {
            flushPendingKeys()
            testingOmdb.value = true
            try {
                val result = metadataService.testOmdbKey(key)
                omdbTest.value = result.fold(
                    onSuccess = { KeyTestUi(success = true, message = it) },
                    onFailure = {
                        KeyTestUi(success = false, message = it.message ?: it.javaClass.simpleName)
                    },
                )
            } finally {
                testingOmdb.value = false
            }
        }
    }

    fun clearMetadataCache() {
        viewModelScope.launch {
            metadataService.clearCache()
            cacheCleared.value = true
        }
    }

    fun consumeCacheCleared() {
        cacheCleared.value = false
    }

    fun deleteOverride(id: Long) {
        viewModelScope.launch { libraryRepository.deleteOverride(id) }
    }

    /** Re-saves a just-deleted correction (the delete Snackbar's Undo). */
    fun restoreOverride(entity: com.cinesort.core.data.db.TitleOverrideEntity) {
        viewModelScope.launch {
            libraryRepository.saveOverride(
                com.cinesort.core.domain.planner.TitleOverride(
                    normalizedKey = entity.normalizedKey,
                    type = runCatching {
                        com.cinesort.core.domain.planner.ItemType.valueOf(entity.type)
                    }.getOrDefault(com.cinesort.core.domain.planner.ItemType.MOVIE),
                    correctedTitle = entity.correctedTitle,
                    correctedYear = entity.correctedYear,
                ),
            )
        }
    }

    /**
     * Builds the redacted diagnostics summary (spec 11 group 6 / section 14):
     * settings and recent runs only — API keys are never included.
     */
    suspend fun buildDiagnostics(header: String): String {
        val state = uiState.value
        val settings = state.settings
        val runs = journalStore.recentRuns(10)
        val builder = StringBuilder()
        builder.appendLine(header)
        builder.appendLine("version: " + com.cinesort.app.BuildConfig.VERSION_NAME)
        builder.appendLine("language: " + (settings.languageCode ?: "default"))
        builder.appendLine("theme: " + settings.themeMode.name)
        builder.appendLine("dynamicColor: " + settings.dynamicColor)
        builder.appendLine("persianDigits: " + settings.persianDigits)
        builder.appendLine("jalaliDates: " + settings.jalaliDates)
        builder.appendLine("destination: " + (state.destinationName ?: "-"))
        builder.appendLine("movieAddYear: " + settings.movieAddYear)
        builder.appendLine("movieAddGenres: " + settings.movieAddGenres)
        builder.appendLine("movieAddRating: " + settings.movieAddRating)
        builder.appendLine("seriesAddYear: " + settings.seriesAddYear)
        builder.appendLine("seriesAddGenres: " + settings.seriesAddGenres)
        builder.appendLine("seriesAddRating: " + settings.seriesAddRating)
        builder.appendLine("maxGenres: " + settings.maxGenres)
        builder.appendLine("titleCaseMode: " + settings.titleCaseMode.name)
        builder.appendLine("fillMissingYear: " + settings.fillMissingYear)
        builder.appendLine("ratingSource: " + settings.ratingSource.name)
        builder.appendLine("metadataUnavailable: " + settings.metadataUnavailable.name)
        builder.appendLine("skipPreview: " + settings.skipPreview)
        builder.appendLine("minFileAgeSeconds: " + settings.minFileAgeSeconds)
        builder.appendLine("ignoreSamples: " + settings.ignoreSamples)
        builder.appendLine("moveCompanions: " + settings.moveCompanions)
        builder.appendLine("conflictDefault: " + settings.conflictDefault.name)
        builder.appendLine("removeEmptyFolders: " + settings.removeEmptyFolders)
        builder.appendLine("treatJunkAsEmpty: " + settings.treatJunkAsEmpty)
        builder.appendLine("videoExtensions: " + settings.videoExtensions)
        builder.appendLine("extraNoiseWords: " + settings.extraNoiseWords)
        builder.appendLine("maxScanDepth: " + settings.maxScanDepth)
        builder.appendLine("savedCorrections: " + state.overrides.size)
        builder.appendLine("recentRuns: " + runs.size)
        runs.forEach { run ->
            builder.appendLine(
                "run " + run.id + " startedAt=" + run.startedAt +
                    " finished=" + (run.finishedAt != null) +
                    " items=" + run.totalItems + " undone=" + run.undone,
            )
        }
        return builder.toString()
    }

    private companion object {
        const val KEY_SAVE_DEBOUNCE_MS = 800L
    }
}
