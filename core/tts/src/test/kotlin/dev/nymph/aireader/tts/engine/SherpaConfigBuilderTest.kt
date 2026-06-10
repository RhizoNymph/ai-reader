package dev.nymph.aireader.tts.engine

import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.tts.models.VoiceModelCatalog
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SherpaConfigBuilderTest {

    private val modelDir = File("/data/user/0/app/files/tts/kokoro-int8-multi-lang-v1_0")

    @Test
    fun `kokoro descriptor builds a kokoro config with absolute paths`() {
        val descriptor = VoiceModelCatalog.forEngine(TtsEngineId.KOKORO)
        val config = SherpaConfigBuilder.build(descriptor, modelDir, numThreads = 2)

        assertIs<SherpaModelConfig.Kokoro>(config)
        assertEquals(File(modelDir, "model.int8.onnx").absolutePath, config.model)
        assertEquals(File(modelDir, "voices.bin").absolutePath, config.voices)
        assertEquals(File(modelDir, "tokens.txt").absolutePath, config.tokens)
        assertEquals(File(modelDir, "espeak-ng-data").absolutePath, config.dataDir)
        assertEquals(File(modelDir, "dict").absolutePath, config.dictDir)
        assertEquals(2, config.numThreads)
        // comma-joined lexicons, us-en first (sherpa accepts a comma-separated list)
        val lexicons = config.lexicon.split(",")
        assertEquals(
            descriptor.filesOfInterest.lexicons.map { File(modelDir, it).absolutePath },
            lexicons,
        )
        assertTrue(lexicons.first().endsWith("lexicon-us-en.txt"))
    }

    @Test
    fun `piper descriptor builds a vits config`() {
        val dir = File("/data/user/0/app/files/tts/vits-piper-en_US-libritts_r-medium")
        val descriptor = VoiceModelCatalog.forEngine(TtsEngineId.PIPER)
        val config = SherpaConfigBuilder.build(descriptor, dir, numThreads = 4)

        assertIs<SherpaModelConfig.Vits>(config)
        assertEquals(File(dir, "en_US-libritts_r-medium.onnx").absolutePath, config.model)
        assertEquals(File(dir, "tokens.txt").absolutePath, config.tokens)
        assertEquals(File(dir, "espeak-ng-data").absolutePath, config.dataDir)
        assertEquals("", config.lexicon)
        assertEquals(4, config.numThreads)
    }

    @Test
    fun `default thread count is conservative`() {
        val config = SherpaConfigBuilder.build(
            VoiceModelCatalog.forEngine(TtsEngineId.PIPER),
            modelDir,
        )
        assertEquals(2, config.numThreads)
    }
}
