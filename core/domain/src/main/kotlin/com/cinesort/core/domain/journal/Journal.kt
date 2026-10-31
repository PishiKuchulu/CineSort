package com.cinesort.core.domain.journal

/** Persisted per-operation state machine (spec section 8.3). */
enum class OperationState {
    PENDING,
    DIRS_READY,
    MOVING,
    COPYING,
    VERIFIED,
    DONE,
    FAILED,
    SKIPPED,
}

data class OperationRecord(
    val id: String,
    val runId: String,
    val itemId: String,
    /** Original parent directory id and the file's original name. */
    val sourceParentId: String,
    val sourceParentName: String,
    val fileName: String,
    /** Final name in the destination (differs only for the Keep-both conflict choice). */
    val finalName: String,
    val destinationParentId: String,
    /** Destination path segments relative to the destination root. */
    val destinationSegments: List<String>,
    val state: OperationState = OperationState.PENDING,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val isCompanion: Boolean = false,
    val failureReason: String? = null,
    /** Directories this run created for this operation, relative segments, deepest last. */
    val createdDirs: List<String> = emptyList(),
    val crossVolume: Boolean = false,
)

data class RunRecord(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val optionsSnapshot: String = "",
    val totalItems: Int = 0,
    val undone: Boolean = false,
)

/** Persistence port for the journal; Room implements it in core:data. */
interface JournalStore {
    suspend fun createRun(run: RunRecord)
    suspend fun insertOperation(op: OperationRecord)
    suspend fun updateOperation(op: OperationRecord)
    suspend fun operationsOf(runId: String): List<OperationRecord>
    suspend fun run(id: String): RunRecord?
    suspend fun recentRuns(limit: Int): List<RunRecord>
    suspend fun markRunFinished(runId: String, finishedAt: Long)
    suspend fun markRunUndone(runId: String)
    suspend fun unfinishedRuns(): List<RunRecord>
}

/** In-memory journal used by tests and previews. */
class InMemoryJournalStore : JournalStore {
    private val runs = linkedMapOf<String, RunRecord>()
    private val ops = linkedMapOf<String, OperationRecord>()

    override suspend fun createRun(run: RunRecord) {
        runs[run.id] = run
    }

    override suspend fun insertOperation(op: OperationRecord) {
        ops[op.id] = op
    }

    override suspend fun updateOperation(op: OperationRecord) {
        ops[op.id] = op
    }

    override suspend fun operationsOf(runId: String): List<OperationRecord> =
        ops.values.filter { it.runId == runId }

    override suspend fun run(id: String): RunRecord? = runs[id]

    override suspend fun recentRuns(limit: Int): List<RunRecord> =
        runs.values.sortedByDescending { it.startedAt }.take(limit)

    override suspend fun markRunFinished(runId: String, finishedAt: Long) {
        runs[runId]?.let { runs[runId] = it.copy(finishedAt = finishedAt) }
    }

    override suspend fun markRunUndone(runId: String) {
        runs[runId]?.let { runs[runId] = it.copy(undone = true) }
    }

    override suspend fun unfinishedRuns(): List<RunRecord> =
        runs.values.filter { it.finishedAt == null }
}
