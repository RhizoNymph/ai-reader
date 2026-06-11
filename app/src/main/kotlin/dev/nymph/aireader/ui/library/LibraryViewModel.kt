package dev.nymph.aireader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.aireader.data.db.LibraryItem
import dev.nymph.aireader.data.db.LibraryRepository
import dev.nymph.aireader.importing.ImportCoordinator
import dev.nymph.aireader.importing.normalizeWebUrl
import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.playback.cache.ChunkCache
import dev.nymph.aireader.tts.models.ModelRepository
import dev.nymph.aireader.tts.models.ModelState
import dev.nymph.aireader.tts.models.VoiceModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val library: LibraryRepository,
    private val importCoordinator: ImportCoordinator,
    private val chunkCache: ChunkCache,
    modelRepository: ModelRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    val items: StateFlow<List<LibraryItem>> = library.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** In-memory user-facing messages for FAILED rows (docId -> message). */
    val failureMessages: StateFlow<Map<Long, String>> = importCoordinator.failureMessages

    /** First-run banner driver: false until at least one voice model is installed. */
    val anyModelInstalled: StateFlow<Boolean> =
        combine(VoiceModelCatalog.all.map { modelRepository.observe(it.engineId) }) { states ->
            states.any { it is ModelState.Installed }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            VoiceModelCatalog.all.any { modelRepository.observe(it.engineId).value is ModelState.Installed },
        )

    /** [uriString] must be a SAF content URI with a persisted read permission. */
    fun importPdf(uriString: String) {
        // App scope: imports keep running if the user navigates away.
        appScope.launch { importCoordinator.import(DocumentSource.Pdf(uriString)) }
    }

    fun importWebPage(rawUrl: String) {
        val url = normalizeWebUrl(rawUrl) ?: return
        appScope.launch { importCoordinator.import(DocumentSource.WebPage(url)) }
    }

    /** Failed import: drop the failed row and run the import again from its source. */
    fun retry(item: LibraryItem) {
        appScope.launch {
            library.delete(item.id)
            chunkCache.deleteDoc(item.id)
            importCoordinator.clearFailure(item.id)
            importCoordinator.import(item.source)
        }
    }

    fun delete(item: LibraryItem) {
        appScope.launch {
            library.delete(item.id)
            chunkCache.deleteDoc(item.id)
            importCoordinator.clearFailure(item.id)
        }
    }
}
