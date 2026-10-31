package com.cinesort.app.orchestration

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cinesort.core.data.metadata.ApiKeys
import com.cinesort.core.data.metadata.MetadataQuery
import com.cinesort.core.data.metadata.MetadataService
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.RunDraftRepository
import com.cinesort.core.data.repository.SecretStore
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.settings.AppSettings
import com.cinesort.core.data.settings.MetadataUnavailableBehavior
import com.cinesort.core.data.settings.extraNoiseWordSet
import com.cinesort.core.data.settings.toNamingOptions
import com.cinesort.core.data.settings.toScanSettings
import com.cinesort.core.data.work.OrganizeWorker
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.EntryInfo
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.planner.ConflictChoice
import com.cinesort.core.domain.planner.DestinationSnapshot
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.MetadataInfo
import com.cinesort.core.domain.planner.OrganizePlanner
import com.cinesort.core.domain.planner.PlanInput
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.domain.planner.TitleOverride
import com.cinesort.core.domain.scan.ScanDisposition
import com.cinesort.core.domain.scan.ScanRules
import com.cinesort.core.domain.usecase.FolderSetValidator
import com.cinesort.core.parser.AnchorMediaNameParser
import com.cinesort.core.parser.Confidence
import com.cinesort.core.parser.NormalizedKey
import com.cinesort.core.parser.ParseResult
import com.cinesort.core.parser.PathContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** Pipeline phase of the shared scan → plan → run session. */
enum class SessionPhase { IDLE, SCANNING, ENRICHING, PLANNED, RUNNING }

/**
 * One user correction from the preview edit sheet. Null fields keep the parsed
 * value; [clearYear] explicitly drops the year. Movie ↔ Episode conversion is
 * allowed: [type] selects the result kind to build.
 */
data class ItemEdit(
    val type: ItemType? = null,
    val title: String? = null,
    val year: Int? = null,
    val clearYear: Boolean = false,
    val season: Int? = null,
    val episodes: List<Int>? = null,
)

/** Immutable snapshot of everything the flow screens render. */
data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    /** Stepper position: 0 scanning, 1 parsing, 2 metadata/planning, 3 planned. */
    val stageIndex: Int = 0,
    val scannedEntries: Int = 0,
    val videosFound: Int = 0,
    val items: List<ScannedFile> = emptyList(),
    val parseResults: Map<String, ParseResult> = emptyMap(),
    val metadataByKey: Map<String, MetadataInfo> = emptyMap(),
    val plan: List<PlannedItem> = emptyList(),
    val conflictChoices: Map<String, ConflictChoice> = emptyMap(),
    val itemEdits: Map<String, ItemEdit> = emptyMap(),
    val destinationSnapshot: DestinationSnapshot? = null,
    val destinationName: String? = null,
    val lastRunId: String? = null,
    val workId: UUID? = null,
    val error: String? = null,
)

/**
 * In-memory orchestration of one organize flow (spec sections 8.1, 13).
 *
 * Holds the scan results, parse results, metadata and the current plan so the
 * Scan, Preview and Run screens share one state source. The session is cheap
 * to recompute: after process death it simply starts empty and the screens
 * offer a fresh scan — no fake restore.
 */
