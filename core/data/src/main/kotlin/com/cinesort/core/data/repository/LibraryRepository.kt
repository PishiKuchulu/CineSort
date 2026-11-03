package com.cinesort.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cinesort.core.data.db.DestinationDao
import com.cinesort.core.data.db.DestinationEntity
import com.cinesort.core.data.db.SourceFolderDao
import com.cinesort.core.data.db.SourceFolderEntity
import com.cinesort.core.data.db.TitleOverrideDao
import com.cinesort.core.data.db.TitleOverrideEntity
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.TitleOverride
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/** Outcome of [LibraryRepository.addSource] (audit B.3). */
data class AddSourceResult(
    val id: Long,
    /** True when the folder was already a source and only its options changed. */
    val alreadyExisted: Boolean,
)

/** Source folders, destination, and title overrides (spec sections 5.1, 6.6, 12). */
class LibraryRepository(
    private val sourceFolderDao: SourceFolderDao,
    private val destinationDao: DestinationDao,
    private val titleOverrideDao: TitleOverrideDao,
    private val appContext: Context,
) {
    val sources: Flow<List<SourceFolderEntity>> = sourceFolderDao.observeAll()

    val destination: Flow<DestinationEntity?> = destinationDao.observe()

    val overrides: Flow<List<TitleOverrideEntity>> = titleOverrideDao.observeAll()

    suspend fun sourcesOnce(): List<SourceFolderEntity> = sourceFolderDao.getAll()

    suspend fun destinationOnce(): DestinationEntity? = destinationDao.get()

    suspend fun addSource(
        uriString: String,
        name: String,
        includeSubfolders: Boolean,
        maxDepth: Int,
    ): AddSourceResult {
        // One row per tree (review L7): re-adding the same folder updates its
        // options instead of inserting a duplicate (the unique index would
        // reject it anyway). The result tells the caller which happened —
        // before this the merge was silent and the user could believe a
        // second source had been added (audit B.3).
        val existing = sourceFolderDao.getAll().firstOrNull { it.uriString == uriString }
        if (existing != null) {
            sourceFolderDao.updateOptions(existing.id, includeSubfolders, maxDepth.coerceIn(1, 10))
            return AddSourceResult(existing.id, alreadyExisted = true)
        }
        return AddSourceResult(
            id = sourceFolderDao.insert(
                SourceFolderEntity(
                    uriString = uriString,
                    name = name,
                    includeSubfolders = includeSubfolders,
                    maxDepth = maxDepth.coerceIn(1, 10),
                ),
            ),
            alreadyExisted = false,
        )
    }

    suspend fun removeSource(id: Long) {
        val removed = sourceFolderDao.getAll().firstOrNull { it.id == id }
        sourceFolderDao.deleteById(id)
        // Release the persisted grant with the row (review L6): grants are
        // capped per app, so removed folders must not keep consuming them.
        if (removed != null && destinationOnce()?.uriString != removed.uriString) {
            releasePermission(removed.uriString)
        }
    }

    suspend fun updateSourceOptions(
        id: Long,
        includeSubfolders: Boolean,
        maxDepth: Int,
    ) {
        sourceFolderDao.updateOptions(id, includeSubfolders, maxDepth.coerceIn(1, 10))
    }

    suspend fun setDestination(uriString: String, name: String) {
        val previous = destinationDao.get()
        destinationDao.insert(DestinationEntity(id = 0L, uriString = uriString, name = name))
        if (previous != null && previous.uriString != uriString &&
            sourceFolderDao.getAll().none { it.uriString == previous.uriString }
        ) {
            releasePermission(previous.uriString)
        }
    }

    suspend fun clearDestination() {
        val previous = destinationDao.get()
        destinationDao.clear()
        if (previous != null &&
            sourceFolderDao.getAll().none { it.uriString == previous.uriString }
        ) {
            releasePermission(previous.uriString)
        }
    }

    /** Releases the persistable read+write grant for a tree (review L6). */
    private fun releasePermission(uriString: String) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Timber.w(e, "no persistable grant to release for %s", uriString)
        }
    }

    suspend fun deleteOverride(id: Long) {
        titleOverrideDao.deleteById(id)
    }

    /** Edits a saved correction in place (audit A.1-5). */
    suspend fun updateOverride(id: Long, correctedTitle: String, correctedYear: Int?) {
        titleOverrideDao.updateById(id, correctedTitle, correctedYear)
    }

    suspend fun saveOverride(override: TitleOverride) {
        titleOverrideDao.insert(
            TitleOverrideEntity(
                normalizedKey = override.normalizedKey,
                type = override.type.name,
                correctedTitle = override.correctedTitle,
                correctedYear = override.correctedYear,
            ),
        )
    }

    /** Domain-layer overrides for the planner. */
    suspend fun titleOverrides(): List<TitleOverride> =
        titleOverrideDao.getAll().mapNotNull { entity ->
            val type = runCatching { ItemType.valueOf(entity.type) }.getOrNull() ?: return@mapNotNull null
            TitleOverride(
                normalizedKey = entity.normalizedKey,
                type = type,
                correctedTitle = entity.correctedTitle,
                correctedYear = entity.correctedYear,
            )
        }
}
