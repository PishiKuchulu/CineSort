package com.cinesort.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.util.dirRefOf
import com.cinesort.core.data.db.DestinationEntity
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.SecretStore
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.settings.AppSettings
import com.cinesort.core.domain.execute.UndoExecutor
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.RunRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    val sourcesCount: Int = 0,
    val anySourceInvalid: Boolean = false,
    val destination: DestinationEntity? = null,
    val destinationValid: Boolean = true,
    val destinationFreeBytes: Long? = null,
    val lastRun: RunRecord? = null,
    val lastRunDoneCount: Int = 0,
    val hasApiKey: Boolean = false,
    val undoResult: UndoResultUi? = null,
    val isUndoing: Boolean = false,
) {
    val canScan: Boolean
        get() = destination != null && sourcesCount > 0
}

data class UndoResultUi(
    val movedBack: Int,
    val problems: List<String>,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val journalStore: JournalStore,
    private val storageGateway: StorageGateway,
) : ViewModel() {

    private val anySourceInvalid = MutableStateFlow(false)
    private val destinationValid = MutableStateFlow(true)
    private val destinationFree = MutableStateFlow<Long?>(null)
    private val lastRun = MutableStateFlow<RunRecord?>(null)
    private val lastRunDoneCount = MutableStateFlow(0)
    private val undoResult = MutableStateFlow<UndoResultUi?>(null)
    private val isUndoing = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        libraryRepository.sources,
        libraryRepository.destination,
        secretStore.tmdbKey,
        secretStore.omdbKey,
    ) { settings, sources, destination, tmdbKey, omdbKey ->
        HomeUiState(
            settings = settings,
            sourcesCount = sources.size,
            destination = destination,
            hasApiKey = tmdbKey.isNotBlank() || omdbKey.isNotBlank(),
        )
    }.combine(anySourceInvalid) { state, invalid ->
        state.copy(anySourceInvalid = invalid)
    }.combine(destinationValid) { state, valid ->
        state.copy(destinationValid = valid)
    }.combine(destinationFree) { state, free ->
        state.copy(destinationFreeBytes = free)
    }.combine(lastRun) { state, run ->
        state.copy(lastRun = run)
    }.combine(lastRunDoneCount) { state, count ->
        state.copy(lastRunDoneCount = count)
    }.combine(undoResult) { state, result ->
        state.copy(undoResult = result)
    }.combine(isUndoing) { state, undoing ->
        state.copy(isUndoing = undoing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch {
            libraryRepository.sources.collect { sources ->
                anySourceInvalid.value = sources.any { source ->
                    !storageGateway.isValid(dirRefOf(source.uriString, source.name))
                }
            }
        }
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
        refreshLastRun()
    }

    private fun refreshLastRun() {
        viewModelScope.launch {
            val recent = journalStore.recentRuns(1)
            val run = recent.firstOrNull()
            lastRun.value = run
            lastRunDoneCount.value = if (run == null) {
                0
            } else {
                journalStore.operationsOf(run.id)
                    .count { it.state == com.cinesort.core.domain.journal.OperationState.DONE }
            }
        }
    }

    fun onGenresChipChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(movieAddGenres = enabled) }
        }
    }

    fun onRatingChipChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(movieAddRating = enabled) }
        }
    }

    /** Undo of the most recent run via the domain [UndoExecutor]. */
    fun onUndoLastRun() {
        val state = uiState.value
        val run = state.lastRun ?: return
        val destination = state.destination ?: return
        if (state.isUndoing) return
        viewModelScope.launch {
            isUndoing.value = true
            try {
                val executor = UndoExecutor(
                    gateway = storageGateway,
                    journal = journalStore,
                    destinationRoot = dirRefOf(destination.uriString, destination.name),
                )
                val report = executor.undo(run.id)
                undoResult.value = UndoResultUi(
                    movedBack = report.movedBack,
                    problems = report.problems,
                )
            } finally {
                isUndoing.value = false
                refreshLastRun()
            }
        }
    }

    fun consumeUndoResult() {
        undoResult.value = null
    }
}
