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
import com.cinesort.core.domain.journal.OperationState
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
) {
    val isFinished: Boolean get() = run.finishedAt != null
    val canUndo: Boolean get() = isFinished && !run.undone
}

data class HistoryUiState(
    val runs: List<HistoryRunUi> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val undoReport: UndoReport? = null,
    val undoingRunId: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val journalStore: JournalStore,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val storageGateway: StorageGateway,
) : ViewModel() {

    private val runs = MutableStateFlow<List<HistoryRunUi>>(emptyList())
    private val undoReport = MutableStateFlow<UndoReport?>(null)
    private val undoingRunId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HistoryUiState> = combine(
        runs,
        settingsRepository.settings,
        undoReport,
        undoingRunId,
    ) { runList, settings, report, undoing ->
        HistoryUiState(
            runs = runList,
            settings = settings,
            undoReport = report,
            undoingRunId = undoing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState(),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val recent = journalStore.recentRuns(10)
            runs.value = recent.map { record ->
                val ops = journalStore.operationsOf(record.id)
                val mainOps = ops.filter { !it.isCompanion }
                val done = mainOps.count { it.state == OperationState.DONE }
                val failed = if (record.finishedAt != null) {
                    mainOps.count {
                        it.state != OperationState.DONE &&
                            it.state != OperationState.SKIPPED
                    }
                } else {
                    mainOps.count { it.state == OperationState.FAILED }
                }
                HistoryRunUi(
                    run = record,
                    total = record.totalItems.takeIf { it > 0 }
                        ?: mainOps.map { it.itemId }.distinct().size,
                    done = done,
                    failed = failed,
                )
            }
        }
    }

    fun undo(runId: String) {
        if (undoingRunId.value != null) return
        viewModelScope.launch {
            val destination = libraryRepository.destinationOnce() ?: return@launch
            undoingRunId.value = runId
            try {
                val executor = UndoExecutor(
                    gateway = storageGateway,
                    journal = journalStore,
                    destinationRoot = dirRefOf(destination.uriString, destination.name),
                )
                undoReport.value = executor.undo(runId)
            } finally {
                undoingRunId.value = null
                refresh()
            }
        }
    }

    fun consumeUndoReport() {
        undoReport.value = null
    }
}
