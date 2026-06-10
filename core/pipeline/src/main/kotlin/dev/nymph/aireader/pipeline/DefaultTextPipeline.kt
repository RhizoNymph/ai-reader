package dev.nymph.aireader.pipeline

import dev.nymph.aireader.model.ContentBlock
import dev.nymph.aireader.model.ExtractedDocument
import dev.nymph.aireader.model.SpeakableDocument
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.pipeline.stages.LineUnwrapper
import dev.nymph.aireader.pipeline.stages.SentenceSegmenter
import dev.nymph.aireader.pipeline.stages.SpeakableExpander
import dev.nymph.aireader.pipeline.stages.SymbolNormalizer

/**
 * Staged implementation of [TextPipeline]:
 *
 * 1. [LineUnwrapper] removes wrap newlines and dehyphenates, yielding logical paragraphs.
 * 2. [SentenceSegmenter.segment] splits each paragraph on the *display* text, so every
 *    normalized sentence keeps an exact pre-normalization counterpart for the UI.
 * 3. Per sentence, [SpeakableExpander] (URLs/emails/abbreviations) runs before
 *    [SymbolNormalizer] so URL slashes and email at-signs are rewritten, not mangled.
 * 4. Sentences that normalize to nothing speakable are dropped; the rest get terminal
 *    punctuation and pause hints.
 *
 * Pause hints: the last sentence of each logical paragraph gets the block-type pause
 * (Heading 900 ms, Paragraph/Quote 600 ms, ListItem 400 ms); earlier sentences get 0.
 */
class DefaultTextPipeline : TextPipeline {

    override fun process(document: ExtractedDocument): SpeakableDocument {
        val sentences = mutableListOf<SpeakableSentence>()
        document.blocks.forEachIndexed { blockIndex, block ->
            for (paragraph in LineUnwrapper.unwrapBlock(block.text)) {
                appendParagraph(sentences, paragraph, block, blockIndex)
            }
        }
        return SpeakableDocument(document.title, sentences)
    }

    private fun appendParagraph(
        sentences: MutableList<SpeakableSentence>,
        paragraph: String,
        block: ContentBlock,
        blockIndex: Int,
    ) {
        val produced = mutableListOf<Pair<String, String>>() // tts text to display text
        for (displaySentence in SentenceSegmenter.segment(paragraph)) {
            val normalized =
                SymbolNormalizer.normalize(SpeakableExpander.expand(displaySentence))
            if (normalized.none(Char::isLetterOrDigit)) continue
            // Expansions can outgrow the display-side split; re-split the TTS text.
            for (piece in SentenceSegmenter.safetySplit(normalized)) {
                val ttsText = SentenceSegmenter.ensureTerminalPunctuation(piece)
                if (ttsText.isEmpty()) continue
                produced += ttsText to displaySentence
            }
        }
        produced.forEachIndexed { i, (ttsText, displayText) ->
            sentences += SpeakableSentence(
                index = sentences.size,
                text = ttsText,
                displayText = displayText,
                blockIndex = blockIndex,
                pauseAfterMs = if (i == produced.lastIndex) pauseAfterMs(block) else 0,
            )
        }
    }

    private fun pauseAfterMs(block: ContentBlock): Int = when (block) {
        is ContentBlock.Heading -> PAUSE_HEADING_MS
        is ContentBlock.Paragraph -> PAUSE_PARAGRAPH_MS
        is ContentBlock.ListItem -> PAUSE_LIST_ITEM_MS
        is ContentBlock.Quote -> PAUSE_QUOTE_MS
    }

    private companion object {
        const val PAUSE_HEADING_MS = 900
        const val PAUSE_PARAGRAPH_MS = 600
        const val PAUSE_LIST_ITEM_MS = 400
        const val PAUSE_QUOTE_MS = 600
    }
}
