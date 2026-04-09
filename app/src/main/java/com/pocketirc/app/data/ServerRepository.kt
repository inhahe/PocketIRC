package com.pocketirc.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketirc.app.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "pocketirc")

/**
 * Persists the user's [ServerConfig] list as a JSON blob in DataStore.
 *
 * SECURITY TODO: SASL passwords currently live in plaintext DataStore. Move to
 * EncryptedSharedPreferences (androidx.security:security-crypto) before any release.
 */
class ServerRepository(private val context: Context) {

    private val key = stringPreferencesKey("server_configs_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val listSerializer = ListSerializer(ServerConfig.serializer())

    val servers: Flow<List<ServerConfig>> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun save(list: List<ServerConfig>) {
        context.dataStore.edit { it[key] = json.encodeToString(listSerializer, list) }
    }

    suspend fun upsert(config: ServerConfig, current: List<ServerConfig>) {
        val next = current.filterNot { it.id == config.id } + config
        save(next)
    }

    suspend fun remove(id: String, current: List<ServerConfig>) {
        save(current.filterNot { it.id == id })
    }
}
