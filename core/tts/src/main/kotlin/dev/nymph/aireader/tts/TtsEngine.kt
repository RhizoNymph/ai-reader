package dev.nymph.aireader.tts

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SynthesizedAudio
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec

/**
 * A loaded, ready-to-synthesize TTS engine. Implementations wrap sherpa-onnx
 * models (Kokoro, Piper); this is the contract playback depends on.
 */
interface TtsEngine {
    val availableVoices: List<String>

    /** Synthesize one normalized sentence. Blocking-ish work; call from a worker dispatcher. */
    suspend fun synthesize(text: String, voice: VoiceSpec): Outcome<SynthesizedAudio, TtsError>

    fun release()
}
