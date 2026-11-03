package com.cinesort.app.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.util.dirRefOf
import com.cinesort.core.data.db.DestinationEntity
import com.cinesort.core.data.db.SourceFolderEntity
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.usecase.EnsureLibraryRootsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RootsResultUi(
    val created: List<String>,
    val found: List<String>,
    val error: String? = null,
)

data class SourcesUiState(
    val destination: DestinationEntity? = null,
    val destinationValid: Boolean = true,
    val destinationFreeBytes: Long? = null,
    val sources: List<SourceFolderEntity> = emptyList(),
    val sourceValidity: Map<Long, Boolean> = emptyMap(),
    val rootsResult: RootsResultUi? = null,
    val sourceAlreadyExists: Boolean = false,
    val destinationUndoWarning: Boolean = false,
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val storageGateway: StorageGateway,
    private val journalStore: com.cinesort.core.domain.journal.JournalStore,
) : ViewModel() {

    private val ensureRoots = EnsureLibraryRootsUseCase(storageGateway)

    private val destinationValid = MutableStateFlow(true)
    private val destinationFree = MutableStateFlow<Long?>(null)
    private val sourceValidity = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    private val rootsResult = MutableStateFlow<RootsResultUi?>(null)
    private val sourceAlreadyExists = MutableStateFlow(false)
    private val destinationUndoWarning = MutableStateFlow(false)

    val uiState: StateFlow<SourcesUiState> = combine(
        libraryRepository.destination,
        libraryRepository.sources,
        destinationValid,
        sourceValidity,
    ) { destination, sources, destValid, validity ->
        SourcesUiState(
            destination = destination,
            destinationValid = destValid,
            sources = sources,
            sourceValidity = validity,
        )
    }.combine(destinationFree) { state, free ->
        state.copy(destinationFreeBytes = free)
    }.combine(rootsResult) { state, roots ->
        state.copy(rootsResult = roots)
    }.combine(sourceAlreadyExists) { state, value ->
        state.copy(sourceAlreadyExists = value)
    }.combine(destinationUndoWarning) { state, value ->
        state.copy(destinationUndoWarning = value)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SourcesUiState(),
    )

    init {
        viewModelScope.launch {
            libraryRepository.destination.collect { destination ->
                if (destination == null) {
                    destinationValid.value = true
                    destinationFree.value = null
                } else {
                    val ref = dirRefOf(destination.uriString, destination.name)
                    destinationValid.value = storageGateway.isValid(ref)
                    destinationFree.value = runCatching {
                        storageGateway.freeBytes(ref)
                    }.getOrNull()
                }
            }
        }
        viewModelScope.launch {
            libraryRepository.sources.collect { sources ->
                val checked = sources.associate { source ->
                    source.id to storageGateway.isValid(
                        dirRefOf(source.uriString, source.name),
                    )
                }
                sourceValidity.value = checked
            }
        }
    }

    /** Destination picked: persist it, then bootstrap the Movie / Tv series roots. */
    fun onDestinationPicked(uriString: String, name: String) {
        viewModelScope.launch {
            // The destination this replaces (audit A.1-2): runs recorded
            // before the run record carried its own destination resolve
            // Undo against the CURRENT destination — moving the root makes
            // those undos aim at the wrong tree, and the user must be told.
            val previousUri = libraryRepository.destinationOnce()?.uriString
            // Bootstrap first, persist only on success (review H3): a bare launch
            // used to crash the app when ensureRoots threw, after the destination
            // had already been saved.
            try {
                val result = ensureRoots(dirRefOf(uriString, name))
                libraryRepository.setDestination(uriString, name)
                rootsResult.value = RootsResultUi(
                    created = result.created,
                    found = result.found,
                )
                if (previousUri != null && previousUri != uriString) {
                    val legacyUndos = journalStore.recentRuns(50).any { run ->
                        run.finishedAt != null && !run.undone &&
                            run.destinationId.isBlank()
                    }
                    if (legacyUndos) destinationUndoWarning.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Review H-A: a provider SecurityException or a malformed-URI
                // IllegalArgumentException must surface the same error state as
                // an IOException, not crash the app via viewModelScope.
                rootsResult.value = RootsResultUi(
                    created = emptyList(),
                    found = emptyList(),
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    fun onSourcePicked(uriString: String, name: String) {
        viewModelScope.launch {
            val result = libraryRepository.addSource(
                uriString = uriString,
                name = name,
                includeSubfolders = true,
                maxDepth = DEFAULT_SOURCE_DEPTH,
            )
            // A duplicate pick used to look like nothing happened
            // (audit B.3); the screen shows a message now.
            if (result.alreadyExisted) sourceAlreadyExists.value = true
        }
    }

    /** Removes the destination grant entirely (audit A.1-2). */
    fun onClearDestination() {
        viewModelScope.launch { libraryRepository.clearDestination() }
    }

    fun consumeSourceAlreadyExists() {
        sourceAlreadyExists.value = false
    }

    fun consumeDestinationUndoWarning() {
        destinationUndoWarning.value = false
    }

    fun onRemoveSource(id: Long) {
        viewModelScope.launch { libraryRepository.removeSource(id) }
    }

    fun onSourceOptionsChanged(id: Long, includeSubfolders: Boolean, maxDepth: Int) {
        viewModelScope.launch {
            libraryRepository.updateSourceOptions(id, includeSubfolders, maxDepth)
        }
    }

    fun consumeRootsResult() {
        rootsResult.value = null
    }

    fun refreshValidity() {
        viewModelScope.launch {
            val state = uiState.value
            state.destination?.let { destination ->
                val ref = dirRefOf(destination.uriString, destination.name)
                destinationValid.value = storageGateway.isValid(ref)
                destinationFree.value = runCatching {
                    storageGateway.freeBytes(ref)
                }.getOrNull()
            }
            sourceValidity.update {
                state.sources.associate { source ->
                    source.id to storageGateway.isValid(
                        dirRefOf(source.uriString, source.name),
                    )
                }
            }
        }
    }

    companion object {
        const val DEFAULT_SOURCE_DEPTH = 5
    }
}
