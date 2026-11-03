package com.cinesort.app.feature.flow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.cinesort.app.orchestration.OrganizeSession
import com.cinesort.core.data.work.OrganizeWorker
import com.cinesort.core.domain.journal.JournalStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RunUiState(
    val runId: String? = null,
    val percent: Int = 0,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
    val currentFile: String? = null,
    /** Worker phase marker ("posters") once the moves are done (audit A.3-10). */
    val phase: String? = null,
    val workState: WorkInfo.State? = null,
    val journalDone: Int = 0,
    val showCancelConfirm: Boolean = false,
    val navigateToReport: Boolean = false,
    val persianDigits: Boolean = false,
) {
    val isFinished: Boolean
        get() = workState == WorkInfo.State.SUCCEEDED ||
            workState == WorkInfo.State.FAILED ||
            workState == WorkInfo.State.CANCELLED
}

/**
 * Run screen state: mirrors the worker's published progress (percent, done /
 * total counts, current file — the [OrganizeWorker] progress keys) and polls
 * the journal while running for an independent done count.
 */
@HiltViewModel
class RunViewModel @Inject constructor(
    private val session: OrganizeSession,
    private val journalStore: JournalStore,
    private val settingsRepository: com.cinesort.core.data.repository.SettingsRepository,
) : ViewModel() {

    private val runId = MutableStateFlow<String?>(null)
    private val percent = MutableStateFlow(0)
    private val doneItems = MutableStateFlow(0)
    private val totalItems = MutableStateFlow(0)
    private val currentFile = MutableStateFlow<String?>(null)
    private val phase = MutableStateFlow<String?>(null)
    private val workState = MutableStateFlow<WorkInfo.State?>(null)
    private val journalDone = MutableStateFlow(0)
    private val showCancelConfirm = MutableStateFlow(false)
    private val navigateToReport = MutableStateFlow(false)

    private var observeJob: Job? = null
    private var pollJob: Job? = null
    private var reportNavigated = false

    val uiState: StateFlow<RunUiState> = combine(
        runId, percent, doneItems, totalItems, currentFile,
    ) { id, pct, done, total, current ->
        RunUiState(
            runId = id,
            percent = pct,
            doneItems = done,
            totalItems = total,
            currentFile = current,
        )
    }.combine(workState) { state, value -> state.copy(workState = value) }
        .combine(phase) { state, value -> state.copy(phase = value) }
        .combine(journalDone) { state, value -> state.copy(journalDone = value) }
        .combine(showCancelConfirm) { state, value ->
            state.copy(showCancelConfirm = value)
        }
        .combine(navigateToReport) { state, value ->
            state.copy(navigateToReport = value)
        }
        .combine(settingsRepository.settings) { state, settings ->
            // The Run screen was the one screen ignoring the user's digit
            // preference (hard-coded Latin digits — granular audit 1-11).
            state.copy(persianDigits = settings.persianDigits)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RunUiState(),
        )

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                val id = state.lastRunId
                if (id != null && id != runId.value) {
                    observeRun(id)
                }
            }
        }
    }

    private fun observeRun(id: String) {
        runId.value = id
        reportNavigated = false
        observeJob?.cancel()
        pollJob?.cancel()
        observeJob = viewModelScope.launch {
            session.workInfos(id).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                workState.value = info.state
                val progress = info.progress
                percent.value = progress.getInt(OrganizeWorker.KEY_PROGRESS, percent.value)
                doneItems.value = progress.getInt(
                    OrganizeWorker.KEY_DONE_ITEMS,
                    doneItems.value,
                )
                totalItems.value = progress.getInt(
                    OrganizeWorker.KEY_TOTAL_ITEMS,
                    totalItems.value,
                )
                currentFile.value = progress.getString(OrganizeWorker.KEY_CURRENT_FILE)
                progress.getString(OrganizeWorker.KEY_PHASE)?.let { phase.value = it }
                if (info.state == WorkInfo.State.SUCCEEDED && !reportNavigated) {
                    reportNavigated = true
                    navigateToReport.value = true
                }
            }
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                // Counting-size summary, not the whole journal
                // (section-3 audit): this poll used to materialize every
                // operation record of a growing run once a second for the
                // run's entire lifetime. The summary carries the same
                // counts via the shared definition — posters are not
                // item progress (audit B.9).
                val summary = journalStore.operationSummary(id)
                journalDone.value =
                    com.cinesort.core.domain.journal.countFromItemBestStates(
                        summary,
                        runFinished = false,
                    ).done
                if (totalItems.value == 0 && summary.bestStateByItem.isNotEmpty()) {
                    totalItems.value = summary.bestStateByItem.size
                }
                val state = workState.value
                if (state == WorkInfo.State.SUCCEEDED ||
                    state == WorkInfo.State.FAILED ||
                    state == WorkInfo.State.CANCELLED
                ) {
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun requestCancel() {
        showCancelConfirm.value = true
    }

    fun dismissCancel() {
        showCancelConfirm.value = false
    }

    fun confirmCancel() {
        showCancelConfirm.value = false
        session.cancelCurrentWork()
    }

    fun consumeNavigation() {
        navigateToReport.value = false
    }

    companion object {
        // 2.5 s (section-3 audit): the summary query is cheap, but the
        // worker's own progress flow already drives the bar — this poll
        // is only the independent journal cross-check.
        private const val POLL_INTERVAL_MS = 2_500L
    }
}
