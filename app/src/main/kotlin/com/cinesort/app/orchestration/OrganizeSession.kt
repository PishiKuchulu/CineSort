package com.cinesort.app.orchestration

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
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
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.retryableItemIds
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    /** Entries the gateway skipped during the scan (audit A.3-6). */
    val scanSkippedCount: Int = 0,
    /** Entries the gateway failed to read during the scan (audit A.3-6). */
    val scanFailedCount: Int = 0,
    /** A few "name — reason" samples behind the two counters above. */
    val scanIssueSamples: List<String> = emptyList(),
    /** Last enrichment outcome: decoration was requested but unavailable (audit A.3-11). */
    val metadataUnavailable: Boolean = false,
    /** Bytes the selected plan exceeds the destination's free space by (audit A.3-5). */
    val freeSpaceShortfallBytes: Long? = null,
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
    private val replanMutex = Mutex()
    private val planner = OrganizePlanner()

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var scanJob: Job? = null

    // Serializes run starts (audit B.6): confirm and retry must never build
    // two workers over overlapping files.
    private val runStartMutex = Mutex()

    // Written on the session's IO scope, read from replan paths and the UI
    // (review M-H residual): @Volatile for cross-thread visibility.
    @Volatile
    private var lastSettings: AppSettings = AppSettings()

    /** The settings the current plan was built with (audit B.5 staleness check). */
    @Volatile
    private var plannedWithSettings: AppSettings? = null
    private var recentlyModifiedIds: Set<String> = emptySet()
    private var sourceRootNames: Map<String, String> = emptyMap()

    // Mutated from main-thread UI callbacks (non-suspend, so the replan mutex
    // cannot guard them) and read under the replan mutex: a concurrent map is
    // the correct primitive here (review M-H residual).
    private val selectionOverrides = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var metadataUnavailable: Boolean = false

    /** Signature of the last completed enrichment (review M-D). */
    @Volatile
    private var lastEnrichmentSignature: EnrichmentSignature? = null

    /** Everything metadata enrichment depends on besides the items themselves. */
    private data class EnrichmentSignature(
        val hasTmdb: Boolean,
        val hasOmdb: Boolean,
        val movieAddGenres: Boolean,
        val movieAddRating: Boolean,
        val seriesAddGenres: Boolean,
        val seriesAddRating: Boolean,
        val ratingSource: com.cinesort.core.data.settings.RatingSourcePreference,
        val genreNormalization: Boolean,
    )

    // ------------------------------------------------------------ scan

    /** Starts a full scan → parse → snapshot → enrich → plan cycle. No-op while busy. */
    fun startScan() {
        if (scanJob?.isActive == true) return
        replanJob?.cancel()
        scanJob = scope.launch {
            // A rescan must not orphan a live run's tracking (audit B.4):
            // the worker keeps going from its drafts, and the Run screen
            // finds it through lastRunId/workId — keep both across the reset.
            val previous = _state.value
            _state.value = SessionState(
                phase = SessionPhase.SCANNING,
                stageIndex = 0,
                lastRunId = previous.lastRunId,
                workId = previous.workId,
            )
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
        // Scan-phase losses are counted and sampled, not discarded
        // (audit A.3-6): a file the provider failed to read mid-tree used
        // to vanish silently, and the user read the scan as complete.
        var scanSkipped = 0
        var scanFailed = 0
        val scanIssues = mutableListOf<String>()

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
                // Both depth controls are real (audit A.2-1): the
                // per-source depth, capped by the global Settings depth.
                maxDepth = ScanRules.effectiveDepth(entity?.maxDepth, scanSettings.maxDepth),
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
                    is com.cinesort.core.domain.model.ScanEvent.Skipped -> {
                        scanSkipped++
                        if (scanIssues.size < MAX_SCAN_ISSUE_SAMPLES) {
                            scanIssues += event.name + " — " + event.reason
                        }
                    }
                    is com.cinesort.core.domain.model.ScanEvent.Failed -> {
                        scanFailed++
                        if (scanIssues.size < MAX_SCAN_ISSUE_SAMPLES) {
                            scanIssues += event.name + " — " + event.reason
                        }
                    }
                }
            }
            entryOffset += lastProgress
            _state.update {
                it.copy(
                    scanSkippedCount = scanSkipped,
                    scanFailedCount = scanFailed,
                    scanIssueSamples = scanIssues.toList(),
                )
            }
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
     *
     * Folder listings run in parallel with bounded concurrency (review M10): each
     * SAF listChildren is a Binder round trip, so a sequential walk over a large
     * library (thousands of title folders) took minutes.
     */
    private suspend fun buildDestinationSnapshot(destination: DirRef): DestinationSnapshot {
        val folders = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val files = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val fileDetails = java.util.Collections.synchronizedMap(
            mutableMapOf<String, com.cinesort.core.domain.planner.ExistingFileInfo>(),
        )
        val gate = Semaphore(SNAPSHOT_CONCURRENCY)

        suspend fun walk(dir: DirRef, prefix: String, depth: Int) {
            val children = gate.withPermit {
                try {
                    gateway.listChildren(dir)
                } catch (e: Exception) {
                    Timber.w(e, "destination snapshot walk failed at %s", dir.name)
                    return
                }
            }
            val subdirs = mutableListOf<Pair<DirRef, String>>()
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
                        subdirs += childDir to relative
                    }
                } else {
                    files += relative
                    fileDetails[relative] = com.cinesort.core.domain.planner.ExistingFileInfo(
                        size = child.size,
                        lastModified = child.lastModified,
                    )
                }
            }
            if (subdirs.isNotEmpty()) {
                coroutineScope {
                    subdirs.map { (childDir, relative) ->
                        async { walk(childDir, relative, depth + 1) }
                    }.awaitAll()
                }
            }
        }
        walk(destination, prefix = "", depth = 0)
        return DestinationSnapshot(
            folders = folders.toSet(),
            files = files.toSet(),
            fileDetails = fileDetails.toMap(),
            volume = gateway.volumeIdOf(destination),
            freeBytes = runCatching { gateway.freeBytes(destination) }.getOrNull(),
        )
    }

    // ------------------------------------------------------------ metadata

    private fun decorationNeeded(settings: AppSettings): Boolean =
        settings.movieAddGenres || settings.movieAddRating ||
            settings.seriesAddGenres || settings.seriesAddRating

    /**
     * Sets the unavailability flag and mirrors it into the state: Preview
     * renders a banner from the state copy in CONTINUE mode (audit A.3-11),
     * where items otherwise silently lose their genres/ratings.
     */
    private fun setMetadataUnavailable(value: Boolean) {
        metadataUnavailable = value
        _state.update { it.copy(metadataUnavailable = value) }
    }

    /**
     * Fetches metadata for the unique (type, key, year) queries of the current
     * parse results when decoration is on. Records unavailability for the
     * HOLD behaviour; never throws.
     */
    private suspend fun enrichIfNeeded(settings: AppSettings, fromScan: Boolean = true) {
        if (!decorationNeeded(settings)) return
        val tmdb = secretStore.tmdbKey.first()
        val omdb = secretStore.omdbKey.first()
        val keys = ApiKeys(
            tmdbKey = tmdb.takeIf { it.isNotBlank() },
            omdbKey = omdb.takeIf { it.isNotBlank() },
        )
        val signature = EnrichmentSignature(
            hasTmdb = keys.hasTmdb,
            hasOmdb = keys.hasOmdb,
            movieAddGenres = settings.movieAddGenres,
            movieAddRating = settings.movieAddRating,
            seriesAddGenres = settings.seriesAddGenres,
            seriesAddRating = settings.seriesAddRating,
            ratingSource = settings.ratingSource,
            genreNormalization = settings.genreNormalization,
        )
        // Settings-refresh path (review M-D): re-enrich only when something
        // metadata-relevant changed since the last completed enrichment. The
        // old gate (metadataByKey.isEmpty()) never fetched series decoration
        // that was enabled after a movie-only fetch. The scan path always
        // enriches (new items) and records the signature.
        if (!fromScan && signature == lastEnrichmentSignature) return
        if (fromScan) {
            _state.update { it.copy(phase = SessionPhase.ENRICHING, stageIndex = 2) }
        }
        if (!keys.hasTmdb && !keys.hasOmdb) {
            setMetadataUnavailable(true)
            return
        }
        // Queries are built from the override-applied titles (review M-D):
        // persisted corrections feed the queries exactly like in-session edits
        // do, so a corrected item is decorated under its corrected title. The
        // planner's own application of the overrides is unchanged.
        val overrides = libraryRepository.titleOverrides()
        val effective = planner.applyPersistedOverrides(effectiveParseResults(), overrides)
        val queries = buildQueries(effective)
        if (queries.isEmpty()) return
        try {
            val result = metadataService.lookupAll(queries, keys)
            val unavailable = result.isEmpty() || metadataService.circuitOpen
            if (unavailable) {
                // Every lookup came back empty: offline / bad key / quota are
                // indistinguishable from "not found" here, so treat a fully
                // empty batch as unavailable for the HOLD behaviour. An open
                // circuit breaker (review M-D) means the same even when a
                // few cached hits came back, and the signature is only
                // recorded for a batch that actually delivered.
                setMetadataUnavailable(true)
            } else {
                setMetadataUnavailable(false)
                lastEnrichmentSignature = signature
            }
            _state.update { it.copy(metadataByKey = it.metadataByKey + result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "metadata enrichment failed")
            setMetadataUnavailable(true)
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

    /**
     * Re-plans with the latest persisted settings, fetching metadata lazily.
     *
     * Audit B.5: this used to be a second, unguarded replan gate — its own
     * launch with no cancellation and no mutex — so it could publish an
     * older plan over a newer edit-driven one (the exact race review M9
     * closed for [requestReplan]). It now goes through the same serialized
     * gate: cancel the pending replan, then read settings → enrich → plan
     * under [replanMutex].
     */
    fun refreshSettingsAndReplan() {
        replanJob?.cancel()
        replanJob = scope.launch {
            val settings = settingsRepository.current()
            lastSettings = settings
            replanMutex.withLock {
                if (decorationNeeded(settings)) {
                    // The signature gate inside decides whether a fetch is due.
                    enrichIfNeeded(settings, fromScan = false)
                }
                replanInternal()
            }
            if (_state.value.plan.isNotEmpty()) {
                _state.update { it.copy(phase = SessionPhase.PLANNED, stageIndex = 3) }
            }
        }
    }

    /**
     * Replans if the persisted settings changed since the current plan was
     * built (audit B.5): editing Settings and returning to Preview used to
     * leave the plan on the old settings until some unrelated Preview
     * action happened to replan. Called when Preview is entered. The
     * internal OMDb quota timestamp is ignored — it is not a user setting
     * and changes on its own during enrichment.
     */
    fun syncSettingsAndReplanIfChanged() {
        scope.launch {
            val planned = plannedWithSettings ?: return@launch
            val current = settingsRepository.current()
            if (current.copy(omdbExhaustedUntil = planned.omdbExhaustedUntil) != planned) {
                refreshSettingsAndReplan()
            }
        }
    }

    private var replanJob: Job? = null

    /**
     * Replans are serialized and latest-wins (review M9): the session scope is
     * multi-threaded, so unguarded launches let a slower older replan overwrite a
     * newer plan. Cancelling the previous job plus the mutex keeps publication
     * order aligned with request order.
     */
    fun requestReplan() {
        replanJob?.cancel()
        replanJob = scope.launch {
            replanMutex.withLock { replanInternal() }
        }
    }

    private suspend fun replanInternal() {
        val current = _state.value
        if (current.items.isEmpty() || current.destinationSnapshot == null) return
        val settings = lastSettings
        plannedWithSettings = settings
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
        _state.update {
            it.copy(
                plan = planned,
                conflictChoices = choices,
                // Preview warns before the run starts when the destination
                // cannot hold the selection (audit A.3-5).
                freeSpaceShortfallBytes = planner.freeSpaceShortfall(
                    planned,
                    current.destinationSnapshot,
                ),
            )
        }
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
     *
     * Returns false when the item is not in the current scan at all, so the
     * caller can tell the user instead of closing the sheet in silence
     * (audit B.5). An Unrecognized item has no group key: apply-to-series
     * degrades to just that item — previously the whole edit was dropped
     * for exactly the items that need correction most.
     */
    fun applyEdit(
        itemId: String,
        edit: ItemEdit,
        applyToSeries: Boolean,
        remember: Boolean,
    ): Boolean {
        val current = _state.value
        if (itemId !in current.parseResults) return false
        val effective = effectiveParseResults()
        val targets = if (applyToSeries) {
            val groupKey = groupKeyOf(effective[itemId])
            if (groupKey == null) {
                listOf(itemId)
            } else {
                effective.entries.filter { groupKeyOf(it.value) == groupKey }.map { it.key }
            }
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
        return true
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
        // One pass over the plan (review L9): calling setSelected per item made
        // group toggles O(group x plan) in state copies.
        val memberIds = _state.value.plan
            .filter { it.targetFolderPath == targetFolderPath }
            .map { it.id }
            .toSet()
        memberIds.forEach { selectionOverrides[it] = selected }
        _state.update { state ->
            state.copy(
                plan = state.plan.map { item ->
                    if (item.id in memberIds && item.status == PlannedStatus.READY) {
                        item.copy(selected = selected)
                    } else {
                        item
                    }
                },
            )
        }
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
     *
     * Audit B.6: run starts are serialized on [runStartMutex], refused
     * while a run is active, and enqueued as unique work — confirm/retry
     * spam (or the skip-preview auto-run racing a manual tap) used to be
     * able to start two workers over the same files, and the second one
     * journaled a false FAILED for every file the first had moved.
     */
    suspend fun confirmAndRun(): String? = runStartMutex.withLock {
        if (_state.value.phase == SessionPhase.RUNNING) {
            null
        } else {
            val selected = _state.value.plan.filter {
                it.selected && it.status == PlannedStatus.READY
            }
            if (selected.isEmpty()) {
                null
            } else {
                val runId = UUID.randomUUID().toString()
                draftRepository.writePlannedItems(runId, selected)
                journalConflictSkips(runId)
                val request = OneTimeWorkRequestBuilder<OrganizeWorker>()
                    .setInputData(
                        Data.Builder().putString(OrganizeWorker.KEY_RUN_ID, runId).build(),
                    )
                    .addTag(runId)
                    .build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
                _state.update {
                    it.copy(phase = SessionPhase.RUNNING, lastRunId = runId, workId = request.id)
                }
                runId
            }
        }
    }

    /**
     * Journals this run's conflict-skips (audit A.3-1): items the user
     * resolved as Skip in Preview never become drafts, so without a journal
     * row the run leaves no trace of them and the report's skipped counter
     * has nothing to count. The rows key on the run id; the run row itself
     * is created by the worker's executor when the work starts, and
     * operations are only ever read together with their run.
     */
    private suspend fun journalConflictSkips(runId: String) {
        _state.value.plan
            .filter { it.status == PlannedStatus.SKIPPED }
            .forEach { item ->
                journalStore.insertOperation(
                    OperationRecord(
                        id = UUID.randomUUID().toString(),
                        runId = runId,
                        itemId = item.id,
                        sourceParentId = item.scanned.file.parentId,
                        sourceParentName = item.scanned.parentDir.name,
                        sourceFileId = item.scanned.file.id,
                        fileName = item.scanned.file.name,
                        finalName = item.targetFileName,
                        destinationParentId = "",
                        destinationSegments = item.targetSegments,
                        state = OperationState.SKIPPED,
                        bytesTotal = item.scanned.file.size,
                        crossVolume = item.crossVolume,
                    ),
                )
            }
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
     * journal items [retryableItemIds] selects — a FAILED or never-finished
     * operation, and never an item an Undo already restored (audit A.3-3:
     * retry-after-undo used to re-organize deliberately returned files).
     * Prefers the in-memory plan for full fidelity and falls back to drafts
     * rebuilt from the journal after process death. Serialized with
     * [confirmAndRun] on [runStartMutex] (audit B.6).
     */
    suspend fun retryFailed(runId: String): String? = runStartMutex.withLock {
        if (_state.value.phase == SessionPhase.RUNNING) {
            null
        } else {
            val operations = journalStore.operationsOf(runId)
            val failedItemIds = retryableItemIds(operations)
            if (failedItemIds.isEmpty()) {
                null
            } else {
                val newRunId = UUID.randomUUID().toString()
                val fromPlan = _state.value.plan.filter { it.id in failedItemIds }
                if (fromPlan.size == failedItemIds.size && fromPlan.isNotEmpty()) {
                    draftRepository.writePlannedItems(
                        newRunId,
                        fromPlan.map { it.copy(selected = true, status = PlannedStatus.READY) },
                    )
                } else {
                    // Poster rows belong to their item's main row; they are
                    // not retryable work and must not pose as a main op.
                    val failedOps = operations.filter {
                        it.itemId in failedItemIds && !it.isPoster
                    }
                    draftRepository.writeRetryDrafts(newRunId, failedOps)
                }
                val request = OneTimeWorkRequestBuilder<OrganizeWorker>()
                    .setInputData(
                        Data.Builder().putString(OrganizeWorker.KEY_RUN_ID, newRunId).build(),
                    )
                    .addTag(newRunId)
                    .build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
                _state.update {
                    it.copy(
                        phase = SessionPhase.RUNNING,
                        lastRunId = newRunId,
                        workId = request.id,
                    )
                }
                newRunId
            }
        }
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

        /** The one organize work chain: a second run never overlaps the first (audit B.6). */
        const val UNIQUE_WORK_NAME = "organize-run"
        private const val MAX_SNAPSHOT_DEPTH = 3
        private const val SNAPSHOT_CONCURRENCY = 8
        private const val MAX_SCAN_ISSUE_SAMPLES = 5
    }
}
