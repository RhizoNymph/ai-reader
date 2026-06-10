package dev.nymph.aireader.playback.synth

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.SynthesizedAudio
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.playback.cache.CacheError
import dev.nymph.aireader.playback.cache.ChunkStore
import dev.nymph.aireader.tts.TtsEngine
import java.io.File
import kotlinx.coroutines.channels.Channel

val testVoice = VoiceSpec(TtsEngineId.KOKORO, "af_heart", 1.0f)

fun sentences(count: Int, pauseMs: Int = 0): List<SpeakableSentence> =
    List(count) { i ->
        SpeakableSentence(index = i, text = "s$i", displayText = "S$i.", blockIndex = 0, pauseAfterMs = pauseMs)
    }

/**
 * Deterministic engine: every sentence renders [samplesPerSentence] samples at
 * [sampleRate]. Set [gate] to make each call suspend until a permit is sent;
 * texts in [failTexts] return [TtsError.SynthesisFailed].
 */
class FakeTtsEngine(
    val sampleRate: Int = 24_000,
    val samplesPerSentence: Int = 2_400,
) : TtsEngine {
    override val availableVoices: List<String> = listOf("af_heart")

    val calls = mutableListOf<String>()
    var failTexts: Set<String> = emptySet()
    var gate: Channel<Unit>? = null
    var released = false
        private set

    override suspend fun synthesize(text: String, voice: VoiceSpec): Outcome<SynthesizedAudio, TtsError> {
        calls += text
        gate?.receive()
        if (text in failTexts) {
            return Outcome.Err(TtsError.SynthesisFailed(sentenceIndex = -1, message = "fake failure for $text"))
        }
        return Outcome.Ok(SynthesizedAudio(FloatArray(samplesPerSentence) { 0.1f }, sampleRate))
    }

    override fun release() {
        released = true
    }
}

/** In-memory [ChunkStore]; nothing touches the filesystem. */
class InMemoryChunkStore : ChunkStore {
    val written = LinkedHashMap<Int, ByteArray>()
    var failWrites = false

    override fun has(index: Int): Boolean = written.containsKey(index)

    override fun fileFor(index: Int): File = File("/inmemory/$index.wav")

    override fun write(index: Int, wavBytes: ByteArray): Outcome<File, CacheError> {
        if (failWrites) return Outcome.Err(CacheError.Io(fileFor(index).path, "fake write failure"))
        written[index] = wavBytes
        return Outcome.Ok(fileFor(index))
    }

    override fun totalBytes(): Long = written.values.sumOf { it.size.toLong() }
}
