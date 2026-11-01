package com.cinesort.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * CineSort Room database with schema export on (spec section 12).
 * v2: operations.sourceFileId (review H7) — see MIGRATION_1_2 in DataModule.
 */
@Database(
    entities = [
        SourceFolderEntity::class,
        DestinationEntity::class,
        MetadataCacheEntity::class,
        TitleOverrideEntity::class,
        RunEntity::class,
        OperationEntity::class,
        ExecutionDraftEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class CineSortDatabase : RoomDatabase() {
    abstract fun sourceFolderDao(): SourceFolderDao
    abstract fun destinationDao(): DestinationDao
    abstract fun metadataCacheDao(): MetadataCacheDao
    abstract fun titleOverrideDao(): TitleOverrideDao
    abstract fun runDao(): RunDao
    abstract fun executionDraftDao(): ExecutionDraftDao

    companion object {
        const val DATABASE_NAME = "cinesort.db"
    }
}
