package dev.nymph.aireader.data.settings

import dev.nymph.aireader.model.TtsEngineId

/** App-wide defaults applied to new documents and to the synthesis pipeline. */
data class AppSettings(
    val defaultEngineId: TtsEngineId = SettingsDefaults.ENGINE_ID,
    val defaultVoiceId: String = SettingsDefaults.VOICE_ID,
    val defaultSpeed: Float = SettingsDefaults.SPEED,
    /** How many sentences past the playhead the playback service synthesizes ahead. */
    val synthesisLookaheadSentences: Int = SettingsDefaults.LOOKAHEAD_SENTENCES,
)

object SettingsDefaults {
    val ENGINE_ID: TtsEngineId = TtsEngineId.KOKORO
    const val VOICE_ID: String = "af_heart"
    const val SPEED: Float = 1.0f
    const val LOOKAHEAD_SENTENCES: Int = 8
}

/** Mirrors VoiceSpec's accepted speed range. */
private val SPEED_RANGE = 0.5f..3.0f

/*
 * Pure parsing helpers from raw stored preference values. All of them fall back to
 * the default on missing/invalid input and never throw, so a corrupt or downgraded
 * preferences file can never crash the app.
 */

fun engineIdFromStored(value: String?): TtsEngineId {
    val normalized = value?.trim()?.uppercase() ?: return SettingsDefaults.ENGINE_ID
    return TtsEngineId.entries.firstOrNull { it.name == normalized } ?: SettingsDefaults.ENGINE_ID
}

fun voiceIdFromStored(value: String?): String =
    if (value.isNullOrBlank()) SettingsDefaults.VOICE_ID else value

fun speedFromStored(value: Float?): Float =
    if (value == null || !value.isFinite()) SettingsDefaults.SPEED
    else value.coerceIn(SPEED_RANGE)

fun lookaheadFromStored(value: Int?): Int =
    if (value == null || value < 1) SettingsDefaults.LOOKAHEAD_SENTENCES else value

fun appSettingsFromStored(
    engineId: String?,
    voiceId: String?,
    speed: Float?,
    lookaheadSentences: Int?,
): AppSettings = AppSettings(
    defaultEngineId = engineIdFromStored(engineId),
    defaultVoiceId = voiceIdFromStored(voiceId),
    defaultSpeed = speedFromStored(speed),
    synthesisLookaheadSentences = lookaheadFromStored(lookaheadSentences),
)
