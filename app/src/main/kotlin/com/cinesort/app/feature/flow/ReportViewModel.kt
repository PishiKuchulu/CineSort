package com.cinesort.app.feature.flow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.orchestration.OrganizeSession
import com.cinesort.app.util.dirRefOf
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.domain.execute.UndoExecutor
import com.cinesort.core.domain.execute.UndoReport
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
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

data class ReportUiState(
    val run: RunRecord? = null,
    val loaded: Boolean = false,
    val doneCount: Int = 0,
    val skippedCount: Int = 0,
    val failedOps: List<OperationRecord> = emptyList(),
    val totalItems: Int = 0,
    val destinationName: String? = null,
    val destinationUri: String? = null,
    val undoReport: UndoReport? = null,
    val isUndoing: Boolean = false,
    val navigateToRunId: String? = null,
) {
    val failedCount: Int get() = failedOps
        .filter { !it.isCompanion }
        .map { it.itemId }
        .distinct()
        .size
    val canUndo: Boolean
        get() = run?.finishedAt != null && run?.undone == false && !isUndoing
}

/**
 * Report state for one run (spec 10.4 screen 7). An item counts as failed
 * when any of its journal operations is FAILED or was left in a non-terminal
 * state by a finished run — the executor records per-item failures that way.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val session: OrganizeSession,
    private val journalStore: JournalStore,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val storageGateway: StorageGateway,
) : ViewModel() {

    private val run = MutableStateFlow<RunRecord?>(null)
    private val loaded = MutableStateFlow(false)
    private val doneCount = MutableStateFlow(0)
    private val skippedCount = MutableStateFlow(0)
    private val failedOps = MutableStateFlow<List<OperationRecord>>(emptyList())
    private val totalItems = MutableStateFlow(0)
    private val destinationName = MutableStateFlow<String?>(null)
    private val destinationUri = MutableStateFlow<String?>(null)
    private val undoReport = MutableStateFlow<UndoReport?>(null)
    private val isUndoing = MutableStateFlow(false)
    private val navigateToRunId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ReportUiState> = combine(
        run, loaded, doneCount, skippedCount, failedOps,
    ) { record, isLoaded, done, skipped, failed ->
        ReportUiState(
            run = record,
            loaded = isLoaded,
            doneCount = done,
            skippedCount = skipped,
            failedOps = failed,
        )
    }.combine(totalItems) { state, value -> state.copy(totalItems = value) }
        .combine(destinationName) { state, value -> state.copy(destinationName = value) }
        .combine(destinationUri) { state, value -> state.copy(destinationUri = value) }
        .combine(undoReport) { state, value -> state.copy(undoReport = value) }
        .combine(isUndoing) { state, value -> state.copy(isUndoing = value) }
        .combine(navigateToRunId) { state, value -> state.copy(navigateToRunId = value) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReportUiState(),
        )

    fun load(runId: String?) {
        if (runId == null) {
            loaded.value = true
            return
        }
        if (run.value?.id == runId && loaded.value) return
        viewModelScope.launch {
            val record = journalStore.run(runId)
            run.value = record
            val destination = libraryRepository.destinationOnce()
            destinationName.value = destination?.name
            destinationUri.value = destination?.uriString
            refreshOperations(runId, record)
            loaded.value = true
        }
    }

    private suspend fun refreshOperations(runId: String, record: RunRecord?) {
        val ops = journalStore.operationsOf(runId)
        doneCount.value = ops
            .filter { !it.isCompanion }
            .count { it.state == OperationState.DONE }
        skippedCount.value = ops
            .filter { !it.isCompanion }
            .count { it.state == OperationState.SKIPPED }
        failedOps.value = if (record?.finishedAt != null) {
            ops.filter {
                it.state != OperationState.DONE && it.state != OperationState.SKIPPED
            }
        } else {
            ops.filter { it.state == OperationState.FAILED }
        }
        totalItems.value = record?.totalItems
            ?: ops.map { it.itemId }.distinct().size
    }

    fun retryFailed() {
        val runId = run.value?.id ?: return
        viewModelScope.launch {
            val newRunId = session.retryFailed(runId)
            if (newRunId != null) navigateToRunId.value = newRunId
        }
    }

    fun undo() {
        val record = run.value ?: return
        if (isUndoing.value) return
        viewModelScope.launch {
            val destination = libraryRepository.destinationOnce() ?: return@launch
            isUndoing.value = true
            try {
                val executor = UndoExecutor(
                    gateway = storageGateway,
                    journal = journalStore,
                    destinationRoot = dirRefOf(destination.uriString, destination.name),
                )
                undoReport.value = executor.undo(record.id)
                val refreshed = journalStore.run(record.id)
                run.value = refreshed
                if (refreshed != null) refreshOperations(record.id, refreshed)
            } finally {
                isUndoing.value = false
            }
        }
    }

    fun consumeUndoReport() {
        undoReport.value = null
    }

    fun consumeNavigation() {
        navigateToRunId.value = null
    }

    /**
     * Redacted share text (spec 14): run summary + settings summary. Only the
     * destination folder NAME is included — never full paths or API keys.
     */
    suspend fun buildShareText(header: String): String {
        val state = uiState.value
        val record = state.run
        val settings = settingsRepository.current()
        val builder = StringBuilder()
        builder.appendLine(header)
        builder.appendLine("run: " + (record?.id ?: "-"))
        builder.appendLine("total: " + state.totalItems)
        builder.appendLine("done: " + state.doneCount)
        builder.appendLine("failed: " + state.failedCount)
        builder.appendLine("skipped: " + state.skippedCount)
        builder.appendLine("destination: " + (state.destinationName ?: "-"))
        builder.appendLine("movieAddGenres: " + settings.movieAddGenres)
        builder.appendLine("movieAddRating: " + settings.movieAddRating)
        builder.appendLine("seriesAddGenres: " + settings.seriesAddGenres)
        builder.appendLine("seriesAddRating: " + settings.seriesAddRating)
        builder.appendLine("maxGenres: " + settings.maxGenres)
        builder.appendLine("skipPreview: " + settings.skipPreview)
        builder.appendLine("metadataUnavailable: " + settings.metadataUnavailable.name)
        state.failedOps.forEach { op ->
            builder.appendLine(
                "failed: " + op.fileName + " (" +
                    (op.failureReason ?: op.state.name) + ")",
            )
        }
        return builder.toString()
    }
}
