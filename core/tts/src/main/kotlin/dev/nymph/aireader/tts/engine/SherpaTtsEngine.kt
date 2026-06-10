package dev.nymph.aireader.tts.engine

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SynthesizedAudio
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.tts.TtsEngine
import dev.nymph.aireader.tts.models.VoiceModelDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * [TtsEngine] backed by sherpa-onnx v1.13.2 [OfflineTts].
 *
 * NATIVE: instantiating this class loads `libsherpa-onnx-jni` (via
 * [OfflineTts]'s companion `System.loadLibrary`) and allocates the native
 * model — Android only, never on the JVM. Construct only through
 * [TtsEngineFactory], which guarantees the model's `.complete` marker exists.
 *
 * Thread-safety: the native [OfflineTts] is not thread-safe; all generate
 * calls are serialized behind a [Mutex]. [release] must not be called while a
 * [synthesize] call is in flight (playback owns that ordering).
 */
class SherpaTtsEngine(
    private val descriptor: VoiceModelDescriptor,
    modelDir: File,
    numThreads: Int = SherpaConfigBuilder.DEFAULT_NUM_THREADS,
) : TtsEngine {

    private val mutex = Mutex()

    private var tts: OfflineTts? = OfflineTts(
        config = SherpaConfigBuilder.build(descriptor, modelDir, numThreads).toOfflineTtsConfig(),
    )

    override val availableVoices: List<String> = descriptor.voices.map { it.id }

    override suspend fun synthesize(
        text: String,
        voice: VoiceSpec,
    ): Outcome<SynthesizedAudio, TtsError> {
        if (voice.engineId != descriptor.engineId) {
            return err("voice ${voice.voiceId} targets ${voice.engineId}, engine is ${descriptor.engineId}")
        }
        val sid = descriptor.speakerIdFor(voice.voiceId)
            ?: return err("unknown voice ${voice.voiceId} for ${descriptor.modelId}")
        if (text.isBlank()) return err("blank text")

        return mutex.withLock {
            val engine = tts ?: return@withLock err("engine already released")
            val audio = engine.generate(text = text, sid = sid, speed = voice.speed)
            if (audio.samples.isEmpty()) {
                err("model produced no audio for: ${text.take(80)}")
            } else {
                Outcome.Ok(SynthesizedAudio(samples = audio.samples, sampleRate = audio.sampleRate))
            }
        }
    }

    override fun release() {
        tts?.free()
        tts = null
    }

    private fun err(message: String): Outcome.Err<TtsError> =
        Outcome.Err(TtsError.SynthesisFailed(sentenceIndex = -1, message = message))
}

/** Maps the pure config holder onto sherpa-onnx v1.13.2 native config classes. */
private fun SherpaModelConfig.toOfflineTtsConfig(): OfflineTtsConfig {
    val modelConfig = when (this) {
        is SherpaModelConfig.Kokoro -> OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = model,
                voices = voices,
                tokens = tokens,
                dataDir = dataDir,
                lexicon = lexicon,
                dictDir = dictDir,
            ),
            numThreads = numThreads,
        )
        is SherpaModelConfig.Vits -> OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = model,
                tokens = tokens,
                dataDir = dataDir,
                lexicon = lexicon,
            ),
            numThreads = numThreads,
        )
    }
    return OfflineTtsConfig(model = modelConfig)
}
