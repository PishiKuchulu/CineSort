package com.cinesort.core.data.repository

import com.cinesort.core.data.settings.AppSettings

/**
 * The slice of settings access that pure consumers (e.g. MetadataService)
 * depend on. The DataStore-backed [SettingsRepository] implements it in
 * production; tests substitute an in-memory fake — the repository itself
 * needs an Android Context and cannot be built on a JVM unit test.
 */
interface SettingsAccess {
    suspend fun current(): AppSettings

    suspend fun update(block: (AppSettings) -> AppSettings)
}
