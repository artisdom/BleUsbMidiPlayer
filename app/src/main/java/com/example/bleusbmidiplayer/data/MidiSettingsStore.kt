package com.example.bleusbmidiplayer.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MidiSettingsStore(private val context: Context) {
    val selectedFolder: Flow<Uri?> = context.dataStore.data.map { prefs ->
        prefs[FOLDER_URI]?.let(Uri::parse)
    }

    suspend fun updateFolder(uri: Uri?) {
        context.dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(FOLDER_URI)
            } else {
                prefs[FOLDER_URI] = uri.toString()
            }
        }
    }

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("midi_settings")
        private val FOLDER_URI = stringPreferencesKey("folder_uri")
    }
}
