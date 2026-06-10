package dev.nymph.aireader.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nymph.aireader.model.TtsEngineId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * App settings over a Preferences DataStore. Parsing of stored values goes through the
 * pure helpers in AppSettings.kt; setters sanitize through the same helpers so invalid
 * values are never persisted.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    private object Keys {
        val DEFAULT_ENGINE_ID = stringPreferencesKey("defaultEngineId")
        val DEFAULT_VOICE_ID = stringPreferencesKey("defaultVoiceId")
        val DEFAULT_SPEED = floatPreferencesKey("defaultSpeed")
        val SYNTHESIS_LOOKAHEAD_SENTENCES = intPreferencesKey("synthesisLookaheadSentences")
    }

    /** Current settings; an unreadable preferences file yields the defaults. */
    val settings: Flow<AppSettings> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { preferences ->
            appSettingsFromStored(
                engineId = preferences[Keys.DEFAULT_ENGINE_ID],
                voiceId = preferences[Keys.DEFAULT_VOICE_ID],
                speed = preferences[Keys.DEFAULT_SPEED],
                lookaheadSentences = preferences[Keys.SYNTHESIS_LOOKAHEAD_SENTENCES],
            )
        }

    suspend fun setDefaultEngineId(engineId: TtsEngineId) {
        dataStore.edit { preferences -> preferences[Keys.DEFAULT_ENGINE_ID] = engineId.name }
    }

    suspend fun setDefaultVoiceId(voiceId: String) {
        dataStore.edit { preferences ->
            preferences[Keys.DEFAULT_VOICE_ID] = voiceIdFromStored(voiceId)
        }
    }

    suspend fun setDefaultSpeed(speed: Float) {
        dataStore.edit { preferences -> preferences[Keys.DEFAULT_SPEED] = speedFromStored(speed) }
    }

    suspend fun setSynthesisLookaheadSentences(count: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.SYNTHESIS_LOOKAHEAD_SENTENCES] = lookaheadFromStored(count)
        }
    }
}
