package dev.nymph.aireader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dev.nymph.aireader.di.AppContainer
import dev.nymph.aireader.playback.service.AiReaderPlaybackService

class AiReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // PdfBox-Android requires one-time init before the first extraction.
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(applicationContext)
        // The playback service and the full-book render worker are instantiated
        // by Android and resolve their dependencies through this holder; it must
        // be assigned before anything can start either of them.
        AiReaderPlaybackService.dependencies = container.playbackDependencies
        container.start()
    }
}
