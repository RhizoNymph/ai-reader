package dev.nymph.aireader.ui.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nymph.aireader.data.db.DocumentStatus
import dev.nymph.aireader.data.db.LibraryItem
import dev.nymph.aireader.model.DocumentSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenDocument: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val items by viewModel.items.collectAsState()
    val failureMessages by viewModel.failureMessages.collectAsState()
    val anyModelInstalled by viewModel.anyModelInstalled.collectAsState()

    var showUrlDialog by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<LibraryItem?>(null) }

    val context = LocalContext.current
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // OpenDocument grants are persistable; keep read access for re-extraction.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.importPdf(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Reader") },
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Voice models")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ImportFab(
                onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                onAddWebPage = { showUrlDialog = true },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!anyModelInstalled) {
                NoModelBanner(onOpenModels = onOpenModels)
            }
            if (items.isEmpty()) {
                EmptyLibraryHint()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        LibraryItemCard(
                            item = item,
                            failureMessage = failureMessages[item.id],
                            onClick = {
                                if (item.status == DocumentStatus.READY && item.sentenceCount > 0) {
                                    onOpenDocument(item.id)
                                }
                            },
                            onLongClick = { deleteCandidate = item },
                            onRetry = { viewModel.retry(item) },
                            onDelete = { deleteCandidate = item },
                        )
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        AddWebPageDialog(
            onConfirm = { url ->
                viewModel.importWebPage(url)
                showUrlDialog = false
            },
            onDismiss = { showUrlDialog = false },
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete document?") },
            text = { Text("“${candidate.title}” and its synthesized audio will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(candidate)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ImportFab(onImportPdf: () -> Unit, onAddWebPage: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Add, contentDescription = "Import")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Import PDF") },
                leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                onClick = {
                    expanded = false
                    onImportPdf()
                },
            )
            DropdownMenuItem(
                text = { Text("Add web page") },
                leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAddWebPage()
                },
            )
        }
    }
}

@Composable
private fun NoModelBanner(onOpenModels: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("No voice installed", style = MaterialTheme.typography.titleSmall)
            Text(
                "Download a voice model to start listening to your documents.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenModels, modifier = Modifier.align(Alignment.End)) {
                Text("Get a voice")
            }
        }
    }
}

@Composable
private fun EmptyLibraryHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Your library is empty.\nImport a PDF or share a web page to begin.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryItemCard(
    item: LibraryItem,
    failureMessage: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (item.source) {
                    is DocumentSource.Pdf -> Icons.Filled.PictureAsPdf
                    is DocumentSource.WebPage -> Icons.Filled.Language
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when (item.status) {
                    DocumentStatus.IMPORTING -> ImportingRow()
                    DocumentStatus.FAILED -> FailedRow(failureMessage, onRetry, onDelete)
                    DocumentStatus.READY -> ReadyRow(item)
                }
            }
        }
    }
}

@Composable
private fun ImportingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Importing…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedRow(failureMessage: String?, onRetry: () -> Unit, onDelete: () -> Unit) {
    Column {
        Text(
            failureMessage ?: "Import failed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Row {
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun ReadyRow(item: LibraryItem) {
    val percent = (item.progressFraction * 100).toInt()
    Column {
        Text(
            "$percent% · ${item.sentenceCount} sentences",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (percent > 0) {
            LinearProgressIndicator(
                progress = { item.progressFraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AddWebPageDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add web page") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                placeholder = { Text("https://example.com/article") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank(),
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
