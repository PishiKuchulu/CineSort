package com.cinesort.app.feature.flow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.orchestration.OrganizeSession
import com.cinesort.app.orchestration.SessionPhase
import com.cinesort.app.orchestration.SessionState
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScanUiState(
    val session: SessionState = SessionState(),
    val settings: AppSettings = AppSettings(),
    val navigateToPreview: Boolean = false,
    val navigateToRun: Boolean = false,
    val autoRunStarted: Boolean = false,
) {
    val isBusy: Boolean
        get() = session.phase == SessionPhase.SCANNING ||
            session.phase == SessionPhase.ENRICHING
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val session: OrganizeSession,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val navigateToPreview = MutableStateFlow(false)
    private val navigateToRun = MutableStateFlow(false)
    private val autoRunStarted = MutableStateFlow(false)

    /** True only for a scan launched from this screen visit, so returning to
     *  the Scan screen with an existing plan never auto-navigates in a loop. */
    private var launchedHere = false
    private var handledPlan = false

    val uiState: StateFlow<ScanUiState> = combine(
        session.state,
        settingsRepository.settings,
        navigateToPreview,
        navigateToRun,
    ) { sessionState, settings, toPreview, toRun ->
        ScanUiState(
            session = sessionState,
            settings = settings,
            navigateToPreview = toPreview,
            navigateToRun = toRun,
        )
    }.combine(autoRunStarted) { state, started ->
        state.copy(autoRunStarted = started)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScanUiState(),
    )

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                if (state.phase == SessionPhase.PLANNED && launchedHere && !handledPlan) {
                    handledPlan = true
                    if (settingsRepository.current().skipPreview) {
                        autoRunStarted.value = true
                        val runId = session.confirmAndRun()
                        if (runId != null) {
                            navigateToRun.value = true
                        } else {
                            navigateToPreview.value = true
                        }
                    } else {
                        navigateToPreview.value = true
                    }
                }
            }
        }
    }

    /** Starts a scan when the screen opens idle; a running session is left alone. */
    fun startIfNeeded() {
        val phase = session.state.value.phase
        if (phase == SessionPhase.IDLE && session.state.value.plan.isEmpty()) {
            launchedHere = true
            handledPlan = false
            session.startScan()
        }
    }

    fun startScan() {
        launchedHere = true
        handledPlan = false
        navigateToPreview.value = false
        navigateToRun.value = false
        autoRunStarted.value = false
        session.startScan()
    }

    fun cancelScan() {
        session.cancelScan()
    }

    fun consumeNavigation() {
        navigateToPreview.value = false
        navigateToRun.value = false
    }
}
