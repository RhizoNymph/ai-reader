package dev.nymph.aireader.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nymph.aireader.tts.models.VoiceModelDescriptor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    val rows by viewModel.rows.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Voice models are large one-time downloads (roughly 80–130 MB). " +
                        "Using Wi-Fi is recommended.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(rows, key = { it.descriptor.modelId }) { row ->
                ModelCard(
                    row = row,
                    onDownload = { viewModel.download(row.descriptor) },
                    onCancel = { viewModel.cancel(row.descriptor) },
                    onUninstall = { viewModel.uninstall(row.descriptor) },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    row: ModelRow,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
) {
    val descriptor = row.descriptor
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(descriptor.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${descriptor.voices.size} voices · ${descriptor.language} · ${sizeLabel(descriptor)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val status = row.status) {
                is ModelRowStatus.NotInstalled -> ActionRow {
                    OutlinedButton(onClick = onDownload) { Text("Download") }
                }
                is ModelRowStatus.Paused -> {
                    StatusText("Download paused; resume to continue where it left off.")
                    ActionRow {
                        OutlinedButton(onClick = onDownload) { Text("Resume") }
                    }
                }
                is ModelRowStatus.Downloading -> {
                    LinearProgressIndicator(
                        progress = { status.progressPct / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusText("Downloading… ${status.progressPct}%")
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                is ModelRowStatus.Installed -> {
                    StatusText("Installed")
                    ActionRow {
                        TextButton(onClick = onUninstall) { Text("Uninstall") }
                    }
                }
                is ModelRowStatus.Failed -> {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    ActionRow {
                        OutlinedButton(onClick = onDownload) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        content()
    }
}

private fun sizeLabel(descriptor: VoiceModelDescriptor): String =
    "${descriptor.archiveSizeBytes / 1_000_000} MB"
