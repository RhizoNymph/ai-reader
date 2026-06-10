package dev.nymph.aireader.data.settings

import dev.nymph.aireader.model.TtsEngineId
import org.junit.Test
import kotlin.test.assertEquals

class EngineIdParsingTest {

    @Test
    fun `valid engine names parse exactly`() {
        assertEquals(TtsEngineId.KOKORO, engineIdFromStored("KOKORO"))
        assertEquals(TtsEngineId.PIPER, engineIdFromStored("PIPER"))
    }

    @Test
    fun `engine names parse case insensitively and trimmed`() {
        assertEquals(TtsEngineId.PIPER, engineIdFromStored("piper"))
        assertEquals(TtsEngineId.KOKORO, engineIdFromStored("  Kokoro "))
    }

    @Test
    fun `missing or invalid engine falls back to default instead of throwing`() {
        assertEquals(TtsEngineId.KOKORO, engineIdFromStored(null))
        assertEquals(TtsEngineId.KOKORO, engineIdFromStored(""))
        assertEquals(TtsEngineId.KOKORO, engineIdFromStored("ESPEAK"))
    }
}

class VoiceIdParsingTest {

    @Test
    fun `stored voice id is preserved`() {
        assertEquals("en_US-amy-medium", voiceIdFromStored("en_US-amy-medium"))
    }

    @Test
    fun `missing or blank voice id falls back to default`() {
        assertEquals(SettingsDefaults.VOICE_ID, voiceIdFromStored(null))
        assertEquals(SettingsDefaults.VOICE_ID, voiceIdFromStored(""))
        assertEquals(SettingsDefaults.VOICE_ID, voiceIdFromStored("   "))
    }
}

class SpeedParsingTest {

    @Test
    fun `valid speed is preserved`() {
        assertEquals(1.5f, speedFromStored(1.5f))
        assertEquals(0.5f, speedFromStored(0.5f))
        assertEquals(3.0f, speedFromStored(3.0f))
    }

    @Test
    fun `missing speed falls back to default`() {
        assertEquals(SettingsDefaults.SPEED, speedFromStored(null))
    }

    @Test
    fun `out of range speed is clamped instead of throwing`() {
        assertEquals(0.5f, speedFromStored(0.05f))
        assertEquals(3.0f, speedFromStored(12f))
    }

    @Test
    fun `non finite speed falls back to default`() {
        assertEquals(SettingsDefaults.SPEED, speedFromStored(Float.NaN))
        assertEquals(SettingsDefaults.SPEED, speedFromStored(Float.POSITIVE_INFINITY))
        assertEquals(SettingsDefaults.SPEED, speedFromStored(Float.NEGATIVE_INFINITY))
    }
}

class LookaheadParsingTest {

    @Test
    fun `valid lookahead is preserved`() {
        assertEquals(8, lookaheadFromStored(8))
        assertEquals(1, lookaheadFromStored(1))
        assertEquals(32, lookaheadFromStored(32))
    }

    @Test
    fun `missing or non positive lookahead falls back to default`() {
        assertEquals(SettingsDefaults.LOOKAHEAD_SENTENCES, lookaheadFromStored(null))
        assertEquals(SettingsDefaults.LOOKAHEAD_SENTENCES, lookaheadFromStored(0))
        assertEquals(SettingsDefaults.LOOKAHEAD_SENTENCES, lookaheadFromStored(-4))
    }
}

class AppSettingsAssemblyTest {

    @Test
    fun `all missing values assemble the default settings`() {
        assertEquals(
            AppSettings(
                defaultEngineId = TtsEngineId.KOKORO,
                defaultVoiceId = "af_heart",
                defaultSpeed = 1.0f,
                synthesisLookaheadSentences = 8,
            ),
            appSettingsFromStored(engineId = null, voiceId = null, speed = null, lookaheadSentences = null),
        )
        assertEquals(AppSettings(), appSettingsFromStored(null, null, null, null))
    }

    @Test
    fun `valid stored values assemble verbatim`() {
        assertEquals(
            AppSettings(
                defaultEngineId = TtsEngineId.PIPER,
                defaultVoiceId = "en_US-amy-medium",
                defaultSpeed = 1.3f,
                synthesisLookaheadSentences = 12,
            ),
            appSettingsFromStored("PIPER", "en_US-amy-medium", 1.3f, 12),
        )
    }

    @Test
    fun `each invalid stored value independently falls back`() {
        val settings = appSettingsFromStored("nope", "  ", Float.NaN, -1)
        assertEquals(TtsEngineId.KOKORO, settings.defaultEngineId)
        assertEquals("af_heart", settings.defaultVoiceId)
        assertEquals(1.0f, settings.defaultSpeed)
        assertEquals(8, settings.synthesisLookaheadSentences)
    }
}
