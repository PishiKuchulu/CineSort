package com.cinesort.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.util.dirRefOf
import com.cinesort.core.data.db.DestinationEntity
import com.cinesort.core.data.metadata.MetadataService
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.SecretStore
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.usecase.EnsureLibraryRootsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val destination: DestinationEntity? = null,
    val sourcesCount: Int = 0,
    val rootsCreated: List<String> = emptyList(),
    val rootsFound: List<String> = emptyList(),
    val tmdbKey: String = "",
    val omdbKey: String = "",
    val tmdbTestMessage: String? = null,
    val omdbTestMessage: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val secretStore: SecretStore,
    private val metadataService: MetadataService,
    storageGateway: StorageGateway,
) : ViewModel() {

    private val ensureRoots = EnsureLibraryRootsUseCase(storageGateway)
    private val rootsCreated = MutableStateFlow<List<String>>(emptyList())
    private val rootsFound = MutableStateFlow<List<String>>(emptyList())
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
        .combine(tmdbTestMessage) { state, message -> state.copy(tmdbTestMessage = message) }
        .combine(omdbTestMessage) { state, message -> state.copy(omdbTestMessage = message) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OnboardingUiState(),
        )

    fun onDestinationPicked(uriString: String, name: String) {
        viewModelScope.launch {
            libraryRepository.setDestination(uriString, name)
            val result = ensureRoots(dirRefOf(uriString, name))
            rootsCreated.value = result.created
            rootsFound.value = result.found
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

    fun onTmdbKeyChange(value: String) {
        tmdbTestMessage.value = null
        viewModelScope.launch { secretStore.setTmdbKey(value) }
    }

    fun onOmdbKeyChange(value: String) {
        omdbTestMessage.value = null
        viewModelScope.launch { secretStore.setOmdbKey(value) }
    }

    fun clearTmdbKey() {
        viewModelScope.launch { secretStore.setTmdbKey("") }
    }

    fun clearOmdbKey() {
        viewModelScope.launch { secretStore.setOmdbKey("") }
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
}
