package com.cinesort.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

@Dao
interface ExecutionDraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ExecutionDraftEntity>)

    @Query("SELECT * FROM execution_drafts WHERE runId = :runId")
    suspend fun getByRun(runId: String): List<ExecutionDraftEntity>

    @Query("DELETE FROM execution_drafts WHERE runId = :runId")
    suspend fun clearRun(runId: String)

    @Query("DELETE FROM execution_drafts")
    suspend fun clearAll()
}
