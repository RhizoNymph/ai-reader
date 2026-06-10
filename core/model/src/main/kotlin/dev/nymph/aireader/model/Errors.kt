package dev.nymph.aireader.model

/** Structured failure for the import path (extraction + pipeline). */
sealed interface ImportError {
    val message: String

    data class Unreadable(val source: DocumentSource, override val message: String) : ImportError
    data class Network(val url: String, override val message: String) : ImportError
    data class NoMainContent(val source: DocumentSource, override val message: String) : ImportError
    data class Encrypted(val source: DocumentSource, override val message: String) : ImportError
}

/** Structured failure for TTS model download/load/synthesis. */
sealed interface TtsError {
    val message: String

    data class ModelNotInstalled(val engineId: TtsEngineId, override val message: String) : TtsError
    data class DownloadFailed(val engineId: TtsEngineId, override val message: String) : TtsError
    data class ChecksumMismatch(val engineId: TtsEngineId, override val message: String) : TtsError
    data class SynthesisFailed(val sentenceIndex: Int, override val message: String) : TtsError
}

/** Minimal typed Result; avoids exceptions crossing module boundaries. */
sealed interface Outcome<out T, out E> {
    data class Ok<T>(val value: T) : Outcome<T, Nothing>
    data class Err<E>(val error: E) : Outcome<Nothing, E>
}

inline fun <T, E, R> Outcome<T, E>.map(f: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Err -> this
}

inline fun <T, E> Outcome<T, E>.getOrElse(f: (E) -> T): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> f(error)
}
