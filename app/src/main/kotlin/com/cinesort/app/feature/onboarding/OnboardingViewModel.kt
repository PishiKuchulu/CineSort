package com.cinesort.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.util.dirRefOf
import com.cinesort.core.data.db.DestinationEntity
import com.cinesort.core.data.di.ApplicationScope
import com.cinesort.core.data.metadata.MetadataService
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.SecretStore
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.usecase.EnsureLibraryRootsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val languageCode: String = "en",
    val destination: DestinationEntity? = null,
    val sourcesCount: Int = 0,
    val rootsCreated: List<String> = emptyList(),
    val rootsFound: List<String> = emptyList(),
    val rootsError: String? = null,
    val tmdbKey: String = "",
    val omdbKey: String = "",
    val tmdbTestMessage: String? = null,
    val omdbTestMessage: String? = null,
    val tmdbTestSuccess: Boolean? = null,
    val omdbTestSuccess: Boolean? = null,
    val isTestingTmdb: Boolean = false,
    val isTestingOmdb: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val secretStore: SecretStore,
    private val metadataService: MetadataService,
    storageGateway: StorageGateway,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val ensureRoots = EnsureLibraryRootsUseCase(storageGateway)
    private val rootsCreated = MutableStateFlow<List<String>>(emptyList())
    private val rootsFound = MutableStateFlow<List<String>>(emptyList())
    private val rootsError = MutableStateFlow<String?>(null)
    private val tmdbKeyField = MutableStateFlow<String?>(null)
    private val omdbKeyField = MutableStateFlow<String?>(null)
    private var tmdbSaveJob: Job? = null
    private var omdbSaveJob: Job? = null
    // The latest key values the debounce has not written yet (review M-I).
    private var pendingTmdbKey: String? = null
    private var pendingOmdbKey: String? = null
    private val tmdbTestMessage = MutableStateFlow<String?>(null)
    private val omdbTestMessage = MutableStateFlow<String?>(null)
    private val tmdbTestSuccess = MutableStateFlow<Boolean?>(null)
    private val omdbTestSuccess = MutableStateFlow<Boolean?>(null)
    private val isTestingTmdb = MutableStateFlow(false)
    private val isTestingOmdb = MutableStateFlow(false)

    val uiState: StateFlow<OnboardingUiState> = combine(
        libraryRepository.destination,
        libraryRepository.sources,
        secretStore.tmdbKey,
        secretStore.omdbKey,
    ) { destination, sources, tmdbKey, omdbKey ->
        OnboardingUiState(
            destination = destination,
            sourcesCount = sources.size,
            tmdbKey = tmdbKey,
            omdbKey = omdbKey,
        )
    }.combine(rootsCreated) { state, created -> state.copy(rootsCreated = created) }
        .combine(rootsFound) { state, found -> state.copy(rootsFound = found) }
        .combine(rootsError) { state, error -> state.copy(rootsError = error) }
        .combine(tmdbKeyField) { state, field ->
            if (field != null) state.copy(tmdbKey = field) else state
        }
        .combine(omdbKeyField) { state, field ->
            if (field != null) state.copy(omdbKey = field) else state
        }
        .combine(tmdbTestMessage) { state, message -> state.copy(tmdbTestMessage = message) }
        .combine(omdbTestMessage) { state, message -> state.copy(omdbTestMessage = message) }
        .combine(tmdbTestSuccess) { state, value -> state.copy(tmdbTestSuccess = value) }
        .combine(omdbTestSuccess) { state, value -> state.copy(omdbTestSuccess = value) }
        .combine(isTestingTmdb) { state, value -> state.copy(isTestingTmdb = value) }
        .combine(isTestingOmdb) { state, value -> state.copy(isTestingOmdb = value) }
        .combine(settingsRepository.settings) { state, settings ->
            state.copy(languageCode = settings.languageCode ?: "en")
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OnboardingUiState(),
        )

    /**
     * Welcome-step language pick: persists the choice together with its
     * digit/date conventions (Persian → Persian digits + Jalali dates,
     * English → Latin digits + Gregorian dates). The screen applies the
     * per-app locale itself, mirroring the Settings language chips.
     */
    fun onLanguagePicked(code: String) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    languageCode = code,
                    persianDigits = code == "fa",
                    jalaliDates = code == "fa",
                )
            }
        }
    }

    fun onDestinationPicked(uriString: String, name: String) {
        viewModelScope.launch {
            // Bootstrap first, persist only on success (review H3).
            try {
                val result = ensureRoots(dirRefOf(uriString, name))
                libraryRepository.setDestination(uriString, name)
                rootsCreated.value = result.created
                rootsFound.value = result.found
                rootsError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Review H-A: same widening as SourcesViewModel — any bootstrap
                // failure surfaces as the roots error, never as a crash.
                rootsError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun onSourcePicked(uriString: String, name: String) {
        viewModelScope.launch {
            libraryRepository.addSource(
                uriString = uriString,
                name = name,
                includeSubfolders = true,
                // The one shared default (audit B.1): this literal used to
                // be a second, drifting copy of SourcesViewModel's.
                maxDepth = com.cinesort.app.feature.sources.SourcesViewModel
                    .DEFAULT_SOURCE_DEPTH,
            )
        }
    }

    // API-key fields keep local state while typing (review M8); the encrypted
    // store is written debounced instead of on every keystroke.
    fun onTmdbKeyChange(value: String) {
        tmdbTestMessage.value = null
        tmdbTestSuccess.value = null
        tmdbKeyField.value = value
        pendingTmdbKey = value
        tmdbSaveJob?.cancel()
        tmdbSaveJob = viewModelScope.launch {
            delay(KEY_SAVE_DEBOUNCE_MS)
            flushTmdbKey()
        }
    }

    fun onOmdbKeyChange(value: String) {
        omdbTestMessage.value = null
        omdbTestSuccess.value = null
        omdbKeyField.value = value
        pendingOmdbKey = value
        omdbSaveJob?.cancel()
        omdbSaveJob = viewModelScope.launch {
            delay(KEY_SAVE_DEBOUNCE_MS)
            flushOmdbKey()
        }
    }

    fun clearTmdbKey() {
        tmdbSaveJob?.cancel()
        pendingTmdbKey = null
        tmdbKeyField.value = ""
        viewModelScope.launch {
            secretStore.setTmdbKey("")
            tmdbKeyField.value = null
        }
    }

    fun clearOmdbKey() {
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
     * (review M-I), so the Test actions — and any later reader of the
     * stored key — see the key as typed.
     */
    suspend fun flushPendingKeys() {
        tmdbSaveJob?.cancel()
        omdbSaveJob?.cancel()
        flushTmdbKey()
        flushOmdbKey()
    }

    override fun onCleared() {
        // viewModelScope is cancelled as part of clearing, so the flush
        // runs on the application scope: finishing onboarding inside the
        // 800 ms debounce window no longer loses the typed key (review M-I).
        appScope.launch { flushPendingKeys() }
        super.onCleared()
    }

    fun testTmdbKey() {
        val key = uiState.value.tmdbKey
        if (key.isBlank()) return
        viewModelScope.launch {
            flushPendingKeys()
            isTestingTmdb.value = true
            try {
                metadataService.testTmdbKey(key).fold(
                    onSuccess = {
                        tmdbTestSuccess.value = true
                        tmdbTestMessage.value = it
                    },
                    onFailure = {
                        tmdbTestSuccess.value = false
                        tmdbTestMessage.value =
                            it.message ?: it.javaClass.simpleName
                    },
                )
            } finally {
                isTestingTmdb.value = false
            }
        }
    }

    fun testOmdbKey() {
        val key = uiState.value.omdbKey
        if (key.isBlank()) return
        viewModelScope.launch {
            flushPendingKeys()
            isTestingOmdb.value = true
            try {
                metadataService.testOmdbKey(key).fold(
                    onSuccess = {
                        omdbTestSuccess.value = true
                        omdbTestMessage.value = it
                    },
                    onFailure = {
                        omdbTestSuccess.value = false
                        omdbTestMessage.value =
                            it.message ?: it.javaClass.simpleName
                    },
                )
            } finally {
                isTestingOmdb.value = false
            }
        }
    }

    private companion object {
        const val KEY_SAVE_DEBOUNCE_MS = 800L
    }
}
