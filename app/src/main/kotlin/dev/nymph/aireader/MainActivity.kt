package dev.nymph.aireader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import dev.nymph.aireader.di.AppContainer
import dev.nymph.aireader.importing.parseShareIntent
import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.ui.AiReaderNavHost
import dev.nymph.aireader.ui.theme.AiReaderTheme
import kotlinx.coroutines.launch

/**
 * Single activity: hosts the nav graph and routes incoming share intents
 * (URLs and PDFs) into the import flow. launchMode is singleTask, so shares
 * while the app is alive arrive via [onNewIntent].
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as AiReaderApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        setContent {
            AiReaderTheme {
                AiReaderNavHost(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val source = parseShareIntent(
            action = intent.action,
            mimeType = intent.type,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            streamUri = streamUri?.toString(),
        ) ?: return

        if (source is DocumentSource.Pdf && streamUri != null) {
            takePersistableReadPermission(streamUri)
        }
        // App-scoped so the import survives this activity; the library list
        // shows the row (importing -> ready/failed) immediately.
        container.appScope.launch { container.importCoordinator.import(source) }
        // Consume the share so a configuration change doesn't re-import.
        intent.action = null
    }

    /**
     * Share intents usually carry only a transient read grant; a persistable
     * one is taken when offered so later re-reads of the PDF keep working.
     */
    private fun takePersistableReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not persistable: the import still works now; a later re-extraction
            // of this document will surface ImportError.Unreadable.
        }
    }
}
