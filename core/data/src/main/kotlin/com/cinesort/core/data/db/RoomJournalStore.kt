package com.cinesort.core.data.db

import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.RunRecord

/** Room-backed [JournalStore] (spec sections 8.5, 12). */
class RoomJournalStore(
    private val runDao: RunDao,
) : JournalStore {

    override suspend fun createRun(run: RunRecord) {
        runDao.insertRun(run.toEntity())
    }

    override suspend fun insertOperation(op: OperationRecord) {
        runDao.insertOperation(op.toEntity())
    }

    override suspend fun updateOperation(op: OperationRecord) {
        runDao.updateOperation(op.toEntity())
    }

    override suspend fun operationsOf(runId: String): List<OperationRecord> =
        runDao.operationsOf(runId).map { it.toRecord() }

    override suspend fun run(id: String): RunRecord? = runDao.run(id)?.toRecord()

    override suspend fun recentRuns(limit: Int): List<RunRecord> =
        runDao.recentRuns(limit).map { it.toRecord() }

    override suspend fun markRunFinished(runId: String, finishedAt: Long) {
        runDao.markFinished(runId, finishedAt)
    }

    override suspend fun markRunUndone(runId: String) {
        runDao.markUndone(runId)
    }

    override suspend fun unfinishedRuns(): List<RunRecord> =
        runDao.unfinishedRuns().map { it.toRecord() }

    // ------------------------------------------------------------ mapping

    private fun RunRecord.toEntity(): RunEntity = RunEntity(
        id = id,
        startedAt = startedAt,
        finishedAt = finishedAt,
        optionsSnapshot = optionsSnapshot,
        totalItems = totalItems,
        undone = undone,
    )

    private fun RunEntity.toRecord(): RunRecord = RunRecord(
        id = id,
        startedAt = startedAt,
        finishedAt = finishedAt,
        optionsSnapshot = optionsSnapshot,
        totalItems = totalItems,
        undone = undone,
    )

    private fun OperationRecord.toEntity(): OperationEntity = OperationEntity(
        id = id,
        runId = runId,
        itemId = itemId,
        sourceParentId = sourceParentId,
        sourceParentName = sourceParentName,
        sourceFileId = sourceFileId,
        fileName = fileName,
        finalName = finalName,
        destinationParentId = destinationParentId,
        destinationSegments = destinationSegments.joinToString("/"),
        state = state.name,
        bytesTotal = bytesTotal,
        bytesDone = bytesDone,
        isCompanion = isCompanion,
        isPoster = isPoster,
        failureReason = failureReason,
        createdDirs = createdDirs.joinToString("\n"),
        crossVolume = crossVolume,
    )

    private fun OperationEntity.toRecord(): OperationRecord = OperationRecord(
        id = id,
        runId = runId,
        itemId = itemId,
        sourceParentId = sourceParentId,
        sourceParentName = sourceParentName,
        sourceFileId = sourceFileId,
        fileName = fileName,
        finalName = finalName,
        destinationParentId = destinationParentId,
        destinationSegments = if (destinationSegments.isEmpty()) {
            emptyList()
        } else {
            destinationSegments.split('/')
        },
        state = runCatching { OperationState.valueOf(state) }.getOrDefault(OperationState.PENDING),
        bytesTotal = bytesTotal,
        bytesDone = bytesDone,
        isCompanion = isCompanion,
        isPoster = isPoster,
        failureReason = failureReason,
        createdDirs = if (createdDirs.isEmpty()) emptyList() else createdDirs.split('\n'),
        crossVolume = crossVolume,
    )
}
