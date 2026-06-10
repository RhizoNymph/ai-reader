package dev.nymph.aireader.model

/**
 * One synthesis unit. Produced by the text pipeline from an [ExtractedDocument];
 * [text] is fully normalized (no stray newlines, no unspeakable symbols) and is
 * exactly what gets sent to the TTS engine.
 */
data class SpeakableSentence(
    /** Position in the document's sentence list; stable playback/seek key. */
    val index: Int,
    /** Normalized text handed to the TTS engine. */
    val text: String,
    /** Text as shown in the UI (original punctuation/casing preserved). */
    val displayText: String,
    /** Index of the [ContentBlock] this sentence came from. */
    val blockIndex: Int,
    /** Silence inserted after this sentence (paragraph/heading boundaries pause longer). */
    val pauseAfterMs: Int,
)

data class SpeakableDocument(
    val title: String?,
    val sentences: List<SpeakableSentence>,
) {
    init {
        require(sentences.withIndex().all { (i, s) -> s.index == i }) {
            "sentence indices must be contiguous from 0"
        }
    }
}