@Singleton
class OrganizeSession @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val gateway: StorageGateway,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val metadataService: MetadataService,
    private val journalStore: JournalStore,
    private val draftRepository: RunDraftRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val planner = OrganizePlanner()

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var lastSettings: AppSettings = AppSettings()
    private var destinationRef: DirRef? = null
    private var recentlyModifiedIds: Set<String> = emptySet()
    private var sourceRootNames: Map<String, String> = emptyMap()
    private val selectionOverrides = mutableMapOf<String, Boolean>()
    private var metadataUnavailable: Boolean = false

    // ------------------------------------------------------------ scan

    /** Starts a full scan → parse → snapshot → enrich → plan cycle. No-op while busy. */
    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _state.value = SessionState(phase = SessionPhase.SCANNING, stageIndex = 0)
            selectionOverrides.clear()
            metadataUnavailable = false
            try {
                runScanPipeline()
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = SessionPhase.IDLE, error = null) }
                throw e
            } catch (e: Exception) {
                Timber.w(e, "scan pipeline failed")
                _state.update {
                    it.copy(phase = SessionPhase.IDLE, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Cancels an in-flight scan; the session returns to Idle. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update {
            if (it.phase == SessionPhase.SCANNING || it.phase == SessionPhase.ENRICHING) {
                it.copy(phase = SessionPhase.IDLE)
            } else {
                it
            }
        }
    }

    private suspend fun runScanPipeline() {
        val settings = settingsRepository.current()
        lastSettings = settings
        val destinationEntity = libraryRepository.destinationOnce()
        val sourceEntities = libraryRepository.sourcesOnce()
        if (destinationEntity == null || sourceEntities.isEmpty()) {
            _state.update { it.copy(phase = SessionPhase.IDLE, error = ERROR_NO_SETUP) }
            return
        }
        val destination = DirRef(destinationEntity.uriString, destinationEntity.name)
        destinationRef = destination

        // Folder-set validation runs on bare document ids (spec 5.8); SAF tree
        // URIs wrap them, so unwrap before comparing.
        val validation = FolderSetValidator.validate(
            destination = DirRef(documentIdOf(destination.id), destination.name),
            sources = sourceEntities.map { DirRef(documentIdOf(it.uriString), it.name) },
        )
        if (validation is FolderSetValidator.Result.Error) {
            _state.update { it.copy(phase = SessionPhase.IDLE, error = validation.message) }
            return
        }
        val sources = FolderSetValidator.dedupeSources(
            sourceEntities.map { DirRef(it.uriString, it.name) },
        )
        val entityByUri = sourceEntities.associateBy { it.uriString }

        val scanSettings = settings.toScanSettings()
        val now = System.currentTimeMillis()
        val found = mutableListOf<ScannedFile>()
        val recentlyModified = mutableSetOf<String>()
        val rootNames = mutableMapOf<String, String>()
        var entryOffset = 0

        for (source in sources) {
            val entity = entityByUri[source.id]
            val exclude = if (
                FolderSetValidator.isInside(
                    documentIdOf(destination.id),
                    documentIdOf(source.id),
                )
            ) {
                setOf(destination)
            } else {
                emptySet()
            }
            var lastProgress = 0
            gateway.scan(
                root = source,
                recursive = entity?.includeSubfolders ?: true,
                maxDepth = entity?.maxDepth ?: scanSettings.maxDepth,
                exclude = exclude,
            ).collect { event ->
                when (event) {
                    is com.cinesort.core.domain.model.ScanEvent.Progress -> {
                        lastProgress = event.scannedEntries
                        val total = entryOffset + event.scannedEntries
                        _state.update { it.copy(scannedEntries = total) }
                    }
                    is com.cinesort.core.domain.model.ScanEvent.Found -> {
                        val scanned = event.item
                        when (
                            ScanRules.classify(
                                scanned.file.name,
                                scanned.file.size,
                                scanned.file.lastModified,
                                now,
                                scanSettings,
                            )
                        ) {
                            ScanDisposition.VIDEO -> {
                                found += scanned
                                rootNames[scanned.file.id] = source.name
                            }
                            ScanDisposition.RECENTLY_MODIFIED -> {
                                found += scanned
                                recentlyModified += scanned.file.id
                                rootNames[scanned.file.id] = source.name
                            }
                            else -> Unit // filtered out by the user's scan settings
                        }
                        _state.update { it.copy(videosFound = found.size) }
                    }
                    else -> Unit // skipped / failed entries only move the counters
                }
            }
            entryOffset += lastProgress
        }

        // Parse every item with the anchor parser (spec section 6).
        _state.update { it.copy(stageIndex = 1, items = found) }
        val parser = AnchorMediaNameParser(
            currentYear = Calendar.getInstance().get(Calendar.YEAR),
            extraNoiseWords = settings.extraNoiseWordSet(),
            titleCaseMode = settings.titleCaseMode,
        )
        val parsed = found.associate { scanned ->
            scanned.file.id to parser.parse(
                scanned.file.name,
                PathContext(
                    ancestors = scanned.ancestors,
                    sourceRootName = rootNames[scanned.file.id],
                ),
            )
        }
        recentlyModifiedIds = recentlyModified
        sourceRootNames = rootNames
        _state.update { it.copy(parseResults = parsed) }

        // Destination snapshot (bounded walk) + volume info for the planner.
        val snapshot = buildDestinationSnapshot(destination)
        _state.update {
            it.copy(destinationSnapshot = snapshot, destinationName = destination.name)
        }

        enrichIfNeeded(settings)
        replanInternal()
        _state.update { it.copy(phase = SessionPhase.PLANNED, stageIndex = 3, error = null) }
    }

    /**
     * Walks the destination tree with bounded depth: library roots, their title
     * folders, season folders, and the files inside title/season folders.
     */
    private suspend fun buildDestinationSnapshot(destination: DirRef): DestinationSnapshot {
        val folders = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        val fileDetails = mutableMapOf<String, com.cinesort.core.domain.planner.ExistingFileInfo>()

        suspend fun walk(dir: DirRef, prefix: String, depth: Int) {
            val children = try {
                gateway.listChildren(dir)
            } catch (e: Exception) {
                Timber.w(e, "destination snapshot walk failed at %s", dir.name)
                return
            }
            for (child: EntryInfo in children) {
                val relative = if (prefix.isEmpty()) {
                    child.ref.name
                } else {
                    prefix + "/" + child.ref.name
                }
                if (child.isDirectory) {
                    folders += relative
                    val childDir = child.ref as? DirRef
                    if (childDir != null && depth < MAX_SNAPSHOT_DEPTH) {
                        walk(childDir, relative, depth + 1)
                    }
                } else {
                    files += relative
                    fileDetails[relative] = com.cinesort.core.domain.planner.ExistingFileInfo(
                        size = child.size,
                        lastModified = child.lastModified,
                    )
                }
            }
        }
        walk(destination, prefix = "", depth = 0)
        return DestinationSnapshot(
            folders = folders,
            files = files,
            fileDetails = fileDetails,
            volume = gateway.volumeIdOf(destination),
            freeBytes = runCatching { gateway.freeBytes(destination) }.getOrNull(),
        )
    }

    // ------------------------------------------------------------ metadata

    private fun decorationNeeded(settings: AppSettings): Boolean =
        settings.movieAddGenres || settings.movieAddRating ||
            settings.seriesAddGenres || settings.seriesAddRating

    /**
     * Fetches metadata for the unique (type, key, year) queries of the current
     * parse results when decoration is on. Records unavailability for the
     * HOLD behaviour; never throws.
     */
    private suspend fun enrichIfNeeded(settings: AppSettings, fromScan: Boolean = true) {
        if (!decorationNeeded(settings)) return
        if (fromScan) {
            _state.update { it.copy(phase = SessionPhase.ENRICHING, stageIndex = 2) }
        }
        val tmdb = secretStore.tmdbKey.first()
        val omdb = secretStore.omdbKey.first()
        val keys = ApiKeys(
            tmdbKey = tmdb.takeIf { it.isNotBlank() },
            omdbKey = omdb.takeIf { it.isNotBlank() },
        )
        if (!keys.hasTmdb && !keys.hasOmdb) {
            metadataUnavailable = true
            return
        }
        val queries = buildQueries(effectiveParseResults())
        if (queries.isEmpty()) return
        try {
            val result = metadataService.lookupAll(queries, keys)
            if (result.isEmpty()) {
                // Every lookup came back empty: offline / bad key / quota are
                // indistinguishable from "not found" here, so treat a fully
                // empty batch as unavailable for the HOLD behaviour.
                metadataUnavailable = true
            }
            _state.update { it.copy(metadataByKey = it.metadataByKey + result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "metadata enrichment failed")
            metadataUnavailable = true
        }
    }

    private fun buildQueries(results: Map<String, ParseResult>): List<MetadataQuery> {
        val seen = mutableSetOf<Triple<ItemType, String, Int?>>()
        val queries = mutableListOf<MetadataQuery>()
        for (result in results.values) {
            when (result) {
                is ParseResult.Movie -> {
                    val key = NormalizedKey.movieKey(result.title, result.year)
                    if (seen.add(Triple(ItemType.MOVIE, key, result.year))) {
                        queries += MetadataQuery(ItemType.MOVIE, result.title, result.year, key)
                    }
                }
                is ParseResult.Episode -> {
                    val key = NormalizedKey.seriesKey(result.seriesTitle)
                    if (seen.add(Triple(ItemType.EPISODE, key, result.seriesYearHint))) {
                        queries += MetadataQuery(
                            ItemType.EPISODE,
                            result.seriesTitle,
                            result.seriesYearHint,
                            key,
                        )
                    }
                }
                is ParseResult.Unrecognized -> Unit
            }
        }
        return queries
    }

    /** Stores a user-chosen metadata candidate for [itemId] and re-plans. */
    fun storeMetadataFor(itemId: String, info: MetadataInfo) {
        val result = effectiveParseResults()[itemId] ?: return
        val key = when (result) {
            is ParseResult.Movie -> NormalizedKey.movieKey(result.title, result.year)
            is ParseResult.Episode -> NormalizedKey.seriesKey(result.seriesTitle)
            is ParseResult.Unrecognized -> return
        }
        _state.update { it.copy(metadataByKey = it.metadataByKey + (key to info)) }
        requestReplan()
    }

    /** Current API keys for candidate searches from the edit sheet. */
    suspend fun currentApiKeys(): ApiKeys = ApiKeys(
        tmdbKey = secretStore.tmdbKey.first().takeIf { it.isNotBlank() },
        omdbKey = secretStore.omdbKey.first().takeIf { it.isNotBlank() },
    )

    suspend fun searchCandidates(
        type: ItemType,
        title: String,
        year: Int?,
    ): List<com.cinesort.core.data.metadata.MetadataCandidate> {
        if (title.isBlank()) return emptyList()
        return metadataService.searchCandidates(type, title, year, currentApiKeys())
    }

    // ------------------------------------------------------------ planning

    /** Parse results with the session's per-item edits applied. */
    fun effectiveParseResults(): Map<String, ParseResult> {
        val current = _state.value
        if (current.itemEdits.isEmpty()) return current.parseResults
        return current.parseResults.mapValues { (id, result) ->
            current.itemEdits[id]?.let { applyEditToResult(result, it) } ?: result
        }
    }

    /** Re-plans with the latest persisted settings, fetching metadata lazily. */
    fun refreshSettingsAndReplan() {
        scope.launch {
            val settings = settingsRepository.current()
            lastSettings = settings
            if (decorationNeeded(settings) && _state.value.metadataByKey.isEmpty()) {
                enrichIfNeeded(settings, fromScan = false)
            }
            replanInternal()
            if (_state.value.plan.isNotEmpty()) {
                _state.update { it.copy(phase = SessionPhase.PLANNED, stageIndex = 3) }
            }
        }
    }

    fun requestReplan() {
        scope.launch { replanInternal() }
    }

    private suspend fun replanInternal() {
        val current = _state.value
        if (current.items.isEmpty() || current.destinationSnapshot == null) return
        val settings = lastSettings
        val overrides = libraryRepository.titleOverrides()
        var choices = current.conflictChoices
        // Seed the persisted conflict default for unresolved conflicts once.
        if (settings.conflictDefault !=
            com.cinesort.core.data.settings.ConflictDefault.ASK
        ) {
            val seeded = planner.plan(planInput(current, settings, choices, overrides))
            val defaultChoice = when (settings.conflictDefault) {
                com.cinesort.core.data.settings.ConflictDefault.SKIP -> ConflictChoice.SKIP
                com.cinesort.core.data.settings.ConflictDefault.KEEP_BOTH ->
                    ConflictChoice.KEEP_BOTH
                else -> null
            }
            if (defaultChoice != null) {
                val additions = seeded
                    .filter { it.status == PlannedStatus.CONFLICT && it.id !in choices }
                    .associate { it.id to defaultChoice }
                if (additions.isNotEmpty()) choices = choices + additions
            }
        }
        var planned = planner.plan(planInput(current, settings, choices, overrides))
        planned = planned.map { item ->
            var out = item
            val override = selectionOverrides[item.id]
            if (override != null && item.status == PlannedStatus.READY) {
                out = out.copy(selected = override)
            }
            if (metadataUnavailable &&
                settings.metadataUnavailable == MetadataUnavailableBehavior.HOLD &&
                out.metadata == null && decorationRelevant(out.type, settings)
            ) {
                out = out.copy(
                    selected = false,
                    warnings = if (WARNING_METADATA_HELD in out.warnings) {
                        out.warnings
                    } else {
                        out.warnings + WARNING_METADATA_HELD
                    },
                )
            }
            out
        }
        _state.update { it.copy(plan = planned, conflictChoices = choices) }
    }

    private fun planInput(
        current: SessionState,
        settings: AppSettings,
        choices: Map<String, ConflictChoice>,
        overrides: List<TitleOverride>,
    ): PlanInput = PlanInput(
        items = current.items,
        parseResults = effectiveParseResults(),
        metadataByKey = current.metadataByKey,
        naming = settings.toNamingOptions(),
        overrides = overrides,
        destination = current.destinationSnapshot ?: DestinationSnapshot(),
        conflictChoices = choices,
        recentlyModifiedIds = recentlyModifiedIds,
        scanSettings = settings.toScanSettings(),
    )

    private fun decorationRelevant(type: ItemType?, settings: AppSettings): Boolean =
        when (type) {
            ItemType.MOVIE -> settings.movieAddGenres || settings.movieAddRating
            ItemType.EPISODE -> settings.seriesAddGenres || settings.seriesAddRating
            null -> false
        }

    // ------------------------------------------------------------ edits & choices

    /**
     * Applies one edit-sheet correction. [applyToSeries] widens it to every item
     * sharing the item's series/movie key; [remember] persists a title override
     * keyed by the ORIGINAL parse (spec 6.6).
     */
    fun applyEdit(
        itemId: String,
        edit: ItemEdit,
        applyToSeries: Boolean,
        remember: Boolean,
    ) {
        val current = _state.value
        val effective = effectiveParseResults()
        val targets = if (applyToSeries) {
            val groupKey = groupKeyOf(effective[itemId]) ?: return
            effective.entries.filter { groupKeyOf(it.value) == groupKey }.map { it.key }
        } else {
            listOf(itemId)
        }
        val merged = targets.associateWith { target ->
            mergeEdits(current.itemEdits[target], edit)
        }
        _state.update { it.copy(itemEdits = it.itemEdits + merged) }

        if (remember) {
            val raw = current.parseResults[itemId]
            val editedResult = effectiveParseResults()[itemId]
            val override = buildOverride(raw, edit, editedResult)
            if (override != null) {
                scope.launch { libraryRepository.saveOverride(override) }
            }
        }
        requestReplan()
    }

    private fun buildOverride(
        raw: ParseResult?,
        edit: ItemEdit,
        edited: ParseResult?,
    ): TitleOverride? {
        val correctedTitle: String
        val correctedYear: Int?
        when (edited) {
            is ParseResult.Movie -> {
                correctedTitle = edited.title
                correctedYear = edited.year
            }
            is ParseResult.Episode -> {
                correctedTitle = edited.seriesTitle
                correctedYear = edited.seriesYearHint
            }
            else -> return null
        }
        if (correctedTitle.isBlank()) return null
        return when (raw) {
            is ParseResult.Movie -> TitleOverride(
                normalizedKey = NormalizedKey.movieKey(raw.title, raw.year),
                type = ItemType.MOVIE,
                correctedTitle = correctedTitle,
                correctedYear = correctedYear,
            )
            is ParseResult.Episode -> TitleOverride(
                normalizedKey = NormalizedKey.seriesKey(raw.seriesTitle),
                type = ItemType.EPISODE,
                correctedTitle = correctedTitle,
                correctedYear = correctedYear,
            )
            is ParseResult.Unrecognized -> TitleOverride(
                normalizedKey = NormalizedKey.of(
                    raw.fileName.substringBeforeLast('.', raw.fileName),
                ),
                type = edit.type ?: ItemType.MOVIE,
                correctedTitle = correctedTitle,
                correctedYear = correctedYear,
            )
            null -> null
        }
    }

    private fun groupKeyOf(result: ParseResult?): String? = when (result) {
        is ParseResult.Movie -> "movie:" + NormalizedKey.movieKey(result.title, result.year)
        is ParseResult.Episode -> "series:" + NormalizedKey.seriesKey(result.seriesTitle)
        else -> null
    }

    private fun mergeEdits(old: ItemEdit?, edit: ItemEdit): ItemEdit {
        if (old == null) return edit
        return ItemEdit(
            type = edit.type ?: old.type,
            title = edit.title ?: old.title,
            year = edit.year ?: old.year,
            clearYear = when {
                edit.year != null -> false
                edit.clearYear -> true
                else -> old.clearYear
            },
            season = edit.season ?: old.season,
            episodes = edit.episodes ?: old.episodes,
        )
    }

    private fun applyEditToResult(result: ParseResult, edit: ItemEdit): ParseResult {
        val targetType = edit.type ?: when (result) {
            is ParseResult.Movie -> ItemType.MOVIE
            is ParseResult.Episode -> ItemType.EPISODE
            is ParseResult.Unrecognized -> null
        }
        return when (result) {
            is ParseResult.Movie -> {
                val title = edit.title?.takeIf { it.isNotBlank() } ?: result.title
                val year = if (edit.clearYear) null else edit.year ?: result.year
                if (targetType == ItemType.EPISODE) {
                    ParseResult.Episode(
                        fileName = result.fileName,
                        seriesTitle = title,
                        seriesYearHint = year,
                        season = edit.season ?: 1,
                        episodes = edit.episodes?.ifEmpty { listOf(1) } ?: listOf(1),
                        fromFolderContext = false,
                        confidence = result.confidence,
                        trace = result.trace,
                    )
                } else {
                    result.copy(title = title, year = year)
                }
            }
            is ParseResult.Episode -> {
                val title = edit.title?.takeIf { it.isNotBlank() } ?: result.seriesTitle
                val year = if (edit.clearYear) {
                    null
                } else {
                    edit.year ?: result.seriesYearHint
                }
                if (targetType == ItemType.MOVIE) {
                    ParseResult.Movie(
                        fileName = result.fileName,
                        title = title,
                        year = year,
                        confidence = result.confidence,
                        trace = result.trace,
                    )
                } else {
                    result.copy(
                        seriesTitle = title,
                        seriesYearHint = year,
                        season = edit.season ?: result.season,
                        episodes = edit.episodes?.ifEmpty { result.episodes }
                            ?: result.episodes,
                    )
                }
            }
            is ParseResult.Unrecognized -> {
                val title = edit.title?.takeIf { it.isNotBlank() } ?: return result
                val year = if (edit.clearYear) null else edit.year
                // A user-corrected name is trusted at MEDIUM so the planner can
                // mark it READY instead of leaving it in Needs review forever.
                when (targetType) {
                    ItemType.EPISODE -> ParseResult.Episode(
                        fileName = result.fileName,
                        seriesTitle = title,
                        seriesYearHint = year,
                        season = edit.season ?: 1,
                        episodes = edit.episodes?.ifEmpty { listOf(1) } ?: listOf(1),
                        fromFolderContext = false,
                        confidence = Confidence.MEDIUM,
                        trace = result.trace,
                    )
                    ItemType.MOVIE, null -> ParseResult.Movie(
                        fileName = result.fileName,
                        title = title,
                        year = year,
                        confidence = Confidence.MEDIUM,
                        trace = result.trace,
                    )
                }
            }
        }
    }

    /** Sets one conflict choice, or all current conflicts when [itemId] is null. */
    fun setConflictChoice(itemId: String?, choice: ConflictChoice) {
        val current = _state.value
        val additions = if (itemId != null) {
            mapOf(itemId to choice)
        } else {
            current.plan.filter { it.status == PlannedStatus.CONFLICT }
                .associate { it.id to choice }
        }
        if (additions.isEmpty()) return
        _state.update { it.copy(conflictChoices = it.conflictChoices + additions) }
        requestReplan()
    }

    fun setSelected(itemId: String, selected: Boolean) {
        selectionOverrides[itemId] = selected
        _state.update { state ->
            state.copy(
                plan = state.plan.map { item ->
                    if (item.id == itemId && item.status == PlannedStatus.READY) {
                        item.copy(selected = selected)
                    } else {
                        item
                    }
                },
            )
        }
    }

    fun setGroupSelected(targetFolderPath: String, selected: Boolean) {
        _state.value.plan
            .filter { it.targetFolderPath == targetFolderPath }
            .forEach { setSelected(it.id, selected) }
    }

    fun selectAllReady() {
        _state.value.plan.filter { it.status == PlannedStatus.READY }
            .forEach { selectionOverrides[it.id] = true }
        _state.update { state ->
            state.copy(
                plan = state.plan.map { item ->
                    if (item.status == PlannedStatus.READY) {
                        item.copy(selected = true)
                    } else {
                        item
                    }
                },
            )
        }
    }

    fun deselectNeedsReview() {
        _state.update { state ->
            state.copy(
                plan = state.plan.map { item ->
                    if (item.status == PlannedStatus.NEEDS_REVIEW) {
                        selectionOverrides[item.id] = false
                        item.copy(selected = false)
                    } else {
                        item
                    }
                },
            )
        }
    }

    fun clearSelection() {
        _state.value.plan.forEach { selectionOverrides[it.id] = false }
        _state.update { state ->
            state.copy(plan = state.plan.map { it.copy(selected = false) })
        }
    }

    // ------------------------------------------------------------ execution

    /**
     * Writes drafts for the selected READY items and enqueues the organize
     * worker. Returns the new run id, or null when nothing is runnable.
     */
    suspend fun confirmAndRun(): String? {
        val selected = _state.value.plan.filter {
            it.selected && it.status == PlannedStatus.READY
        }
        if (selected.isEmpty()) return null
        val runId = UUID.randomUUID().toString()
        draftRepository.writePlannedItems(runId, selected)
        val request = OneTimeWorkRequestBuilder<OrganizeWorker>()
            .setInputData(
                Data.Builder().putString(OrganizeWorker.KEY_RUN_ID, runId).build(),
            )
            .addTag(runId)
            .build()
        WorkManager.getInstance(appContext).enqueue(request)
        _state.update {
            it.copy(phase = SessionPhase.RUNNING, lastRunId = runId, workId = request.id)
        }
        return runId
    }

    /** Live work infos for the current (or given) run, for the Run screen. */
    fun workInfos(runId: String): Flow<List<WorkInfo>> =
        WorkManager.getInstance(appContext).getWorkInfosByTagFlow(runId)

    fun cancelCurrentWork() {
        val current = _state.value
        val workManager = WorkManager.getInstance(appContext)
        if (current.workId != null) {
            workManager.cancelWorkById(current.workId)
        } else if (current.lastRunId != null) {
            workManager.cancelAllWorkByTag(current.lastRunId)
        }
    }

    /**
     * Retries the failed items of [runId] as a new run. Failed items are the
     * journal items that have any operation not DONE/SKIPPED (the executor
     * leaves a failed operation in its last in-progress state, and FAILED when
     * a state was recorded). Prefers the in-memory plan for full fidelity and
     * falls back to drafts rebuilt from the journal after process death.
     */
    suspend fun retryFailed(runId: String): String? {
        val operations = journalStore.operationsOf(runId)
        val failedItemIds = operations
            .groupBy { it.itemId }
            .filterValues { itemOps ->
                itemOps.any {
                    it.state != OperationState.DONE && it.state != OperationState.SKIPPED
                }
            }
            .keys
        if (failedItemIds.isEmpty()) return null
        val newRunId = UUID.randomUUID().toString()
        val fromPlan = _state.value.plan.filter { it.id in failedItemIds }
        if (fromPlan.size == failedItemIds.size && fromPlan.isNotEmpty()) {
            draftRepository.writePlannedItems(
                newRunId,
                fromPlan.map { it.copy(selected = true, status = PlannedStatus.READY) },
            )
        } else {
            val failedOps = operations.filter { it.itemId in failedItemIds }
            draftRepository.writeRetryDrafts(newRunId, failedOps)
        }
        val request = OneTimeWorkRequestBuilder<OrganizeWorker>()
            .setInputData(
                Data.Builder().putString(OrganizeWorker.KEY_RUN_ID, newRunId).build(),
            )
            .addTag(newRunId)
            .build()
        WorkManager.getInstance(appContext).enqueue(request)
        _state.update {
            it.copy(phase = SessionPhase.RUNNING, lastRunId = newRunId, workId = request.id)
        }
        return newRunId
    }

    /** Bare document id (`primary:Movies/Library`) for a stored tree URI string. */
    private fun documentIdOf(uriString: String): String =
        try {
            val uri = Uri.parse(uriString)
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        } catch (e: Exception) {
            uriString
        }

    companion object {
        const val ERROR_NO_SETUP = "no-setup"
        const val WARNING_METADATA_HELD = "metadata unavailable — held"
        private const val MAX_SNAPSHOT_DEPTH = 3
    }
}
