package dev.nymph.aireader.playback.synth

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.playback.audio.WavCodec
import dev.nymph.aireader.tts.TtsEngine

/**
 * One rendered sentence chunk, ready for the cache. [error] is non-null when
 * synthesis failed and [wavBytes] is the silence fallback instead of speech.
 */
class ChunkRender(
    val wavBytes: ByteArray,
    val sampleRate: Int,
    val error: TtsError?,
)

/**
 * Render one sentence into a complete WAV chunk: engine audio followed by the
 * sentence's [SpeakableSentence.pauseAfterMs] of silence (paragraph/heading
 * pauses are baked into the chunk).
 *
 * On synthesis failure the chunk is [fallbackSilenceMs] + pause of silence at
 * [fallbackSampleRate] so the playback queue never stalls on a bad sentence;
 * the error is reported alongside. Never throws (cancellation excepted).
 *
 * Shared by [SynthesisScheduler] and the full-book render worker so both
 * produce byte-identical chunks for the same input.
 */
internal suspend fun renderChunk(
    engine: TtsEngine,
    sentence: SpeakableSentence,
    voice: VoiceSpec,
    fallbackSampleRate: Int,
    fallbackSilenceMs: Int,
): ChunkRender = when (val result = engine.synthesize(sentence.text, voice)) {
    is Outcome.Ok -> {
        val audio = result.value
        val padded = WavCodec.appendSilence(audio.samples, audio.sampleRate, sentence.pauseAfterMs)
        ChunkRender(WavCodec.encode(padded, audio.sampleRate), audio.sampleRate, error = null)
    }
    is Outcome.Err -> {
        val sampleCount = WavCodec.silenceSampleCount(fallbackSampleRate, fallbackSilenceMs + sentence.pauseAfterMs)
        ChunkRender(WavCodec.encode(FloatArray(sampleCount), fallbackSampleRate), fallbackSampleRate, result.error)
    }
}
