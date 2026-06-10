package dev.nymph.aireader.model

/** Mono PCM float audio as produced by a TTS engine. */
class SynthesizedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    val durationMs: Long
        get() = samples.size * 1000L / sampleRate
}

/** A concrete voice on a concrete engine, plus synthesis-time parameters. */
data class VoiceSpec(
    val engineId: TtsEngineId,
    val voiceId: String,
    val speed: Float = 1.0f,
) {
    init {
        require(speed in 0.5f..3.0f) { "speed out of range: $speed" }
    }
}

enum class TtsEngineId { KOKORO, PIPER }
