package dev.nymph.aireader.playback.service

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media playback service. Pure Media3 [MediaSessionService] glue:
 * wraps the app-scoped [PlaybackController]'s player in a [MediaSession] and
 * relies on Media3's built-in foreground lifecycle and default media
 * notification.
 *
 * Dependencies arrive through the [dependencies] companion property, which the
 * app must set in `Application.onCreate` before anything can start this
 * service (see [PlaybackDependencies]). If the service is somehow started
 * without them (defensive; should not happen since the Application always
 * runs first), it stops itself instead of crashing.
 */
class AiReaderPlaybackService : MediaSessionService() {

    companion object {
        /** Wired by the app at startup; read by this service and [FullBookRenderWorker]. */
        @Volatile
        var dependencies: PlaybackDependencies? = null
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val deps = dependencies
        if (deps == null) {
            stopSelf()
            return
        }
        mediaSession = MediaSession.Builder(this, deps.controller().player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
