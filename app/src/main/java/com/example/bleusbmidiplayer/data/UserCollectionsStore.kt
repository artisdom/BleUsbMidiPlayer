package com.example.bleusbmidiplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.bleusbmidiplayer.midi.MidiPlaylist
import com.example.bleusbmidiplayer.midi.TrackReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UserCollectionsStore(private val context: Context) {

    val snapshot: Flow<UserCollectionsSnapshot> = context.collectionsStore.data.map { prefs ->
        val favorites = prefs[FAVORITES_KEY]?.let { json.decodeFromString<List<TrackReference>>(it) } ?: emptyList()
        val playlists = prefs[PLAYLISTS_KEY]?.let { json.decodeFromString<List<MidiPlaylist>>(it) } ?: emptyList()
        UserCollectionsSnapshot(
            favorites = favorites,
            playlists = playlists,
        )
    }

    suspend fun updateFavorites(transform: (List<TrackReference>) -> List<TrackReference>) {
        context.collectionsStore.edit { prefs ->
            val current = prefs[FAVORITES_KEY]?.let { json.decodeFromString<List<TrackReference>>(it) } ?: emptyList()
            val updated = transform(current)
            if (updated.isEmpty()) {
                prefs.remove(FAVORITES_KEY)
            } else {
                prefs[FAVORITES_KEY] = json.encodeToString(updated)
            }
        }
    }

    suspend fun updatePlaylists(transform: (List<MidiPlaylist>) -> List<MidiPlaylist>) {
        context.collectionsStore.edit { prefs ->
            val current = prefs[PLAYLISTS_KEY]?.let { json.decodeFromString<List<MidiPlaylist>>(it) } ?: emptyList()
            val updated = transform(current)
            if (updated.isEmpty()) {
                prefs.remove(PLAYLISTS_KEY)
            } else {
                prefs[PLAYLISTS_KEY] = json.encodeToString(updated)
            }
        }
    }

    companion object {
        private val Context.collectionsStore: DataStore<Preferences> by preferencesDataStore("user_collections")
        private val FAVORITES_KEY = stringPreferencesKey("favorites_json")
        private val PLAYLISTS_KEY = stringPreferencesKey("playlists_json")
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

data class UserCollectionsSnapshot(
    val favorites: List<TrackReference> = emptyList(),
    val playlists: List<MidiPlaylist> = emptyList(),
)
