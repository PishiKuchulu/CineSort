package com.cinesort.core.data.repository

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cinesort_secrets",
)

/**
 * Encrypted API-key storage (spec sections 12, 14): Tink AEAD with an Android
 * Keystore-backed master key. Ciphertexts live in a dedicated preferences
 * DataStore. Exposed as flows of plain keys (empty string when unset); key
 * values are never logged.
 */
class SecretStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dataStore: DataStore<Preferences> = appContext.secretsDataStore

    val tmdbKey: Flow<String> = keyFlow(TMDB_KEY)

    val omdbKey: Flow<String> = keyFlow(OMDB_KEY)

    suspend fun setTmdbKey(value: String) = writeKey(TMDB_KEY, value)

    suspend fun setOmdbKey(value: String) = writeKey(OMDB_KEY, value)

    // ------------------------------------------------------------ internals

    @Volatile
    private var cachedAead: Aead? = null

    private fun aead(): Aead? {
        cachedAead?.let { return it }
        return synchronized(this) {
            cachedAead ?: try {
                AeadConfig.register()
                val handle = AndroidKeysetManager.Builder()
                    .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREF_FILE)
                    .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                    .keysetHandle
                handle.getPrimitive(Aead::class.java).also { cachedAead = it }
            } catch (e: GeneralSecurityException) {
                Timber.w(e, "secret store keyset unavailable")
                null
            } catch (e: Exception) {
                Timber.w(e, "secret store keyset unavailable")
                null
            }
        }
    }

    private fun keyFlow(prefKey: Preferences.Key<String>): Flow<String> =
        dataStore.data
            .catch { error ->
                if (error is IOException) {
                    Timber.w(error, "secret read failed")
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { prefs -> decrypt(prefs[prefKey]) }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrBlank()) return ""
        val aead = aead() ?: return ""
        return try {
            val cipherBytes = Base64.decode(encoded, Base64.NO_WRAP)
            val plainBytes = aead.decrypt(cipherBytes, EMPTY_AAD)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.w(e, "secret decryption failed")
            ""
        }
    }

    private suspend fun writeKey(prefKey: Preferences.Key<String>, value: String) {
        if (value.isBlank()) {
            dataStore.edit { prefs -> prefs.remove(prefKey) }
            return
        }
        val aead = aead() ?: throw IllegalStateException("secret store unavailable")
        val cipherBytes = aead.encrypt(value.trim().toByteArray(Charsets.UTF_8), EMPTY_AAD)
        val encoded = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        dataStore.edit { prefs -> prefs[prefKey] = encoded }
    }

    private companion object {
        const val KEYSET_NAME = "cinesort_keyset"
        const val KEYSET_PREF_FILE = "cinesort_keyset"
        const val MASTER_KEY_URI = "android-keystore://cinesort_master"
        const val KEY_TEMPLATE = "AES256_GCM"
        val EMPTY_AAD = ByteArray(0)
        val TMDB_KEY = stringPreferencesKey("tmdb_key_enc")
        val OMDB_KEY = stringPreferencesKey("omdb_key_enc")
    }
}
