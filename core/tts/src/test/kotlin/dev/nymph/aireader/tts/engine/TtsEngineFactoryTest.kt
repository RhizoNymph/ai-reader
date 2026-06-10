package dev.nymph.aireader.tts.engine

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SynthesizedAudio
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.tts.TtsEngine
import dev.nymph.aireader.tts.models.ModelStore
import dev.nymph.aireader.tts.models.VoiceModelCatalog
import dev.nymph.aireader.tts.models.VoiceModelDescriptor
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TtsEngineFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeEngine : TtsEngine {
        override val availableVoices: List<String> = emptyList()
        override suspend fun synthesize(
            text: String,
            voice: VoiceSpec,
        ): Outcome<SynthesizedAudio, TtsError> =
            Outcome.Err(TtsError.SynthesisFailed(-1, "fake"))

        override fun release() = Unit
    }

    @Test
    fun `create fails with ModelNotInstalled when marker is missing`() {
        val store = ModelStore(tmp.newFolder())
        val factory = TtsEngineFactory(store) { _, _ -> FakeEngine() }
        val descriptor = VoiceModelCatalog.forEngine(TtsEngineId.KOKORO)

        val result = factory.create(descriptor)

        assertIs<Outcome.Err<TtsError>>(result)
        val error = result.error
        assertIs<TtsError.ModelNotInstalled>(error)
        assertEquals(TtsEngineId.KOKORO, error.engineId)
    }

    @Test
    fun `create returns engine built from the installed model dir`() {
        val store = ModelStore(tmp.newFolder())
        val descriptor = VoiceModelCatalog.forEngine(TtsEngineId.PIPER)
        store.markInstalled(descriptor.modelId)

        var receivedDescriptor: VoiceModelDescriptor? = null
        var receivedDir: File? = null
        val fake = FakeEngine()
        val factory = TtsEngineFactory(store) { d, dir ->
            receivedDescriptor = d
            receivedDir = dir
            fake
        }

        val result = factory.create(descriptor)

        assertIs<Outcome.Ok<TtsEngine>>(result)
        assertEquals(fake, result.value)
        assertEquals(descriptor, receivedDescriptor)
        assertEquals(store.modelDir(descriptor.modelId), receivedDir)
    }
}
