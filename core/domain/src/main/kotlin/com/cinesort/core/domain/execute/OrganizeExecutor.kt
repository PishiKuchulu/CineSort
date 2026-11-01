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
        val bytesTotal = runnable.sumOf { it.sizeBytes }
        var bytesDone = 0L
        var done = 0
        var skipped = 0
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
                failed += item.scanned.file.name to (e.message ?: e.javaClass.simpleName)
            } catch (e: SecurityException) {
                failed += item.scanned.file.name to (e.message ?: e.javaClass.simpleName)
            }
        }
        journal.markRunFinished(runId, clock())
        onProgress(ExecutionProgress(runnable.size, runnable.size, null, bytesDone, bytesTotal))
        return ExecutionReport(runId, done, failed, skipped)
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
