package com.cinesort.core.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cinesort.core.data.R
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.RunDraftRepository
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalMaintenance
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.MetadataInfo
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.parser.AnchorKind
import com.cinesort.core.parser.Confidence
import com.cinesort.core.parser.ParseResult
import com.cinesort.core.parser.ParseTrace
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * Foreground worker that executes one persisted run (spec section 8.3).
 *
 * The plan is rebuilt from execution drafts written before the run started,
 * so a process death mid-run loses nothing: drafts plus the journal carry
 * the full state. Stale `.cinesort-part` leftovers under the destination
 * root are removed at run start (review L-F); scans never delete files.
 */
@HiltWorker
class OrganizeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val gateway: StorageGateway,
    private val journalStore: JournalStore,
    private val draftRepository: RunDraftRepository,
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.failure()
        closeInterruptedRuns(runId)
        val drafts = draftRepository.draftsFor(runId)
        if (drafts.isEmpty()) {
            Timber.w("organize run %s has no drafts", runId)
            return Result.failure()
        }
        val destination = libraryRepository.destinationOnce() ?: return Result.failure()
        val destinationRoot = DirRef(destination.uriString, destination.name)
        cleanupStalePartials(destinationRoot)

        // Companions are decoded (and stat'ed through the gateway) before the map:
        // decodeCompanions is suspending, so it cannot run inside List.map.
        val companionsById = HashMap<String, List<FileRef>>()
        for (draft in drafts) {
            companionsById[draft.id] =
                decodeCompanions(draft.companionUris, draft.companionNames, draft.parentUri)
        }

        val items = drafts.map { draft ->
            val scannedFile = FileRef(
                id = draft.fileUri,
                name = draft.fileName,
                size = draft.fileSize,
                lastModified = draft.fileLastModified,
                parentId = draft.parentUri,
            )
            val parentDir = DirRef(draft.parentUri, draft.parentName)
            val companions = companionsById[draft.id].orEmpty()
            val type = runCatching { ItemType.valueOf(draft.type) }.getOrDefault(ItemType.MOVIE)
            val trace = ParseTrace(null, null, AnchorKind.NONE, listOf("restored-from-draft"))
            val effective: ParseResult = if (type == ItemType.MOVIE) {
                ParseResult.Movie(
                    fileName = draft.fileName,
                    title = draft.title,
                    year = draft.year,
                    confidence = Confidence.HIGH,
                    trace = trace,
                )
            } else {
                ParseResult.Episode(
                    fileName = draft.fileName,
                    seriesTitle = draft.title,
                    seriesYearHint = draft.year,
                    season = draft.season ?: 1,
                    episodes = draft.episodes.split(',')
                        .mapNotNull { it.trim().toIntOrNull() }
                        .ifEmpty { listOf(1) },
                    fromFolderContext = false,
                    confidence = Confidence.HIGH,
                    trace = trace,
                )
            }
            PlannedItem(
                id = draft.id,
                scanned = ScannedFile(
                    file = scannedFile,
                    parentDir = parentDir,
                    ancestors = if (draft.ancestors.isEmpty()) {
                        emptyList()
                    } else {
                        draft.ancestors.split('\n')
                    },
                    siblings = emptyList(),
                    sourceVolume = draft.sourceVolume,
                ),
                companions = companions,
                effective = effective,
                type = type,
                targetSegments = if (draft.targetSegments.isEmpty()) {
                    emptyList()
                } else {
                    draft.targetSegments.split('/')
                },
                targetFileName = draft.targetFileName,
                metadata = draft.posterUrl?.let { MetadataInfo(posterUrl = it) },
                status = PlannedStatus.READY,
                selected = true,
                crossVolume = draft.crossVolume,
            )
        }

        // Resume (review H8): items whose journal operations are all DONE were
        // completed by an earlier attempt of this run before a process death —
        // skip them instead of re-running and failing on the missing sources.
        val opsByItem = journalStore.operationsOf(runId).groupBy { it.itemId }
        val pendingItems = items.filter { item ->
            val ops = opsByItem[item.id].orEmpty()
            ops.isEmpty() || ops.any { it.state != OperationState.DONE }
        }

        createNotificationChannel()
        // Review L-C: foreground promotion can be refused (background-start
        // restrictions, Android 15 dataSync limits). Losing the promotion
        // must not lose the run — the worker continues in the background.
        try {
            setForeground(
                createForegroundInfo(percent = 0, currentFile = null, total = pendingItems.size),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "foreground promotion refused; continuing in background")
        }

        val settings = settingsRepository.current()
        val executor = OrganizeExecutor(gateway, journalStore, destinationRoot)
        var lastPercent = -1
        val report = try {
            executor.execute(
                runId = runId,
                items = pendingItems,
                // The run records the settings it actually ran with
                // (audit A.3-7): Report's share text reads this snapshot
                // instead of the settings as they are NOW.
                optionsSnapshot = settingsSnapshot(settings),
                destinationId = destinationRoot.id,
                destinationName = destinationRoot.name,
            ) { progress ->
                val percent = if (progress.totalItems == 0) {
                    100
                } else {
                    (progress.doneItems * 100) / progress.totalItems
                }
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgressAsync(
                        Data.Builder()
                            .putInt(KEY_PROGRESS, percent)
                            .putInt(KEY_DONE_ITEMS, progress.doneItems)
                            .putInt(KEY_TOTAL_ITEMS, progress.totalItems)
                            .putString(KEY_CURRENT_FILE, progress.currentFileName)
                            .build(),
                    )
                    setForegroundAsync(
                        createForegroundInfo(percent, progress.currentFileName, progress.totalItems),
                    )
                }
            }
        } catch (e: CancellationException) {
            // A WorkManager stop/cancellation is not a failure (review §4):
            // rethrow so the executor's own per-item rethrow is not nullified.
            throw e
        } catch (e: Exception) {
            Timber.e(e, "organize run %s failed", runId)
            return Result.failure()
        }

        if (settings.downloadPosters) {
            // The executor already reported 100% (audit A.3-10): publish
            // the poster phase by name so the Run screen and notification
            // stop claiming the run is done while posters still download.
            setProgressAsync(
                Data.Builder()
                    .putInt(KEY_PROGRESS, 100)
                    .putString(KEY_PHASE, PHASE_POSTERS)
                    .build(),
            )
            setForegroundAsync(
                createForegroundInfo(percent = 100, currentFile = null, total = pendingItems.size),
            )
            downloadPosters(runId, pendingItems, destinationRoot)
        }
        if (settings.removeEmptyFolders) {
            cleanupEmptySourceFolders(items, settings.treatJunkAsEmpty)
        }
        draftRepository.clearRun(runId)

        return Result.success(
            Data.Builder()
                .putString(KEY_RUN_ID, runId)
                .putInt(KEY_DONE_ITEMS, report.done)
                .putInt(KEY_FAILED_ITEMS, report.failed.size)
                .putInt(KEY_SKIPPED_ITEMS, report.skipped)
                .build(),
        )
    }

    /**
     * Journal hygiene (review M-B): [JournalStore.unfinishedRuns] finally gets a
     * caller. A run whose process died keeps operations in non-final states
     * forever; when a new run starts, those stale ops of every OTHER unfinished
     * run are closed as FAILED ("interrupted") so the history never shows
     * phantom in-progress rows. Audit A.3-4: the run itself is finished too —
     * [JournalMaintenance.closeInterruptedRun] also stamps `finishedAt`, which
     * used to stay null forever, leaving the run eternally "unfinished" (and
     * never Undo-eligible) in History. Final states (DONE / FAILED / SKIPPED /
     * UNDONE) are never touched, and a cleanup failure never fails the new run.
     */
    private suspend fun closeInterruptedRuns(currentRunId: String) {
        try {
            for (run in journalStore.unfinishedRuns()) {
                if (run.id == currentRunId) continue
                JournalMaintenance.closeInterruptedRun(
                    journalStore,
                    run.id,
                    System.currentTimeMillis(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "stale-run journal cleanup failed")
        }
    }

    /** Compact one-line summary of the settings a run executes with (audit A.3-7). */
    private fun settingsSnapshot(settings: com.cinesort.core.data.settings.AppSettings): String =
        listOf(
            "movieAddGenres: ${settings.movieAddGenres}",
            "movieAddRating: ${settings.movieAddRating}",
            "seriesAddGenres: ${settings.seriesAddGenres}",
            "seriesAddRating: ${settings.seriesAddRating}",
            "maxGenres: ${settings.maxGenres}",
            "skipPreview: ${settings.skipPreview}",
            "metadataUnavailable: ${settings.metadataUnavailable.name}",
            "downloadPosters: ${settings.downloadPosters}",
            "conflictDefault: ${settings.conflictDefault.name}",
        ).joinToString("; ")

    /**
     * Review L-F: scans no longer delete files, so stale `.cinesort-part`
     * leftovers from interrupted verified copies are removed here instead —
     * under the destination root only, the one tree this app writes temp
     * files into. Best effort: failures are logged and the run continues.
     */
    private suspend fun cleanupStalePartials(destinationRoot: DirRef) {
        try {
            val queue = ArrayDeque<Pair<DirRef, Int>>()
            queue.add(destinationRoot to 0)
            while (queue.isNotEmpty()) {
                val (dir, depth) = queue.removeFirst()
                for (child in gateway.listChildren(dir)) {
                    val ref = child.ref
                    if (child.isDirectory) {
                        if (depth < MAX_PARTIAL_CLEANUP_DEPTH && ref is DirRef) {
                            queue.add(ref to depth + 1)
                        }
                    } else if (ref.name.endsWith(PART_SUFFIX)) {
                        try {
                            gateway.delete(ref)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "stale partial cleanup failed for %s", ref.name)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "stale partial cleanup failed")
        }
    }

    /** One folder's poster work: which item owns it and what undo must know. */
    private data class PosterTarget(
        val segments: List<String>,
        val url: String,
        val item: PlannedItem,
        val createdDirs: List<String>,
    )

    /**
     * Optional poster download (owner feature): for every distinct target
     * folder of this run with a matched poster, save `poster.jpg` into it —
     * the movie's own folder, or the series root folder for episodes. Existing
     * posters are kept; failures are logged and never fail the run.
     *
     * Review M-A: only items whose journal operations all finished DONE get
     * a poster — a failed item's folder may not even exist, and a poster must
     * never be the only trace of a failed move. Each written poster is
     * journaled as an isPoster operation so Undo deletes it and the
     * run-created folder can then be removed as empty.
     */
    private suspend fun downloadPosters(
        runId: String,
        items: List<PlannedItem>,
        destinationRoot: DirRef,
    ) {
        val opsByItem = journalStore.operationsOf(runId).groupBy { it.itemId }
        val byFolder = LinkedHashMap<String, PosterTarget>()
        for (item in items) {
            val itemOps = opsByItem[item.id].orEmpty()
            if (itemOps.isEmpty() || itemOps.any { it.state != OperationState.DONE }) continue
            val url = item.metadata?.posterUrl ?: continue
            val folderSegments = when (item.type) {
                ItemType.EPISODE -> item.targetSegments.dropLast(1)
                else -> item.targetSegments
            }
            if (folderSegments.isEmpty()) continue
            val createdDirs = itemOps
                .firstOrNull { !it.isCompanion && !it.isPoster }
                ?.createdDirs
                .orEmpty()
            byFolder.putIfAbsent(
                folderSegments.joinToString("/"),
                PosterTarget(folderSegments, url, item, createdDirs),
            )
        }
        // Bounded parallelism (section-3 audit): the downloads used to
        // run strictly one after another — each with a 15 s timeout —
        // after the moves had already reported 100 %, so a run with
        // hundreds of folders could sit in the poster phase for minutes.
        // Three at a time keeps the phase short without stampeding the
        // provider or the SAF gateway; the phase itself is published
        // (KEY_PHASE) before this starts. Each target stays independent:
        // failures are logged per folder and never fail the run.
        coroutineScope {
            val semaphore = Semaphore(MAX_PARALLEL_POSTER_DOWNLOADS)
            byFolder.values.map { target ->
                async {
                    semaphore.withPermit {
                        downloadOnePoster(runId, target, destinationRoot)
                    }
                }
            }.awaitAll()
        }
    }

    /** Downloads and journals one folder's poster; see [downloadPosters]. */
    private suspend fun downloadOnePoster(
        runId: String,
        target: PosterTarget,
        destinationRoot: DirRef,
    ) {
        try {
            var dir: DirRef? = destinationRoot
            for (segment in target.segments) {
                dir = dir?.let { gateway.findChildDir(it, segment) }
                if (dir == null) break
            }
            val folder = dir ?: return
            val exists = gateway.listChildren(folder).any { child ->
                !child.isDirectory && child.ref.name.equals(POSTER_FILE_NAME, ignoreCase = true)
            }
            if (exists) return
            val bytes = PosterDownloader.fetch(target.url) ?: return
            val created = gateway.createFile(folder, POSTER_FILE_NAME, "image/jpeg", bytes)
            journalStore.insertOperation(
                OperationRecord(
                    id = UUID.randomUUID().toString(),
                    runId = runId,
                    itemId = target.item.id,
                    sourceParentId = folder.id,
                    sourceParentName = folder.name,
                    sourceFileId = created.id,
                    fileName = POSTER_FILE_NAME,
                    finalName = POSTER_FILE_NAME,
                    destinationParentId = folder.id,
                    destinationSegments = target.segments,
                    state = OperationState.DONE,
                    bytesTotal = bytes.size.toLong(),
                    bytesDone = bytes.size.toLong(),
                    isPoster = true,
                    createdDirs = target.createdDirs,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "poster save failed for %s", target.segments.joinToString("/"))
        }
    }

    /**
     * Optional post-run cleanup (spec section 8.6): removes source folders
     * that held moved files and are now empty. Source roots are never
     * removed, and only folders emptied by this run are candidates.
     */
    private suspend fun cleanupEmptySourceFolders(items: List<PlannedItem>, treatJunkAsEmpty: Boolean) {
        val sourceRootIds = libraryRepository.sourcesOnce().map { it.uriString }.toSet()
        // Deepest folders first, so parents can become empty after children go.
        val parents = items.map { it.scanned.parentDir to it.scanned.ancestors.size }
            .distinctBy { it.first.id }
            .sortedByDescending { it.second }
            .map { it.first }
        for (parent in parents) {
            if (parent.id in sourceRootIds) continue
            try {
                if (!gateway.isValid(parent)) continue
                val children = gateway.listChildren(parent)
                when {
                    children.isEmpty() -> gateway.delete(parent)
                    treatJunkAsEmpty && children.all { child ->
                        !child.isDirectory &&
                            child.ref.name.substringAfterLast('.', "").lowercase() in JUNK_EXTENSIONS
                    } -> {
                        children.forEach { child -> runCatching { gateway.delete(child.ref) } }
                        gateway.delete(parent)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "empty-folder cleanup skipped for %s", parent.name)
            }
        }
    }

    private suspend fun decodeCompanions(
        uris: String,
        names: String,
        parentId: String,
    ): List<FileRef> {
        if (uris.isEmpty()) return emptyList()
        val uriList = uris.split('\n')
        val nameList = names.split('\n')
        val refs = uriList.mapIndexed { index, uri ->
            FileRef(
                id = uri,
                name = nameList.getOrElse(index) { uri.substringAfterLast('/') },
                size = 0,
                lastModified = 0,
                parentId = parentId,
            )
        }
        // Drafts carry no companion sizes, and copyVerified checks the copied byte
        // count — a size of 0 would fail every cross-volume companion (review H7).
        // Stat the parent folder once and fill in the real sizes.
        val parentDir = DirRef(parentId, parentId.substringAfterLast('/'))
        val byId = try {
            gateway.listChildren(parentDir).associateBy { it.ref.id }
        } catch (e: Exception) {
            Timber.w(e, "companion stat failed for %s", parentId)
            emptyMap()
        }
        return refs.map { ref ->
            val entry = byId[ref.id]
            if (entry != null) ref.copy(size = entry.size, lastModified = entry.lastModified) else ref
        }
    }

    // ------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        // Notification channels exist only on API 26+; older versions post without one.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.organize_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun createForegroundInfo(percent: Int, currentFile: String?, total: Int): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val text = when {
            currentFile != null -> "$percent% · $currentFile"
            total > 0 -> "$percent%"
            else -> applicationContext.getString(R.string.organize_notification_preparing)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.organize_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total == 0)
            .addAction(
                android.R.drawable.ic_delete,
                applicationContext.getString(R.string.organize_notification_cancel),
                cancelIntent,
            )
            .build()
        val notificationId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val POSTER_FILE_NAME = "poster.jpg"

        /** Temp-file suffix of verified copies; mirrors the SAF gateway's (review L-F). */
        const val PART_SUFFIX = ".cinesort-part"

        /** Deepest destination level the stale-partial sweep descends to (review L-F). */
        const val MAX_PARTIAL_CLEANUP_DEPTH = 6
        const val CHANNEL_ID = "organize"
        const val KEY_RUN_ID = "runId"
        const val KEY_PROGRESS = "progress"
        const val KEY_DONE_ITEMS = "doneItems"
        const val KEY_TOTAL_ITEMS = "totalItems"
        const val KEY_FAILED_ITEMS = "failedItems"
        const val KEY_SKIPPED_ITEMS = "skippedItems"
        const val KEY_CURRENT_FILE = "currentFile"
        const val KEY_PHASE = "phase"

        /** Progress phase after the moves reach 100%: poster downloads (audit A.3-10). */
        const val PHASE_POSTERS = "posters"

        /** Concurrent poster downloads within the poster phase (section-3 audit). */
        const val MAX_PARALLEL_POSTER_DOWNLOADS = 3
        val JUNK_EXTENSIONS = setOf("nfo", "txt", "jpg", "url", "nomedia")
    }
}
