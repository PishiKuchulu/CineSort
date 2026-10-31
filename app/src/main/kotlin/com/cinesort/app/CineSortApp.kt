package com.cinesort.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/** Exposes the Hilt worker factory to the Application (field injection is
 *  not available on Application itself, so an EntryPoint is used). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerFactoryEntryPoint {
    fun workerFactory(): HiltWorkerFactory
}

/**
 * Application entry point. Timber's debug tree is planted only in debug
 * builds. WorkManager is configured manually (the default initializer is
 * removed in the manifest) so workers are built by Hilt.
 */
@HiltAndroidApp
class CineSortApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                EntryPointAccessors.fromApplication(
                    this,
                    WorkerFactoryEntryPoint::class.java,
                ).workerFactory(),
            )
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
