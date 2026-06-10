package dev.nymph.aireader.extraction

import dev.nymph.aireader.model.ExtractedDocument
import dev.nymph.aireader.model.ImportError
import dev.nymph.aireader.model.Outcome

/**
 * Source-specific main-content extractor (one per [dev.nymph.aireader.model.DocumentSource] kind).
 * Implementations land in the extraction feature work; this is the module contract.
 */
interface Extractor<in S> {
    suspend fun extract(source: S): Outcome<ExtractedDocument, ImportError>
}
