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
    val undoneCount: Int = 0,
    val posterCount: Int = 0,
    val failedOps: List<OperationRecord> = emptyList(),
    val totalItems: Int = 0,
    val destinationName: String? = null,
    val destinationUri: String? = null,
    val undoReport: UndoReport? = null,
    val isUndoing: Boolean = false,
    val navigateToRunId: String? = null,
    val persianDigits: Boolean = false,
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
 * Report state for one run (spec 10.4 screen 7). All outcome counts come
 * from the shared journal definition
 * ([com.cinesort.core.domain.journal.countRunOperations], audit B.9):
 * per item, main operations only, posters excluded from done, UNDONE in
 * its own bucket instead of failed.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val session: OrganizeSession,
    private val journalStore: JournalStore,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val storageGateway: StorageGateway,
    private val undoCoordinator: com.cinesort.app.orchestration.UndoCoordinator,
) : ViewModel() {

    private val run = MutableStateFlow<RunRecord?>(null)
    private val loaded = MutableStateFlow(false)
    private val counts = MutableStateFlow(
        com.cinesort.core.domain.journal.RunOperationCounts(),
    )
    private val failedOps = MutableStateFlow<List<OperationRecord>>(emptyList())
    private val totalItems = MutableStateFlow(0)
    private val destinationName = MutableStateFlow<String?>(null)
    private val destinationUri = MutableStateFlow<String?>(null)
    private val undoReport = MutableStateFlow<UndoReport?>(null)
    private val isUndoing = MutableStateFlow(false)
    private val navigateToRunId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ReportUiState> = combine(
        run, loaded, counts, failedOps,
    ) { record, isLoaded, runCounts, failed ->
        ReportUiState(
            run = record,
            loaded = isLoaded,
            doneCount = runCounts.done,
            skippedCount = runCounts.skipped,
            undoneCount = runCounts.undone,
            posterCount = runCounts.posters,
            failedOps = failed,
        )
    }.combine(totalItems) { state, value -> state.copy(totalItems = value) }
        .combine(destinationName) { state, value -> state.copy(destinationName = value) }
        .combine(destinationUri) { state, value -> state.copy(destinationUri = value) }
        .combine(undoReport) { state, value -> state.copy(undoReport = value) }
        .combine(isUndoing) { state, value -> state.copy(isUndoing = value) }
        .combine(navigateToRunId) { state, value -> state.copy(navigateToRunId = value) }
        .combine(settingsRepository.settings) { state, settings ->
            state.copy(persianDigits = settings.persianDigits)
        }
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
        // No same-run early return (audit B.7): a Report left in the back
        // stack while the run was undone from History re-enters and must
        // show the current journal, not the pre-undo numbers.
        viewModelScope.launch {
            val record = journalStore.run(runId)
            run.value = record
            // The run's own destination when recorded (audit B.8); older
            // runs fall back to the library's current destination.
            if (record != null && record.destinationId.isNotBlank()) {
                destinationName.value = record.destinationName
                destinationUri.value = record.destinationId
            } else {
                val destination = libraryRepository.destinationOnce()
                destinationName.value = destination?.name
                destinationUri.value = destination?.uriString
            }
            refreshOperations(runId, record)
            loaded.value = true
        }
    }

    private suspend fun refreshOperations(runId: String, record: RunRecord?) {
        val ops = journalStore.operationsOf(runId)
        val runCounts = com.cinesort.core.domain.journal.countRunOperations(
            ops,
            runFinished = record?.finishedAt != null,
        )
        counts.value = runCounts
        failedOps.value = ops.filter { op ->
            op.itemId in runCounts.failedItemIds && !op.isPoster &&
                op.state != OperationState.DONE &&
                op.state != OperationState.UNDONE &&
                op.state != OperationState.SKIPPED
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
            // Undo resolves files under the RUN's destination (audit B.8),
            // falling back to the current one for pre-v5 runs that recorded
            // none — and runs under the process-wide undo lock (audit B.8),
            // not just this screen's own flag.
            val destinationRoot = if (record.destinationId.isNotBlank()) {
                dirRefOf(record.destinationId, record.destinationName)
            } else {
                val destination = libraryRepository.destinationOnce() ?: return@launch
                dirRefOf(destination.uriString, destination.name)
            }
            isUndoing.value = true
            try {
                undoReport.value = undoCoordinator.withUndoLock {
                    val executor = UndoExecutor(
                        gateway = storageGateway,
                        journal = journalStore,
                        destinationRoot = destinationRoot,
                    )
                    executor.undo(record.id)
                }
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
        builder.appendLine("undone: " + state.undoneCount)
        builder.appendLine("posters: " + state.posterCount)
        builder.appendLine("destination: " + (state.destinationName ?: "-"))
        // The settings summary is the run's own snapshot when it recorded
        // one (audit A.3-7): reading the CURRENT settings here made the
        // shared report describe a different configuration than the run
        // actually used whenever settings changed after the run.
        if (!record?.optionsSnapshot.isNullOrBlank()) {
            builder.appendLine("settings-at-run: " + record?.optionsSnapshot)
        } else {
            builder.appendLine("movieAddGenres: " + settings.movieAddGenres)
            builder.appendLine("movieAddRating: " + settings.movieAddRating)
            builder.appendLine("seriesAddGenres: " + settings.seriesAddGenres)
            builder.appendLine("seriesAddRating: " + settings.seriesAddRating)
            builder.appendLine("maxGenres: " + settings.maxGenres)
            builder.appendLine("skipPreview: " + settings.skipPreview)
            builder.appendLine("metadataUnavailable: " + settings.metadataUnavailable.name)
        }
        state.failedOps.forEach { op ->
            builder.appendLine(
                "failed: " + op.fileName + " (" +
                    (op.failureReason ?: op.state.name) + ")",
            )
        }
        return builder.toString()
    }
}
