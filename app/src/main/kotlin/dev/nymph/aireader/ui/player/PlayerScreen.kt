package dev.nymph.aireader.ui.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.playback.synth.SynthState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (uiState as? PlayerUiState.Ready)?.title ?: "Player",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    (uiState as? PlayerUiState.Ready)?.let { ready ->
                        ExportAction(viewModel, ready.title)
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is PlayerUiState.Loading -> CenteredMessage("Loading…")
                is PlayerUiState.ModelMissing ->
                    ModelMissingPrompt(state.engineName, onOpenModels, onRetry = viewModel::reload)
                is PlayerUiState.LoadFailed -> CenteredMessage(state.message, isError = true)
                is PlayerUiState.Ready -> PlayerContent(viewModel, state)
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ModelMissingPrompt(engineName: String, onOpenModels: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "The voice model for this document ($engineName) is not installed.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onOpenModels, modifier = Modifier.padding(top = 16.dp)) {
            Text("Open voice models")
        }
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun PlayerContent(viewModel: PlayerViewModel, state: PlayerUiState.Ready) {
    val currentIndex by viewModel.currentSentenceIndex.collectAsState()
    val synth by viewModel.synthState.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val voice by viewModel.voice.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        SentenceList(
            sentences = state.sentences,
            currentIndex = currentIndex,
            onSentenceTap = viewModel::seekToSentence,
            modifier = Modifier.weight(1f),
        )
        SynthStatusLine(synth, currentIndex)
        ExportStatusLine(
            state = viewModel.exportState.collectAsState().value,
            onCancel = viewModel::cancelExport,
        )
        TransportControls(
            isPlaying = isPlaying,
            onPlay = viewModel::play,
            onPause = viewModel::pause,
            onPrevious = viewModel::skipPrevious,
            onNext = viewModel::skipNext,
            onSeekBack = viewModel::seekBack,
        )
        SpeedAndVoiceRow(
            speed = voice?.speed ?: 1.0f,
            voiceLabel = voiceLabel(viewModel, voice?.voiceId),
            onSpeed = viewModel::setSpeed,
            onPickVoice = viewModel::selectVoice,
            voiceChoices = viewModel::installedVoices,
        )
    }
}

private fun voiceLabel(viewModel: PlayerViewModel, voiceId: String?): String =
    viewModel.installedVoices().firstOrNull { it.voiceId == voiceId }?.displayName
        ?: voiceId.orEmpty()

@Composable
private fun SentenceList(
    sentences: List<SpeakableSentence>,
    currentIndex: Int,
    onSentenceTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(sentences, key = { _, s -> s.index }) { index, sentence ->
            val isCurrent = index == currentIndex
            Text(
                sentence.displayText,
                style = if (isCurrent) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onSentenceTap(index) }
                    .padding(8.dp),
            )
        }
    }
}

/**
 * The top-bar "download audiobook" button: opens a SAF create-document picker,
 * then hands the chosen destination to the export worker. Disabled while an
 * export for this document is queued or running.
 */
@Composable
private fun ExportAction(viewModel: PlayerViewModel, title: String) {
    val exportState by viewModel.exportState.collectAsState()
    val context = LocalContext.current
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4"),
    ) { uri ->
        if (uri != null) {
            // The export outlives this activity; keep the write grant durable.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Provider offered no persistable grant; the transient one
                // covers this process's lifetime, which is the common case.
            }
            viewModel.startExport(uri)
        }
    }
    val busy = exportState is ExportUiState.Queued || exportState is ExportUiState.InProgress
    IconButton(
        onClick = { createDocument.launch(exportFileName(title)) },
        enabled = !busy,
    ) {
        Icon(Icons.Filled.Download, contentDescription = "Download audiobook")
    }
}

/** The suggested file name: the document title, made filesystem-safe. */
private fun exportFileName(title: String): String {
    val safe = title.map { if (it.isLetterOrDigit() || it in " ._-()") it else '_' }
        .joinToString("")
        .trim()
        .ifBlank { "audiobook" }
    return "$safe.m4a"
}

@Composable
private fun ExportStatusLine(state: ExportUiState, onCancel: () -> Unit) {
    if (state is ExportUiState.Idle) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp)) {
        when (state) {
            is ExportUiState.Idle -> Unit
            is ExportUiState.Queued -> ExportProgressRow("Export queued…", progress = null, onCancel)
            is ExportUiState.InProgress -> {
                val label = when (state.phase) {
                    ExportPhase.SYNTHESIZING -> "Rendering audio · ${state.completed} / ${state.total}"
                    ExportPhase.ENCODING -> "Encoding audiobook · ${state.completed} / ${state.total}"
                    ExportPhase.SAVING -> "Saving…"
                }
                val fraction = if (state.total > 0) state.completed.toFloat() / state.total else null
                ExportProgressRow(label, fraction, onCancel)
            }
            is ExportUiState.Saved -> Text(
                "Audiobook saved",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is ExportUiState.Failed -> Text(
                "Export failed: ${state.message}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ExportProgressRow(label: String, progress: Float?, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel export")
        }
    }
}

@Composable
private fun SynthStatusLine(synth: SynthState, currentIndex: Int) {
    val readyAhead = (synth.cachedThrough - currentIndex + 1).coerceAtLeast(0)
    val waitingForAudio = synth.cachedThrough < currentIndex && !synth.isComplete
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        if (waitingForAudio) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (synth.lastError != null) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = "A sentence failed to synthesize",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp).padding(end = 4.dp),
                )
            }
            Text(
                text = when {
                    synth.isComplete -> "All audio ready"
                    waitingForAudio -> "Preparing audio…"
                    else -> "Synthesizing ahead… $readyAhead ready"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBack: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Playback proceeds either way; the permission only gates the notification.
        onPlay()
    }
    val playWithPermission = {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onPlay()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSeekBack) {
            Icon(Icons.Filled.Replay, contentDescription = "Back 3 sentences")
        }
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous sentence")
        }
        FilledIconButton(
            onClick = { if (isPlaying) onPause() else playWithPermission() },
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next sentence")
        }
    }
}

@Composable
private fun SpeedAndVoiceRow(
    speed: Float,
    voiceLabel: String,
    onSpeed: (Float) -> Unit,
    onPickVoice: (VoiceChoice) -> Unit,
    voiceChoices: () -> List<VoiceChoice>,
) {
    var showVoicePicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SPEED_OPTIONS.forEach { option ->
            FilterChip(
                selected = option == speed,
                onClick = { onSpeed(option) },
                label = { Text(formatSpeed(option)) },
            )
        }
        Box(modifier = Modifier.weight(1f))
        TextButton(onClick = { showVoicePicker = true }) {
            Text(voiceLabel.ifBlank { "Voice" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    if (showVoicePicker) {
        val choices = remember { voiceChoices() }
        AlertDialog(
            onDismissRequest = { showVoicePicker = false },
            title = { Text("Voice") },
            text = {
                Column {
                    if (choices.isEmpty()) {
                        Text("No voice models installed.")
                    }
                    choices.forEach { choice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showVoicePicker = false
                                    onPickVoice(choice)
                                },
                        ) {
                            RadioButton(
                                selected = choice.displayName == voiceLabel,
                                onClick = {
                                    showVoicePicker = false
                                    onPickVoice(choice)
                                },
                            )
                            Column {
                                Text(choice.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    choice.descriptor.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoicePicker = false }) { Text("Close") }
            },
        )
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
