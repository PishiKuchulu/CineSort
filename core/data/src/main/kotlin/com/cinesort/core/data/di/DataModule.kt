package com.cinesort.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cinesort.core.data.db.CineSortDatabase
import com.cinesort.core.data.db.DestinationDao
import com.cinesort.core.data.db.ExecutionDraftDao
import com.cinesort.core.data.db.MetadataCacheDao
import com.cinesort.core.data.db.RoomJournalStore
import com.cinesort.core.data.db.RunDao
import com.cinesort.core.data.db.SourceFolderDao
import com.cinesort.core.data.db.TitleOverrideDao
import com.cinesort.core.data.metadata.MetadataService
import com.cinesort.core.data.repository.LibraryRepository
import com.cinesort.core.data.repository.RunDraftRepository
import com.cinesort.core.data.repository.SecretStore
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.saf.SafStorageGateway
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide coroutine scope that outlives every ViewModel. Used for
 * work that must finish even when the screen that started it goes away —
 * e.g. flushing a debounced API-key save from `ViewModel.onCleared()`
 * (review M-I), where `viewModelScope` is already cancelled.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** v1 -> v2: operations gain the recorded source file id (review H7), and
 *  source folders gain a unique uri index (review L7; duplicates merged first). */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE operations ADD COLUMN sourceFileId TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "DELETE FROM source_folders WHERE id NOT IN " +
                "(SELECT MIN(id) FROM source_folders GROUP BY uriString)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_source_folders_uriString " +
                "ON source_folders (uriString)",
        )
    }
}

/** v3: drafts carry the matched poster URL for the optional poster download. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE execution_drafts ADD COLUMN posterUrl TEXT")
    }
}

/** v4: operations record downloaded posters so undo can remove them (review M-A). */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE operations ADD COLUMN isPoster INTEGER NOT NULL DEFAULT 0")
    }
}

/** v5: runs record their destination root so Undo uses it (audit B.8). */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE runs ADD COLUMN destinationId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE runs ADD COLUMN destinationName TEXT NOT NULL DEFAULT ''")
    }
}

/** Hilt wiring for the data layer (spec sections 4, 12). */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CineSortDatabase =
        Room.databaseBuilder(context, CineSortDatabase::class.java, CineSortDatabase.DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideSourceFolderDao(database: CineSortDatabase): SourceFolderDao = database.sourceFolderDao()

    @Provides
    fun provideDestinationDao(database: CineSortDatabase): DestinationDao = database.destinationDao()

    @Provides
    fun provideMetadataCacheDao(database: CineSortDatabase): MetadataCacheDao = database.metadataCacheDao()

    @Provides
    fun provideTitleOverrideDao(database: CineSortDatabase): TitleOverrideDao = database.titleOverrideDao()

    @Provides
    fun provideRunDao(database: CineSortDatabase): RunDao = database.runDao()

    @Provides
    fun provideExecutionDraftDao(database: CineSortDatabase): ExecutionDraftDao =
        database.executionDraftDao()

    @Provides
    @Singleton
    fun provideStorageGateway(@ApplicationContext context: Context): StorageGateway =
        SafStorageGateway(context)

    @Provides
    @Singleton
    fun provideJournalStore(runDao: RunDao): JournalStore = RoomJournalStore(runDao)

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideSecretStore(@ApplicationContext context: Context): SecretStore = SecretStore(context)

    @Provides
    @Singleton
    fun provideLibraryRepository(
        sourceFolderDao: SourceFolderDao,
        destinationDao: DestinationDao,
        titleOverrideDao: TitleOverrideDao,
        @ApplicationContext context: Context,
    ): LibraryRepository = LibraryRepository(sourceFolderDao, destinationDao, titleOverrideDao, context)

    @Provides
    @Singleton
    fun provideRunDraftRepository(draftDao: ExecutionDraftDao): RunDraftRepository =
        RunDraftRepository(draftDao)

    @Provides
    @Singleton
    fun provideMetadataService(
        metadataCacheDao: MetadataCacheDao,
        settingsRepository: SettingsRepository,
    ): MetadataService = MetadataService(
        cacheDao = metadataCacheDao,
        settingsRepository = settingsRepository,
    )
}
