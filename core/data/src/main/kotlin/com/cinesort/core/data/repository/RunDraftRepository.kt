package com.cinesort.core.data.repository

import android.net.Uri
import android.provider.DocumentsContract
import com.cinesort.core.data.db.ExecutionDraftDao
import com.cinesort.core.data.saf.SafUris
import com.cinesort.core.data.db.ExecutionDraftEntity
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.parser.ParseResult

/** Persisted plan drafts that let a run survive process death (spec section 8.3). */
class RunDraftRepository(
    private val draftDao: ExecutionDraftDao,
) {
    suspend fun writeDrafts(runId: String, drafts: List<ExecutionDraftEntity>) {
        draftDao.clearRun(runId)
        if (drafts.isNotEmpty()) draftDao.insertAll(drafts)
    }

    /** Builds and stores drafts straight from planned items. */
    suspend fun writePlannedItems(runId: String, items: List<PlannedItem>) {
        writeDrafts(runId, items.map { it.toDraft(runId) })
    }

    /**
     * Builds drafts for retrying the failed items of a finished run from its journal
     * operations. Operations are grouped by item: the main (non-companion) operation
     * supplies the file, target segments and final name; companion operations travel
     * with it. File document URIs come from the source file ids recorded at
     * execution time; for pre-v2 journals without them they are reconstructed from
     * the recorded parent document URI plus the file name. Fields the journal does
     * not carry (title, last-modified) stay neutral — the worker's executor path
     * only needs the file, companions and target.
     */
    suspend fun writeRetryDrafts(newRunId: String, operations: List<OperationRecord>) {
        val byItem = operations.groupBy { it.itemId }
        val drafts = byItem.values.mapNotNull { itemOps ->
            val main = itemOps.firstOrNull { !it.isCompanion } ?: return@mapNotNull null
            val companions = itemOps.filter { it.isCompanion }
            // Prefer the file id recorded at execution time (review H7); the
            // name-based reconstruction is only a fallback for pre-v2 journals.
            val mainUri = main.sourceFileId.ifEmpty {
                childDocumentUri(main.sourceParentId, main.fileName) ?: ""
            }
            if (mainUri.isEmpty()) return@mapNotNull null
            // Keep URI/name lists index-aligned: drop companions whose URI is underivable.
            val companionPairs = companions.mapNotNull { op ->
                val uri = op.sourceFileId.ifEmpty {
                    childDocumentUri(op.sourceParentId, op.fileName) ?: ""
                }
                if (uri.isEmpty()) null else uri to op.fileName
            }
            ExecutionDraftEntity(
                id = main.itemId,
                runId = newRunId,
                fileUri = mainUri,
                fileName = main.fileName,
                fileSize = main.bytesTotal,
                fileLastModified = 0L,
                parentUri = main.sourceParentId,
                parentName = main.sourceParentName,
                ancestors = "",
                companionUris = companionPairs.joinToString("\n") { it.first },
                companionNames = companionPairs.joinToString("\n") { it.second },
                type = ItemType.MOVIE.name,
                title = "",
                year = null,
                season = null,
                episodes = "",
                targetSegments = main.destinationSegments.joinToString("/"),
                targetFileName = main.finalName,
                crossVolume = main.crossVolume,
                sourceVolume = main.sourceParentId.substringBefore(':', ""),
            )
        }
        writeDrafts(newRunId, drafts)
    }

    /**
     * Child document URI for a file directly inside [parentUri], or null when it
     * cannot be derived. Never falls back to the parent URI itself (review H7):
     * a draft pointing at the parent folder would make the executor try to move
     * the folder. Callers skip items whose URI cannot be derived.
     */
    private fun childDocumentUri(parentUri: String, fileName: String): String? {
        return try {
            val parent = Uri.parse(parentUri)
            val parentDocId = SafUris.documentIdOf(parent)
            val childDocId = if (parentDocId.endsWith("/")) {
                parentDocId + fileName
            } else {
                "$parentDocId/$fileName"
            }
            DocumentsContract.buildDocumentUriUsingTree(SafUris.treeUriOf(parent), childDocId)
                .toString()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun draftsFor(runId: String): List<ExecutionDraftEntity> = draftDao.getByRun(runId)

    suspend fun clearRun(runId: String) {
        draftDao.clearRun(runId)
    }

    private fun PlannedItem.toDraft(runId: String): ExecutionDraftEntity {
        val effective = effective
        val title: String
        val year: Int?
        val season: Int?
        val episodes: List<Int>
        when (effective) {
            is ParseResult.Movie -> {
                title = effective.title
                year = effective.year
                season = null
                episodes = emptyList()
            }
            is ParseResult.Episode -> {
                title = effective.seriesTitle
                year = effective.seriesYearHint
                season = effective.season
                episodes = effective.episodes
            }
            else -> {
                title = ""
                year = null
                season = null
                episodes = emptyList()
            }
        }
        return ExecutionDraftEntity(
            id = id,
            runId = runId,
            fileUri = scanned.file.id,
            fileName = scanned.file.name,
            fileSize = scanned.file.size,
            fileLastModified = scanned.file.lastModified,
            parentUri = scanned.parentDir.id,
            parentName = scanned.parentDir.name,
            ancestors = scanned.ancestors.joinToString("\n"),
            companionUris = companions.joinToString("\n") { it.id },
            companionNames = companions.joinToString("\n") { it.name },
            type = (type ?: ItemType.MOVIE).name,
            title = title,
            year = year,
            season = season,
            episodes = episodes.joinToString(","),
            targetSegments = targetSegments.joinToString("/"),
            targetFileName = targetFileName,
            crossVolume = crossVolume,
            sourceVolume = scanned.sourceVolume,
            posterUrl = metadata?.posterUrl,
        )
    }
}
