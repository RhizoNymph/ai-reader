package dev.nymph.aireader.di

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import dev.nymph.aireader.data.db.AiReaderDatabase
import dev.nymph.aireader.data.db.LibraryRepository
import dev.nymph.aireader.data.settings.SettingsDefaults
import dev.nymph.aireader.data.settings.SettingsRepository
import dev.nymph.aireader.extraction.pdf.PdfExtractor
import dev.nymph.aireader.extraction.web.WebPageExtractor
import dev.nymph.aireader.importing.ImportCoordinator
import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.model.ImportError
import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.pipeline.DefaultTextPipeline
import dev.nymph.aireader.pipeline.TextPipeline
import dev.nymph.aireader.playback.cache.ChunkCache
import dev.nymph.aireader.playback.service.AiReaderPlaybackService
import dev.nymph.aireader.playback.service.PlaybackController
import dev.nymph.aireader.playback.service.PlaybackDependencies
import dev.nymph.aireader.playback.synth.SynthesisConfig
import dev.nymph.aireader.tts.TtsEngine
import dev.nymph.aireader.tts.engine.TtsEngineFactory
import dev.nymph.aireader.tts.models.ModelDownloader
import dev.nymph.aireader.tts.models.ModelRepository
import dev.nymph.aireader.tts.models.ModelStore
import dev.nymph.aireader.tts.models.VoiceModelCatalog
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** The playback session the app-scoped [PlaybackController] currently has loaded. */
data class ActivePlayback(val docId: Long, val voice: VoiceSpec)

/**
 * Manual DI: app-scoped lazy singletons for every subsystem, plus the
 * [PlaybackDependencies] implementation that the playback service and the
 * full-book render worker resolve through
 * [AiReaderPlaybackService.dependencies] (assigned in AiReaderApp.onCreate).
 */
class AppContainer(private val appContext: Context) {

    /** Outlives any screen; used for imports and persistence that must survive navigation. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Main-thread scope handed to the [PlaybackController] (ExoPlayer's application thread). */
    private val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** One client so the web extractor and the model downloader share pools. */
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    private val database: AiReaderDatabase by lazy { AiReaderDatabase.build(appContext) }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(database.documentDao(), database.sentenceDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext.settingsDataStore)
    }

    val modelStore: ModelStore by lazy { ModelStore(File(appContext.filesDir, "models")) }

    val modelRepository: ModelRepository by lazy {
        ModelRepository(modelStore, ModelDownloader(modelStore, httpClient))
    }

    val modelInstalls: ModelInstallCoordinator by lazy {
        ModelInstallCoordinator(modelRepository, appScope)
    }

    val ttsEngineFactory: TtsEngineFactory by lazy { TtsEngineFactory(modelStore) }

    val textPipeline: TextPipeline by lazy { DefaultTextPipeline() }

    val webPageExtractor: WebPageExtractor by lazy {
        WebPageExtractor(
            httpClient.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build(),
        )
    }

    val pdfExtractor: PdfExtractor by lazy { PdfExtractor(::openPdfStream) }

    /**
     * Chunk cache lives in filesDir (not cacheDir) so the OS can never silently
     * purge synthesized audio mid-listen; eviction is the app's own policy
     * (per-doc delete + stale-voice eviction on load).
     */
    val chunkCache: ChunkCache by lazy { ChunkCache(File(appContext.filesDir, "chunks")) }

    val importCoordinator: ImportCoordinator by lazy {
        ImportCoordinator(webPageExtractor, pdfExtractor, textPipeline, libraryRepository)
    }

    /** Runs the full-book render and audiobook export jobs. */
    val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    /**
     * Synthesis lookahead applied when the controller is first constructed
     * (usually on first playback, well after [start] has populated this from
     * settings). Later changes apply from the next process start.
     */
    @Volatile
    private var lookaheadSentences: Int = SettingsDefaults.LOOKAHEAD_SENTENCES

    val playbackController: PlaybackController by lazy {
        PlaybackController(
            context = appContext,
            cache = chunkCache,
            engineFor = ::engineFor,
            mainScope = mainScope,
            synthesisConfig = SynthesisConfig(lookaheadSentences = lookaheadSentences),
        )
    }

    /** What the controller currently has loaded; lets the player screen re-attach. */
    val activePlayback = MutableStateFlow<ActivePlayback?>(null)

    val playbackDependencies: PlaybackDependencies = object : PlaybackDependencies {
        override fun controller(): PlaybackController = playbackController

        override fun chunkCache(): ChunkCache = this@AppContainer.chunkCache

        override fun engineFor(voice: VoiceSpec): Outcome<TtsEngine, TtsError> =
            this@AppContainer.engineFor(voice)

        override suspend fun sentencesFor(docId: Long): List<SpeakableSentence> =
            libraryRepository.getSpeakable(docId)?.sentences ?: emptyList()
    }

    /** Launch app-lifetime collectors. Called once from AiReaderApp.onCreate. */
    fun start() {
        appScope.launch {
            settingsRepository.settings.collect { lookaheadSentences = it.synthesisLookaheadSentences }
        }
    }

    /**
     * Bring the media session service up so playback keeps running (and shows
     * its notification) after the UI goes away. Idempotent; called from a
     * foreground activity context whenever playback starts.
     */
    fun startPlaybackService() {
        appContext.startService(Intent(appContext, AiReaderPlaybackService::class.java))
    }

    private fun engineFor(voice: VoiceSpec): Outcome<TtsEngine, TtsError> =
        ttsEngineFactory.create(VoiceModelCatalog.forEngine(voice.engineId))

    /**
     * Resolves a persisted SAF content URI to a stream for [PdfExtractor].
     * Every expected failure is mapped to [ImportError.Unreadable].
     */
    private fun openPdfStream(uriString: String): Outcome<InputStream, ImportError> {
        val source = DocumentSource.Pdf(uriString)
        return try {
            when (val stream = appContext.contentResolver.openInputStream(Uri.parse(uriString))) {
                null -> Outcome.Err(
                    ImportError.Unreadable(source, "Provider returned no data for this document"),
                )
                else -> Outcome.Ok(stream)
            }
        } catch (e: FileNotFoundException) {
            Outcome.Err(ImportError.Unreadable(source, "Document not found: ${e.message}"))
        } catch (e: SecurityException) {
            Outcome.Err(ImportError.Unreadable(source, "Read permission for this document was lost"))
        } catch (e: IllegalArgumentException) {
            Outcome.Err(ImportError.Unreadable(source, "Malformed document URI"))
        }
    }
}
