package com.cinesort.core.data.di

import android.content.Context
import androidx.room.Room
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
import javax.inject.Singleton

/** Hilt wiring for the data layer (spec sections 4, 12). */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CineSortDatabase =
        Room.databaseBuilder(context, CineSortDatabase::class.java, CineSortDatabase.DATABASE_NAME)
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
    ): LibraryRepository = LibraryRepository(sourceFolderDao, destinationDao, titleOverrideDao)

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
