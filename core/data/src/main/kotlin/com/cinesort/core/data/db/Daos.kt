package com.cinesort.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceFolderDao {
    @Query("SELECT * FROM source_folders ORDER BY id ASC")
    fun observeAll(): Flow<List<SourceFolderEntity>>

    @Query("SELECT * FROM source_folders ORDER BY id ASC")
    suspend fun getAll(): List<SourceFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SourceFolderEntity): Long

    @Delete
    suspend fun delete(entity: SourceFolderEntity)

    @Query("DELETE FROM source_folders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "UPDATE source_folders SET includeSubfolders = :includeSubfolders, " +
            "maxDepth = :maxDepth WHERE id = :id",
    )
    suspend fun updateOptions(id: Long, includeSubfolders: Boolean, maxDepth: Int)
}

@Dao
interface DestinationDao {
    @Query("SELECT * FROM destination WHERE id = 0")
    fun observe(): Flow<DestinationEntity?>

    @Query("SELECT * FROM destination WHERE id = 0")
    suspend fun get(): DestinationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DestinationEntity)

    @Query("DELETE FROM destination WHERE id = 0")
    suspend fun clear()
}

@Dao
interface MetadataCacheDao {
    @Query("SELECT * FROM metadata_cache WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): MetadataCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MetadataCacheEntity)

    @Query("DELETE FROM metadata_cache WHERE cacheKey = :cacheKey")
    suspend fun delete(cacheKey: String)

    @Query("DELETE FROM metadata_cache")
    suspend fun clearAll()

    // Cache upkeep (section-3 audit): expired rows used to live forever —
    // reads treated them as misses but nothing ever deleted them, and the
    // table grew by one row per unique title forever.

    /** Negative results past their 7-day TTL. */
    @Query("DELETE FROM metadata_cache WHERE negative = 1 AND fetchedAt < :cutoff")
    suspend fun deleteExpiredNegatives(cutoff: Long)

    /** Any row past the 30-day success TTL is expired under every TTL. */
    @Query("DELETE FROM metadata_cache WHERE fetchedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM metadata_cache")
    suspend fun count(): Int

    /** Oldest-first eviction for the row cap. */
    @Query(
        "DELETE FROM metadata_cache WHERE cacheKey IN " +
            "(SELECT cacheKey FROM metadata_cache ORDER BY fetchedAt ASC LIMIT :count)",
    )
    suspend fun deleteOldest(count: Int)
}

@Dao
interface TitleOverrideDao {
    @Query("SELECT * FROM title_overrides ORDER BY correctedTitle ASC")
    fun observeAll(): Flow<List<TitleOverrideEntity>>

    @Query("SELECT * FROM title_overrides")
    suspend fun getAll(): List<TitleOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TitleOverrideEntity)

    @Query("DELETE FROM title_overrides WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "UPDATE title_overrides SET correctedTitle = :correctedTitle, " +
            "correctedYear = :correctedYear WHERE id = :id",
    )
    suspend fun updateById(id: Long, correctedTitle: String, correctedYear: Int?)

    @Query("DELETE FROM title_overrides WHERE normalizedKey = :normalizedKey AND type = :type")
    suspend fun delete(normalizedKey: String, type: String)
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(entity: RunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(entity: OperationEntity)

    @Update
    suspend fun updateOperation(entity: OperationEntity)

    @Query("SELECT * FROM operations WHERE runId = :runId")
    suspend fun operationsOf(runId: String): List<OperationEntity>

    /**
     * Distinct (item, state) pairs of a run's MAIN operations (section-3
     * audit): the counting summary the Run screen polls, instead of a
     * full entity list per tick. At most one row per state an item has
     * actually been in, and only two narrow columns.
     */
    @Query(
        "SELECT DISTINCT itemId, state FROM operations WHERE runId = :runId " +
            "AND isCompanion = 0 AND isPoster = 0",
    )
    suspend fun itemStates(runId: String): List<ItemStateRow>

    /** DONE poster operations of a run (tallied apart from items). */
    @Query(
        "SELECT COUNT(*) FROM operations WHERE runId = :runId " +
            "AND isPoster = 1 AND state = 'DONE'",
    )
    suspend fun countDonePosters(runId: String): Int

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun run(id: String): RunEntity?

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentRuns(limit: Int): List<RunEntity>

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentRuns(limit: Int): Flow<List<RunEntity>>

    @Query("UPDATE runs SET finishedAt = :finishedAt WHERE id = :runId")
    suspend fun markFinished(runId: String, finishedAt: Long)

    @Query("UPDATE runs SET undone = 1 WHERE id = :runId")
    suspend fun markUndone(runId: String)

    @Query("SELECT * FROM runs WHERE finishedAt IS NULL")
    suspend fun unfinishedRuns(): List<RunEntity>
}

/** Projection of [RunDao.itemStates]: one distinct state of one item. */
data class ItemStateRow(
    val itemId: String,
    val state: String,
)

@Dao
interface ExecutionDraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ExecutionDraftEntity>)

    @Query("SELECT * FROM execution_drafts WHERE runId = :runId")
    suspend fun getByRun(runId: String): List<ExecutionDraftEntity>

    @Query("DELETE FROM execution_drafts WHERE runId = :runId")
    suspend fun clearRun(runId: String)

    /**
     * Atomic draft rewrite (section-4 audit): clear + insert used to be
     * two separate operations, so process death between them wiped the
     * run's drafts and with them its only resume input. Inside one
     * transaction the run keeps either the old drafts or the new ones.
     */
    @Transaction
    suspend fun replaceRun(runId: String, entities: List<ExecutionDraftEntity>) {
        clearRun(runId)
        if (entities.isNotEmpty()) insertAll(entities)
    }

    @Query("DELETE FROM execution_drafts")
    suspend fun clearAll()
}
