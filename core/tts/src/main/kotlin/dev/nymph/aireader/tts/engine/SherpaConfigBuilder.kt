package dev.nymph.aireader.tts.engine

import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.tts.models.VoiceModelDescriptor
import java.io.File

/**
 * Pure, JVM-testable mirror of the sherpa-onnx v1.13.2 offline-TTS model
 * configs ([com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig] /
 * [com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig]). [SherpaTtsEngine] maps
 * these 1:1 onto the native config classes; keeping the mapping logic here
 * lets it be unit-tested without loading `libsherpa-onnx-jni`.
 */
sealed interface SherpaModelConfig {
    val numThreads: Int

    data class Kokoro(
        val model: String,
        val voices: String,
        val tokens: String,
        val dataDir: String,
        val lexicon: String,
        val dictDir: String,
        override val numThreads: Int,
    ) : SherpaModelConfig

    data class Vits(
        val model: String,
        val tokens: String,
        val dataDir: String,
        val lexicon: String,
        override val numThreads: Int,
    ) : SherpaModelConfig
}

object SherpaConfigBuilder {

    const val DEFAULT_NUM_THREADS = 2

    /**
     * Resolves a descriptor's files-of-interest against the installed model
     * dir into absolute paths. Multiple lexicons are comma-joined, matching
     * sherpa's `--kokoro-lexicon=a.txt,b.txt` convention.
     */
    fun build(
        descriptor: VoiceModelDescriptor,
        modelDir: File,
        numThreads: Int = DEFAULT_NUM_THREADS,
    ): SherpaModelConfig {
        val files = descriptor.filesOfInterest
        fun path(relative: String?): String =
            relative?.let { File(modelDir, it).absolutePath } ?: ""

        val lexicon = files.lexicons.joinToString(",") { path(it) }

        return when (descriptor.engineId) {
            TtsEngineId.KOKORO -> SherpaModelConfig.Kokoro(
                model = path(files.modelOnnx),
                voices = path(
                    requireNotNull(files.voicesBin) { "kokoro descriptor needs voices.bin" },
                ),
                tokens = path(files.tokens),
                dataDir = path(files.espeakDataDir),
                lexicon = lexicon,
                dictDir = path(files.dictDir),
                numThreads = numThreads,
            )
            TtsEngineId.PIPER -> SherpaModelConfig.Vits(
                model = path(files.modelOnnx),
                tokens = path(files.tokens),
                dataDir = path(files.espeakDataDir),
                lexicon = lexicon,
                numThreads = numThreads,
            )
        }
    }
}
