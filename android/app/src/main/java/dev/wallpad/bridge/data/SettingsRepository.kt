package dev.wallpad.bridge.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("bridge_settings")

class SettingsRepository(private val context: Context) {
    private val darkModeKey = booleanPreferencesKey("dark_mode")
    val darkMode = context.settingsStore.data.map { it[darkModeKey] ?: false }
    suspend fun setDarkMode(enabled: Boolean) {
        context.settingsStore.edit { it[darkModeKey] = enabled }
    }
}

