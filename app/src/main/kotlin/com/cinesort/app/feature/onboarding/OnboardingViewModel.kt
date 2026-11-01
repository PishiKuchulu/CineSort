package com.cinesort.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.util.dirRefOf
import com.cinesort.core.data.db.DestinationEntity
import com.cinesort.core.data.metadata.MetadataService
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.SecretStore
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.usecase.EnsureLibraryRootsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
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
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val secretStore: SecretStore,
    private val metadataService: MetadataService,
    storageGateway: StorageGateway,
) : ViewModel() {

    private val ensureRoots = EnsureLibraryRootsUseCase(storageGateway)
    private val rootsCreated = MutableStateFlow<List<String>>(emptyList())
    private val rootsFound = MutableStateFlow<List<String>>(emptyList())
    private val rootsError = MutableStateFlow<String?>(null)
    private val tmdbKeyField = MutableStateFlow<String?>(null)
    private val omdbKeyField = MutableStateFlow<String?>(null)
    private var tmdbSaveJob: Job? = null
    private var omdbSaveJob: Job? = null
    private val tmdbTestMessage = MutableStateFlow<String?>(null)
    private val omdbTestMessage = MutableStateFlow<String?>(null)

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
            } catch (e: IOException) {
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
                maxDepth = 5,
            )
        }
    }

    // API-key fields keep local state while typing (review M8); the encrypted
    // store is written debounced instead of on every keystroke.
    fun onTmdbKeyChange(value: String) {
        tmdbTestMessage.value = null
        tmdbKeyField.value = value
        tmdbSaveJob?.cancel()
        tmdbSaveJob = viewModelScope.launch {
            delay(KEY_SAVE_DEBOUNCE_MS)
            secretStore.setTmdbKey(value)
            tmdbKeyField.value = null
        }
    }

    fun onOmdbKeyChange(value: String) {
        omdbTestMessage.value = null
        omdbKeyField.value = value
        omdbSaveJob?.cancel()
        omdbSaveJob = viewModelScope.launch {
            delay(KEY_SAVE_DEBOUNCE_MS)
            secretStore.setOmdbKey(value)
            omdbKeyField.value = null
        }
    }

    fun clearTmdbKey() {
        tmdbSaveJob?.cancel()
        tmdbKeyField.value = ""
        viewModelScope.launch {
            secretStore.setTmdbKey("")
            tmdbKeyField.value = null
        }
    }

    fun clearOmdbKey() {
        omdbSaveJob?.cancel()
        omdbKeyField.value = ""
        viewModelScope.launch {
            secretStore.setOmdbKey("")
            omdbKeyField.value = null
        }
    }

    fun testTmdbKey() {
        val key = uiState.value.tmdbKey
        if (key.isBlank()) return
        viewModelScope.launch {
            tmdbTestMessage.value = metadataService.testTmdbKey(key).fold(
                onSuccess = { it },
                onFailure = { it.message ?: it.javaClass.simpleName },
            )
        }
    }

    fun testOmdbKey() {
        val key = uiState.value.omdbKey
        if (key.isBlank()) return
        viewModelScope.launch {
            omdbTestMessage.value = metadataService.testOmdbKey(key).fold(
                onSuccess = { it },
                onFailure = { it.message ?: it.javaClass.simpleName },
            )
        }
    }

    private companion object {
        const val KEY_SAVE_DEBOUNCE_MS = 800L
    }
}
