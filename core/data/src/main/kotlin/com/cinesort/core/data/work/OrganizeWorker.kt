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
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.parser.AnchorKind
import com.cinesort.core.parser.Confidence
import com.cinesort.core.parser.ParseResult
import com.cinesort.core.parser.ParseTrace
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Foreground worker that executes one persisted run (spec section 8.3).
 *
 * The plan is rebuilt from execution drafts written before the run started,
 * so a process death mid-run loses nothing: drafts plus the journal carry
 * the full state. Leftover `.cinesort-part` files need no dedicated cleanup
 * worker — the SAF gateway removes them when the next scan encounters them.
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
        val drafts = draftRepository.draftsFor(runId)
        if (drafts.isEmpty()) {
            Timber.w("organize run %s has no drafts", runId)
            return Result.failure()
        }
        val destination = libraryRepository.destinationOnce() ?: return Result.failure()
        val destinationRoot = DirRef(destination.uriString, destination.name)

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
        setForeground(
            createForegroundInfo(percent = 0, currentFile = null, total = pendingItems.size),
        )

        val executor = OrganizeExecutor(gateway, journalStore, destinationRoot)
        var lastPercent = -1
        val report = try {
            executor.execute(runId, pendingItems) { progress ->
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
        } catch (e: Exception) {
            Timber.e(e, "organize run %s failed", runId)
            return Result.failure()
        }

        val settings = settingsRepository.current()
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
        const val CHANNEL_ID = "organize"
        const val KEY_RUN_ID = "runId"
        const val KEY_PROGRESS = "progress"
        const val KEY_DONE_ITEMS = "doneItems"
        const val KEY_TOTAL_ITEMS = "totalItems"
        const val KEY_FAILED_ITEMS = "failedItems"
        const val KEY_SKIPPED_ITEMS = "skippedItems"
        const val KEY_CURRENT_FILE = "currentFile"
        val JUNK_EXTENSIONS = setOf("nfo", "txt", "jpg", "url", "nomedia")
    }
}
