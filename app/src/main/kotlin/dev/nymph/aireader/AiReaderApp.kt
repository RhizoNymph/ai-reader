package dev.nymph.aireader

import android.app.Application

class AiReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // DI container wiring lands with the feature modules.
    }
}
