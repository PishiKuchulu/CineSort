package com.cinesort.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** CineSort Room database, version 1 with schema export on (spec section 12). */
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
    version = 1,
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
