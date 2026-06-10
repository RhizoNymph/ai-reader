package dev.nymph.aireader.tts.models

import dev.nymph.aireader.model.TtsEngineId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceModelCatalogTest {

    @Test
    fun `catalog has exactly one descriptor per engine`() {
        assertEquals(2, VoiceModelCatalog.all.size)
        assertEquals(
            TtsEngineId.entries.toSet(),
            VoiceModelCatalog.all.map { it.engineId }.toSet(),
        )
    }

    @Test
    fun `forEngine returns matching descriptor`() {
        assertEquals(TtsEngineId.KOKORO, VoiceModelCatalog.forEngine(TtsEngineId.KOKORO).engineId)
        assertEquals(TtsEngineId.PIPER, VoiceModelCatalog.forEngine(TtsEngineId.PIPER).engineId)
    }

    @Test
    fun `kokoro descriptor matches verified release metadata`() {
        val kokoro = VoiceModelCatalog.forEngine(TtsEngineId.KOKORO)
        assertEquals("kokoro-int8-multi-lang-v1_0", kokoro.modelId)
        assertEquals(24000, kokoro.sampleRate)
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                "kokoro-int8-multi-lang-v1_0.tar.bz2",
            kokoro.downloadUrl,
        )
        assertEquals("model.int8.onnx", kokoro.filesOfInterest.modelOnnx)
        assertEquals("tokens.txt", kokoro.filesOfInterest.tokens)
        assertEquals("voices.bin", kokoro.filesOfInterest.voicesBin)
        assertEquals("espeak-ng-data", kokoro.filesOfInterest.espeakDataDir)
        assertEquals("dict", kokoro.filesOfInterest.dictDir)
        assertTrue(kokoro.filesOfInterest.lexicons.contains("lexicon-us-en.txt"))
    }

    @Test
    fun `kokoro exposes the documented v1_0 english voices with verified speaker ids`() {
        val kokoro = VoiceModelCatalog.forEngine(TtsEngineId.KOKORO)
        // Speaker-id table published in the sherpa-onnx kokoro docs for
        // kokoro-multi-lang-v1_0 (53 speakers, ids 0..52).
        val expected = mapOf(
            "af_heart" to 3,
            "af_sky" to 10,
            "am_adam" to 11,
            "bf_emma" to 21,
        )
        for ((voiceId, sid) in expected) {
            assertEquals(sid, kokoro.speakerIdFor(voiceId), "speaker id for $voiceId")
        }
    }

    @Test
    fun `piper descriptor matches verified release metadata`() {
        val piper = VoiceModelCatalog.forEngine(TtsEngineId.PIPER)
        assertEquals("vits-piper-en_US-libritts_r-medium", piper.modelId)
        assertEquals(22050, piper.sampleRate)
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                "vits-piper-en_US-libritts_r-medium.tar.bz2",
            piper.downloadUrl,
        )
        assertEquals("en_US-libritts_r-medium.onnx", piper.filesOfInterest.modelOnnx)
        assertEquals("tokens.txt", piper.filesOfInterest.tokens)
        assertEquals("espeak-ng-data", piper.filesOfInterest.espeakDataDir)
        assertNull(piper.filesOfInterest.voicesBin)
        assertNull(piper.filesOfInterest.dictDir)
    }

    @Test
    fun `download urls are https tar bz2 archives with positive sizes`() {
        for (descriptor in VoiceModelCatalog.all) {
            assertTrue(descriptor.downloadUrl.startsWith("https://github.com/"))
            assertTrue(descriptor.downloadUrl.endsWith(".tar.bz2"))
            assertTrue(descriptor.archiveSizeBytes > 0)
        }
    }

    @Test
    fun `every descriptor has at least one voice and unique ids`() {
        for (descriptor in VoiceModelCatalog.all) {
            assertTrue(descriptor.voices.isNotEmpty())
            assertEquals(descriptor.voices.size, descriptor.voices.map { it.id }.toSet().size)
            assertEquals(
                descriptor.voices.size,
                descriptor.voices.map { it.speakerId }.toSet().size,
            )
            assertTrue(descriptor.voices.all { it.speakerId >= 0 })
            assertTrue(descriptor.voices.all { it.displayName.isNotBlank() })
        }
    }

    @Test
    fun `files of interest are clean relative paths`() {
        for (descriptor in VoiceModelCatalog.all) {
            for (path in descriptor.filesOfInterest.allPaths()) {
                assertTrue(path.isNotBlank())
                assertTrue(!path.startsWith("/"), "$path must be relative")
                assertTrue(!path.contains(".."), "$path must not traverse")
            }
        }
    }

    @Test
    fun `unknown voice id maps to null speaker id`() {
        assertNull(VoiceModelCatalog.forEngine(TtsEngineId.KOKORO).speakerIdFor("nope"))
        assertNull(VoiceModelCatalog.forEngine(TtsEngineId.PIPER).speakerIdFor("nope"))
    }

    @Test
    fun `model ids are unique and filesystem safe`() {
        val ids = VoiceModelCatalog.all.map { it.modelId }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(id.matches(Regex("[A-Za-z0-9._-]+")), "unsafe model id: $id")
        }
    }
}
