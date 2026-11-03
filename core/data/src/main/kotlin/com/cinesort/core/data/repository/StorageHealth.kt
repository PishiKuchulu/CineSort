package com.cinesort.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which local stores failed to read and had to fall back to empty
 * (section-4 audit). Both flags are one-time notices: they stay set
 * until the user dismisses them in Settings.
 */
data class StorageHealth(
    val settingsReset: Boolean = false,
    val secretsUnreadable: Boolean = false,
) {
    val noticePending: Boolean get() = settingsReset || secretsUnreadable
}

/**
 * Persisted recovery signal for the settings/secrets stores.
 *
 * Both stores used to fail silently (section-4 audit): a corrupt
 * settings DataStore read back as "all defaults" and an undecryptable
 * API key (e.g. the Android Keystore entry was invalidated by a lock
 * change) read back as "no key entered" — permanent loss was
 * indistinguishable from a fresh install, for the user and for support.
 *
 * The flags live in a tiny SharedPreferences file of their own, NOT in
 * either DataStore: the flag must survive exactly the corruption it
 * reports. Every repository owns an instance over the same file; a
 * preference-change listener keeps all instances' [state] in sync.
 */
class StorageHealthStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<StorageHealth> = _state.asStateFlow()

    // Kept as a field: the listener must stay reachable for the store's
    // lifetime (the repositories holding stores are singletons).
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun markSettingsReset() {
        if (!_state.value.settingsReset) {
            prefs.edit().putBoolean(KEY_SETTINGS_RESET, true).apply()
        }
    }

    fun markSecretsUnreadable() {
        if (!_state.value.secretsUnreadable) {
            prefs.edit().putBoolean(KEY_SECRETS_UNREADABLE, true).apply()
        }
    }

    /** The user has seen the notice; clear both flags until the next failure. */
    fun dismiss() {
        prefs.edit()
            .putBoolean(KEY_SETTINGS_RESET, false)
            .putBoolean(KEY_SECRETS_UNREADABLE, false)
            .apply()
    }

    private fun read(): StorageHealth = StorageHealth(
        settingsReset = prefs.getBoolean(KEY_SETTINGS_RESET, false),
        secretsUnreadable = prefs.getBoolean(KEY_SECRETS_UNREADABLE, false),
    )

    private companion object {
        const val FILE_NAME = "cinesort_storage_health"
        const val KEY_SETTINGS_RESET = "settings_reset_pending"
        const val KEY_SECRETS_UNREADABLE = "secrets_unreadable_pending"
    }
}
