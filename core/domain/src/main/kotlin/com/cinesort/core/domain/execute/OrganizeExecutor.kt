package com.cinesort.core.domain.execute

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.RunRecord
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class ExecutionProgress(
    val doneItems: Int,
    val totalItems: Int,
    val currentFileName: String?,
    val bytesDone: Long,
    val bytesTotal: Long,
)

data class ExecutionReport(
    val runId: String,
    val done: Int,
    val failed: List<Pair<String, String>>,
    val skipped: Int,
)

/**
 * Sequential executor (spec section 8.3): journals every step, resumes from the journal,
 * and never lets one item's failure abort the run. The WorkManager worker in the app
 * layer drives this class; all storage goes through [StorageGateway].
 */
class OrganizeExecutor(
    private val gateway: StorageGateway,
    private val journal: JournalStore,
    private val destinationRoot: DirRef,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun execute(
        runId: String,
        items: List<PlannedItem>,
        optionsSnapshot: String = "",
        onProgress: (ExecutionProgress) -> Unit = {},
    ): ExecutionReport {
        val runnable = items.filter { it.selected && it.status == PlannedStatus.READY }
        journal.createRun(
            RunRecord(
                id = runId,
                startedAt = clock(),
                optionsSnapshot = optionsSnapshot,
                totalItems = runnable.size,
            ),
        )
        // Resume hygiene (reviews H8/M5): operations left in a non-final state by
        // an interrupted earlier attempt of this same run are closed as FAILED, so
        // the report never shows phantom in-progress rows.
        val runnableIds = runnable.map { it.id }.toSet()
        journal.operationsOf(runId)
            .filter {
                it.itemId in runnableIds &&
                    it.state != OperationState.DONE && it.state != OperationState.FAILED
            }
            .forEach {
                journal.updateOperation(
                    it.copy(
                        state = OperationState.FAILED,
                        failureReason = "interrupted before completion",
                    ),
                )
            }
        val bytesTotal = runnable.sumOf { it.sizeBytes }
        var bytesDone = 0L
        var done = 0
        // Items handed in but not runnable (unselected / not READY) are the
        // report's skipped count (review L8 — it used to stay 0 forever).
        val skipped = items.size - runnable.size
        val failed = mutableListOf<Pair<String, String>>()
        val dirCache = mutableMapOf<String, DirRef>()

        for ((index, item) in runnable.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(ExecutionProgress(index, runnable.size, item.scanned.file.name, bytesDone, bytesTotal))
            try {
                val createdDirs = mutableListOf<String>()
                val targetDir = ensureDirs(item.targetSegments, dirCache, createdDirs)
                moveOne(item.scanned.file, item, targetDir, runId, createdDirs, isCompanion = false)
                for (companion in item.companions) {
                    moveOne(companion, item, targetDir, runId, createdDirs, isCompanion = true)
                }
                done++
                bytesDone += item.sizeBytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val reason = e.message ?: e.javaClass.simpleName
                failed += item.scanned.file.name to reason
                markItemFailed(runId, item, reason)
            } catch (e: SecurityException) {
                val reason = e.message ?: e.javaClass.simpleName
                failed += item.scanned.file.name to reason
                markItemFailed(runId, item, reason)
            }
        }
        journal.markRunFinished(runId, clock())
        onProgress(ExecutionProgress(runnable.size, runnable.size, null, bytesDone, bytesTotal))
        return ExecutionReport(runId, done, failed, skipped)
    }

    /**
     * Persists an item's failure in the journal (review M5): unfinished operations
     * become FAILED with the reason; when the failure happened before any operation
     * row existed (e.g. ensureDirs), a FAILED row is created so the report and the
     * history show it.
     */
    private suspend fun markItemFailed(runId: String, item: PlannedItem, reason: String) {
        val ops = journal.operationsOf(runId).filter { it.itemId == item.id }
        val unfinished = ops.filter {
            it.state != OperationState.DONE && it.state != OperationState.FAILED
        }
        if (unfinished.isEmpty() && ops.none { !it.isCompanion }) {
            journal.insertOperation(
                OperationRecord(
                    id = idFactory(),
                    runId = runId,
                    itemId = item.id,
                    sourceParentId = item.scanned.file.parentId,
                    sourceParentName = item.scanned.parentDir.name,
                    sourceFileId = item.scanned.file.id,
                    fileName = item.scanned.file.name,
                    finalName = item.targetFileName,
                    destinationParentId = destinationRoot.id,
                    destinationSegments = item.targetSegments,
                    state = OperationState.FAILED,
                    bytesTotal = item.scanned.file.size,
                    failureReason = reason,
                    crossVolume = item.crossVolume,
                ),
            )
        } else {
            unfinished.forEach {
                journal.updateOperation(
                    it.copy(state = OperationState.FAILED, failureReason = reason),
                )
            }
        }
    }

    private suspend fun moveOne(
        file: FileRef,
        item: PlannedItem,
        targetDir: DirRef,
        runId: String,
        createdDirs: List<String>,
        isCompanion: Boolean,
    ) {
        val finalName = if (isCompanion) file.name else item.targetFileName
        var op = OperationRecord(
            id = idFactory(),
            runId = runId,
            itemId = item.id,
            sourceParentId = file.parentId,
            sourceParentName = item.scanned.parentDir.name,
            sourceFileId = file.id,
            fileName = file.name,
            finalName = finalName,
            destinationParentId = targetDir.id,
            destinationSegments = item.targetSegments,
            bytesTotal = file.size,
            isCompanion = isCompanion,
            createdDirs = createdDirs.toList(),
            crossVolume = item.crossVolume,
        )
        journal.insertOperation(op)
        op = op.copy(state = OperationState.DIRS_READY)
        journal.updateOperation(op)
        val fromDir = item.scanned.parentDir
        if (item.crossVolume) {
            op = op.copy(state = OperationState.COPYING)
            journal.updateOperation(op)
            gateway.copyVerified(file, targetDir, targetName = finalName) { progress ->
                op = op.copy(bytesDone = progress)
            }
            journal.updateOperation(op.copy(state = OperationState.VERIFIED))
            gateway.delete(file)
        } else {
            op = op.copy(state = OperationState.MOVING)
            journal.updateOperation(op)
            if (finalName == file.name) {
                gateway.move(file, fromDir, targetDir)
            } else {
                gateway.copyVerified(file, targetDir, targetName = finalName)
                gateway.delete(file)
            }
            op = op.copy(state = OperationState.VERIFIED)
            journal.updateOperation(op)
        }
        journal.updateOperation(op.copy(state = OperationState.DONE, bytesDone = file.size))
    }

    /** Resolves existing dirs under the destination root without creating anything. */
    suspend fun resolveDirs(
        segments: List<String>,
        cache: MutableMap<String, DirRef> = mutableMapOf(),
    ): DirRef? {
        var current = destinationRoot
        val path = StringBuilder()
        for (segment in segments) {
            if (path.isNotEmpty()) path.append('/')
            path.append(segment)
            val key = path.toString()
            val cached = cache[key]
            if (cached != null) {
                current = cached
            } else {
                current = gateway.findChildDir(current, segment, ignoreCase = true) ?: return null
                cache[key] = current
            }
        }
        return current
    }

    /** Creates (or reuses, case-insensitively) each segment under the destination root. */
    suspend fun ensureDirs(
        segments: List<String>,
        cache: MutableMap<String, DirRef> = mutableMapOf(),
        createdDirs: MutableList<String>? = null,
    ): DirRef {
        var current = destinationRoot
        val path = StringBuilder()
        for (segment in segments) {
            if (path.isNotEmpty()) path.append('/')
            path.append(segment)
            val key = path.toString()
            val cached = cache[key]
            if (cached != null) {
                current = cached
                continue
            }
            val existing = gateway.findChildDir(current, segment, ignoreCase = true)
            current = if (existing != null) {
                existing
            } else {
                createdDirs?.add(key)
                gateway.createDir(current, segment)
            }
            cache[key] = current
        }
        return current
    }
}
