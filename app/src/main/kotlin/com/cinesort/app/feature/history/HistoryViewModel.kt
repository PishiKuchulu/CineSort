package com.cinesort.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.util.dirRefOf
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.settings.AppSettings
import com.cinesort.core.domain.execute.UndoExecutor
import com.cinesort.core.domain.execute.UndoReport
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

/** One history card: the run plus its journal-derived outcome counts. */
data class HistoryRunUi(
    val run: RunRecord,
    val total: Int,
    val done: Int,
    val failed: Int,
    val skipped: Int = 0,
    val undone: Int = 0,
    val posters: Int = 0,
) {
    val isFinished: Boolean get() = run.finishedAt != null
    val canUndo: Boolean get() = isFinished && !run.undone
}

data class HistoryUiState(
    val runs: List<HistoryRunUi> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val undoReport: UndoReport? = null,
    val undoingRunId: String? = null,
    val isLoading: Boolean = true,
    val hasMore: Boolean = false,
    /** Same central readiness flag as Home (UI/UX wave 1.1.0). */
    val canScan: Boolean = false,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val journalStore: JournalStore,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val storageGateway: StorageGateway,
    private val undoCoordinator: com.cinesort.app.orchestration.UndoCoordinator,
) : ViewModel() {

    private val runs = MutableStateFlow<List<HistoryRunUi>>(emptyList())
    private val undoReport = MutableStateFlow<UndoReport?>(null)
    private val undoingRunId = MutableStateFlow<String?>(null)
    private val isLoading = MutableStateFlow(true)
    private val hasMore = MutableStateFlow(false)

    /** How many runs the list currently asks the journal for (audit A.1-4). */
    private var runsLimit = PAGE_SIZE

    val uiState: StateFlow<HistoryUiState> = combine(
        runs,
        settingsRepository.settings,
        undoReport,
        undoingRunId,
        isLoading,
    ) { runList, settings, report, undoing, loading ->
        HistoryUiState(
            runs = runList,
            settings = settings,
            undoReport = report,
            undoingRunId = undoing,
            isLoading = loading,
        )
    }.combine(hasMore) { state, more -> state.copy(hasMore = more) }
        .combine(
            combine(
                libraryRepository.destination,
                libraryRepository.sources,
            ) { destination, sources -> destination != null && sources.isNotEmpty() },
        ) { state, ready -> state.copy(canScan = ready) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(),
        )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val recent = journalStore.recentRuns(runsLimit)
            hasMore.value = recent.size >= runsLimit
            runs.value = recent.map { record ->
                // Counting-size summary (section-3 audit): the cards
                // need tallies only, not every operation record of every
                // listed run. One counting definition for Report/History/
                // Run (audit B.9): posters never inflate done, UNDONE is
                // not failed.
                val summary = journalStore.operationSummary(record.id)
                val counts = com.cinesort.core.domain.journal.countFromItemBestStates(
                    summary,
                    runFinished = record.finishedAt != null,
                )
                HistoryRunUi(
                    run = record,
                    total = record.totalItems.takeIf { it > 0 }
                        ?: summary.bestStateByItem.size,
                    done = counts.done,
                    failed = counts.failed,
                    skipped = counts.skipped,
                    undone = counts.undone,
                    posters = counts.posters,
                )
            }
            isLoading.value = false
        }
    }

    /** History used to hard-stop at the 10 newest runs (audit A.1-4). */
    fun loadMore() {
        runsLimit += PAGE_SIZE
        refresh()
    }

    /**
     * Gives an unfinished run its final state from History (audit B.9):
     * the same close the worker applies when a later run starts, on
     * demand — the card stops being an action-less dead end.
     */
    fun closeAsInterrupted(runId: String) {
        viewModelScope.launch {
            com.cinesort.core.domain.journal.JournalMaintenance.closeInterruptedRun(
                journalStore,
                runId,
                System.currentTimeMillis(),
            )
            refresh()
        }
    }

    fun undo(runId: String) {
        if (undoingRunId.value != null) return
        viewModelScope.launch {
            val record = journalStore.run(runId) ?: return@launch
            // The run's own destination when recorded (audit B.8), under
            // the process-wide undo lock.
            val destinationRoot = if (record.destinationId.isNotBlank()) {
                dirRefOf(record.destinationId, record.destinationName)
            } else {
                val destination = libraryRepository.destinationOnce() ?: return@launch
                dirRefOf(destination.uriString, destination.name)
            }
            undoingRunId.value = runId
            try {
                undoReport.value = undoCoordinator.withUndoLock {
                    val executor = UndoExecutor(
                        gateway = storageGateway,
                        journal = journalStore,
                        destinationRoot = destinationRoot,
                    )
                    executor.undo(runId)
                }
            } finally {
                undoingRunId.value = null
                refresh()
            }
        }
    }

    fun consumeUndoReport() {
        undoReport.value = null
    }

    private companion object {
        const val PAGE_SIZE = 10
    }
}
