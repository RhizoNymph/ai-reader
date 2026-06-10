package dev.nymph.aireader.pipeline

import dev.nymph.aireader.model.ExtractedDocument
import dev.nymph.aireader.model.SpeakableDocument

/**
 * Turns extracted main-content blocks into TTS-ready sentences.
 * Implementation lands in the pipeline feature work; this is the module contract.
 */
interface TextPipeline {
    fun process(document: ExtractedDocument): SpeakableDocument
}
