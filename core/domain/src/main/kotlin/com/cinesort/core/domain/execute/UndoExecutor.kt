package com.cinesort.core.domain.execute

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import java.io.IOException

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
        val problems = mutableListOf<String>()
        val createdDirs = sortedSetOf<String>(compareByDescending { it.count { c -> c == '/' } })

        // Resolve destination dirs per operation from its segments.
        val executor = OrganizeExecutor(gateway, journal, destinationRoot)
        val dirCache = mutableMapOf<String, DirRef>()
        var movedBack = 0
        for (op in ops.asReversed()) {
            createdDirs += op.createdDirs
            if (undoOperation(op, executor, dirCache, problems)) movedBack++
        }

        val removedDirs = removeEmptyCreatedDirs(createdDirs, problems)
        if (problems.isEmpty()) journal.markRunUndone(runId)
        return UndoReport(runId, movedBack, problems, removedDirs)
    }

    /** Undoes one operation; returns true when its file was moved back. */
    private suspend fun undoOperation(
        op: OperationRecord,
        executor: OrganizeExecutor,
        dirCache: MutableMap<String, DirRef>,
        problems: MutableList<String>,
    ): Boolean {
        try {
            val located = locateMovedFile(op, executor, dirCache, problems) ?: return false
            return moveBack(op, located.first, located.second, problems)
        } catch (e: IOException) {
            problems += "${op.finalName}: ${e.message ?: e.javaClass.simpleName}"
            return false
        } catch (e: SecurityException) {
            problems += "${op.finalName}: ${e.message ?: e.javaClass.simpleName}"
            return false
        }
    }

    /** Finds the moved file and its current directory, recording why when it is gone. */
    private suspend fun locateMovedFile(
        op: OperationRecord,
        executor: OrganizeExecutor,
        dirCache: MutableMap<String, DirRef>,
        problems: MutableList<String>,
    ): Pair<FileRef, DirRef>? {
        val currentDir = executor.resolveDirs(op.destinationSegments, dirCache)
        if (currentDir == null) {
            problems += "${op.finalName}: destination folder is gone"
            return null
        }
        val currentFile = gateway.listChildren(currentDir)
            .firstOrNull { !it.isDirectory && it.ref.name == op.finalName }
            ?.ref as? FileRef
        if (currentFile == null) {
            problems += "${op.finalName}: file no longer in destination"
            return null
        }
        return currentFile to currentDir
    }

    /** Moves [file] from [currentDir] back to the operation's original parent folder. */
    private suspend fun moveBack(
        op: OperationRecord,
        file: FileRef,
        currentDir: DirRef,
        problems: MutableList<String>,
    ): Boolean {
        val originalParent = DirRef(op.sourceParentId, op.sourceParentName)
        if (!gateway.isValid(originalParent)) {
            problems += "${op.finalName}: original folder is gone"
            return false
        }
        val nameTaken = gateway.listChildren(originalParent)
            .any { !it.isDirectory && it.ref.name == op.fileName }
        if (nameTaken) {
            problems += "${op.finalName}: original name is occupied"
            return false
        }
        val sameVolume = gateway.volumeIdOf(file) == gateway.volumeIdOf(originalParent)
        if (sameVolume && op.finalName == op.fileName) {
            gateway.move(file, currentDir, originalParent)
        } else {
            gateway.copyVerified(file, originalParent, targetName = op.fileName)
            gateway.delete(file)
        }
        return true
    }

    /** Removes run-created directories that are still empty, deepest first. */
    private suspend fun removeEmptyCreatedDirs(
        createdDirs: Set<String>,
        problems: MutableList<String>,
    ): Int {
        var removedDirs = 0
        for (dirPath in createdDirs) {
            try {
                val dir = resolveFromDestination(dirPath) ?: continue
                if (gateway.listChildren(dir).isEmpty()) {
                    gateway.delete(dir)
                    removedDirs++
                }
            } catch (e: IOException) {
                problems += "dir $dirPath: ${e.message ?: e.javaClass.simpleName}"
            } catch (e: SecurityException) {
                problems += "dir $dirPath: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return removedDirs
    }

    /** Walks [dirPath] segments down from the destination root; null when any is missing. */
    private suspend fun resolveFromDestination(dirPath: String): DirRef? {
        var current: DirRef = destinationRoot
        for (segment in dirPath.split('/')) {
            current = gateway.findChildDir(current, segment, ignoreCase = true) ?: return null
        }
        return current
    }
}
