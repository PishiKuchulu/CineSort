package com.cinesort.core.domain.execute

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef

data class UndoReport(
    val runId: String,
    val movedBack: Int,
    val problems: List<String>,
    val removedDirs: Int,
)

/**
 * Undo (spec section 8.5): moves a run's files back to their original parents, then
 * removes only directories created by that run that are still empty. Items whose file
 * vanished or whose original name is now occupied are reported, never forced.
 */
class UndoExecutor(
    private val gateway: StorageGateway,
    private val journal: JournalStore,
    private val destinationRoot: DirRef,
) {
    suspend fun undo(runId: String): UndoReport {
        val run = journal.run(runId) ?: return UndoReport(runId, 0, listOf("run not found"), 0)
        if (run.undone) return UndoReport(runId, 0, listOf("run already undone"), 0)
        val ops = journal.operationsOf(runId).filter { it.state == OperationState.DONE }
        var movedBack = 0
        val problems = mutableListOf<String>()
        val createdDirs = sortedSetOf<String>(compareByDescending { it.count { c -> c == '/' } })

        // Resolve destination dirs per operation from its segments.
        val executor = OrganizeExecutor(gateway, journal, destinationRoot)
        val dirCache = mutableMapOf<String, DirRef>()

        for (op in ops.asReversed()) {
            createdDirs += op.createdDirs
            try {
                val currentDir = executor.resolveDirs(op.destinationSegments, dirCache)
                if (currentDir == null) {
                    problems += "${op.finalName}: destination folder is gone"
                    continue
                }
                val currentFile = gateway.listChildren(currentDir)
                    .firstOrNull { !it.isDirectory && it.ref.name == op.finalName }
                    ?.ref as? FileRef
                if (currentFile == null) {
                    problems += "${op.finalName}: file no longer in destination"
                    continue
                }
                val originalParent = DirRef(op.sourceParentId, op.sourceParentName)
                if (!gateway.isValid(originalParent)) {
                    problems += "${op.finalName}: original folder is gone"
                    continue
                }
                val nameTaken = gateway.listChildren(originalParent)
                    .any { !it.isDirectory && it.ref.name == op.fileName }
                if (nameTaken) {
                    problems += "${op.finalName}: original name is occupied"
                    continue
                }
                val sameVolume = gateway.volumeIdOf(currentFile) == gateway.volumeIdOf(originalParent)
                if (sameVolume && op.finalName == op.fileName) {
                    gateway.move(currentFile, currentDir, originalParent)
                } else {
                    gateway.copyVerified(currentFile, originalParent, targetName = op.fileName)
                    gateway.delete(currentFile)
                }
                movedBack++
            } catch (e: Exception) {
                problems += "${op.finalName}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        // Remove run-created directories that are still empty, deepest first.
        var removedDirs = 0
        for (dirPath in createdDirs) {
            try {
                val segments = dirPath.split('/')
                var current: DirRef = destinationRoot
                var resolved: DirRef? = destinationRoot
                for (segment in segments) {
                    val next = gateway.findChildDir(current, segment, ignoreCase = true)
                    if (next == null) {
                        resolved = null
                        break
                    }
                    current = next
                    resolved = next
                }
                val dir = resolved ?: continue
                if (gateway.listChildren(dir).isEmpty()) {
                    gateway.delete(dir)
                    removedDirs++
                }
            } catch (e: Exception) {
                problems += "dir $dirPath: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (problems.isEmpty()) journal.markRunUndone(runId)
        return UndoReport(runId, movedBack, problems, removedDirs)
    }
}
