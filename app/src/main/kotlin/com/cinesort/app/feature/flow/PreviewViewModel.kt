package com.cinesort.app.feature.flow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinesort.app.orchestration.ItemEdit
import com.cinesort.app.orchestration.OrganizeSession
import com.cinesort.core.data.metadata.MetadataCandidate
import com.cinesort.core.data.repository.SecretStore
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.settings.AppSettings
import com.cinesort.core.domain.planner.ConflictChoice
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.MetadataInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PreviewFilter { ALL, MOVIES, SERIES, NEEDS_REVIEW, CONFLICTS, SKIPPED, ALREADY }

data class PreviewUiState(
    val session: com.cinesort.app.orchestration.SessionState =
        com.cinesort.app.orchestration.SessionState(),
    val settings: AppSettings = AppSettings(),
    val filter: PreviewFilter = PreviewFilter.ALL,
    val query: String = "",
    val collapsedGroups: Set<String> = emptySet(),
    val editingItemId: String? = null,
    val editApplyFailed: Boolean = false,
    val candidates: List<MetadataCandidate> = emptyList(),
    val candidatesSearched: Boolean = false,
    val isSearchingCandidates: Boolean = false,
    val hasApiKey: Boolean = false,
    val showConfirm: Boolean = false,
    val navigateToRun: Boolean = false,
)

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val session: OrganizeSession,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
) : ViewModel() {

    private val filter = MutableStateFlow(PreviewFilter.ALL)
    private val query = MutableStateFlow("")
    private val collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    private val editingItemId = MutableStateFlow<String?>(null)
    private val editApplyFailed = MutableStateFlow(false)
    private val candidates = MutableStateFlow<List<MetadataCandidate>>(emptyList())
    private val candidatesSearched = MutableStateFlow(false)
    private val searching = MutableStateFlow(false)
    private val showConfirm = MutableStateFlow(false)
    private val navigateToRun = MutableStateFlow(false)

    val uiState: StateFlow<PreviewUiState> = combine(
        session.state,
        settingsRepository.settings,
        secretStore.tmdbKey,
        secretStore.omdbKey,
        filter,
    ) { sessionState, settings, tmdbKey, omdbKey, currentFilter ->
        PreviewUiState(
            session = sessionState,
            settings = settings,
            hasApiKey = tmdbKey.isNotBlank() || omdbKey.isNotBlank(),
            filter = currentFilter,
        )
    }.combine(query) { state, value -> state.copy(query = value) }
        .combine(collapsedGroups) { state, value -> state.copy(collapsedGroups = value) }
        .combine(editingItemId) { state, value -> state.copy(editingItemId = value) }
        .combine(editApplyFailed) { state, value -> state.copy(editApplyFailed = value) }
        .combine(candidates) { state, value -> state.copy(candidates = value) }
        .combine(candidatesSearched) { state, value ->
            state.copy(candidatesSearched = value)
        }
        .combine(searching) { state, value -> state.copy(isSearchingCandidates = value) }
        .combine(showConfirm) { state, value -> state.copy(showConfirm = value) }
        .combine(navigateToRun) { state, value -> state.copy(navigateToRun = value) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PreviewUiState(),
        )

    fun setFilter(value: PreviewFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggleGroup(targetFolderPath: String) {
        collapsedGroups.value = if (targetFolderPath in collapsedGroups.value) {
            collapsedGroups.value - targetFolderPath
        } else {
            collapsedGroups.value + targetFolderPath
        }
    }

    fun openEdit(itemId: String) {
        candidates.value = emptyList()
        candidatesSearched.value = false
        editingItemId.value = itemId
    }

    fun dismissEdit() {
        editingItemId.value = null
    }

    fun applyEdit(
        itemId: String,
        edit: ItemEdit,
        applyToSeries: Boolean,
        remember: Boolean,
    ) {
        // The session reports whether the edit landed (audit B.5); a
        // silently dropped edit now surfaces as a message on the screen.
        val applied = session.applyEdit(itemId, edit, applyToSeries, remember)
        editingItemId.value = null
        if (!applied) editApplyFailed.value = true
    }

    fun consumeEditApplyFailed() {
        editApplyFailed.value = false
    }

    /** Preview entered: pick up any settings changed elsewhere (audit B.5). */
    fun onEnter() {
        session.syncSettingsAndReplanIfChanged()
    }

    fun setConflictChoice(itemId: String?, choice: ConflictChoice) {
        session.setConflictChoice(itemId, choice)
    }

    fun setSelected(itemId: String, selected: Boolean) {
        session.setSelected(itemId, selected)
    }

    fun setGroupSelected(targetFolderPath: String, selected: Boolean) {
        session.setGroupSelected(targetFolderPath, selected)
    }

    fun selectAllReady() = session.selectAllReady()

    fun deselectNeedsReview() = session.deselectNeedsReview()

    fun clearSelection() = session.clearSelection()

    // ------------------------------------------------------------ naming row

    fun onGenresChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(movieAddGenres = enabled, seriesAddGenres = enabled)
            }
            session.refreshSettingsAndReplan()
        }
    }

    fun onRatingChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(movieAddRating = enabled, seriesAddRating = enabled)
            }
            session.refreshSettingsAndReplan()
        }
    }

    fun onMaxGenresChanged(value: Int) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(maxGenres = value.coerceIn(1, 6)) }
            session.refreshSettingsAndReplan()
        }
    }

    // ------------------------------------------------------------ metadata search

    fun searchCandidates(type: ItemType, title: String, year: Int?) {
        viewModelScope.launch {
            searching.value = true
            try {
                candidates.value = session.searchCandidates(type, title, year)
                candidatesSearched.value = true
            } finally {
                searching.value = false
            }
        }
    }

    fun chooseCandidate(itemId: String, candidate: MetadataCandidate) {
        session.storeMetadataFor(
            itemId,
            MetadataInfo(
                genres = candidate.genres,
                rating = candidate.rating,
                ratingSource = candidate.ratingSource,
                year = candidate.year,
                posterUrl = candidate.posterUrl,
                matchedTitle = candidate.title,
            ),
        )
    }

    // ------------------------------------------------------------ organize

    fun requestOrganize() {
        showConfirm.value = true
    }

    fun dismissConfirm() {
        showConfirm.value = false
    }

    fun confirmOrganize() {
        viewModelScope.launch {
            showConfirm.value = false
            val runId = session.confirmAndRun()
            if (runId != null) navigateToRun.value = true
        }
    }

    fun consumeNavigation() {
        navigateToRun.value = false
    }
}
