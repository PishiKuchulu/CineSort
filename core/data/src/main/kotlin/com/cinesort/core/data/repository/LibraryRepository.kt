package com.cinesort.core.data.repository

import com.cinesort.core.data.db.DestinationDao
import com.cinesort.core.data.db.DestinationEntity
import com.cinesort.core.data.db.SourceFolderDao
import com.cinesort.core.data.db.SourceFolderEntity
import com.cinesort.core.data.db.TitleOverrideDao
import com.cinesort.core.data.db.TitleOverrideEntity
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.TitleOverride
import kotlinx.coroutines.flow.Flow

/** Source folders, destination, and title overrides (spec sections 5.1, 6.6, 12). */
class LibraryRepository(
    private val sourceFolderDao: SourceFolderDao,
    private val destinationDao: DestinationDao,
    private val titleOverrideDao: TitleOverrideDao,
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
    ): Long = sourceFolderDao.insert(
        SourceFolderEntity(
            uriString = uriString,
            name = name,
            includeSubfolders = includeSubfolders,
            maxDepth = maxDepth.coerceIn(1, 10),
        ),
    )

    suspend fun removeSource(id: Long) {
        sourceFolderDao.deleteById(id)
    }

    suspend fun updateSourceOptions(
        id: Long,
        includeSubfolders: Boolean,
        maxDepth: Int,
    ) {
        sourceFolderDao.updateOptions(id, includeSubfolders, maxDepth.coerceIn(1, 10))
    }

    suspend fun setDestination(uriString: String, name: String) {
        destinationDao.insert(DestinationEntity(id = 0L, uriString = uriString, name = name))
    }

    suspend fun clearDestination() {
        destinationDao.clear()
    }

    suspend fun deleteOverride(id: Long) {
        titleOverrideDao.deleteById(id)
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
