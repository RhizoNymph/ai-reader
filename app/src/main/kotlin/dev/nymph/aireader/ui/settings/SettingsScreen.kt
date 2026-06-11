package dev.nymph.aireader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.tts.models.VoiceModelCatalog
import dev.nymph.aireader.ui.player.SPEED_OPTIONS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.state.collectAsState()
    val installedEngines by viewModel.installedEngines.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Default engine")
            VoiceModelCatalog.all.forEach { descriptor ->
                val installed = descriptor.engineId in installedEngines
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = installed) { viewModel.setDefaultEngine(descriptor.engineId) },
                ) {
                    RadioButton(
                        selected = settings.defaultEngineId == descriptor.engineId,
                        onClick = { viewModel.setDefaultEngine(descriptor.engineId) },
                        enabled = installed,
                    )
                    Column {
                        Text(descriptor.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (!installed) {
                            Text(
                                "Not installed — download it on the Voice models screen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            SectionHeader("Default voice")
            DefaultVoicePicker(
                engineId = settings.defaultEngineId,
                selectedVoiceId = settings.defaultVoiceId,
                enabled = settings.defaultEngineId in installedEngines,
                onSelect = viewModel::setDefaultVoice,
            )

            HorizontalDivider()
            SectionHeader("Default speed")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SPEED_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = option == settings.defaultSpeed,
                        onClick = { viewModel.setDefaultSpeed(option) },
                        label = { Text(speedLabel(option)) },
                    )
                }
            }

            HorizontalDivider()
            SectionHeader("Synthesis lookahead")
            LookaheadSlider(
                current = settings.synthesisLookaheadSentences,
                onCommit = viewModel::setLookahead,
            )

            HorizontalDivider()
            AboutSection()
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DefaultVoicePicker(
    engineId: TtsEngineId,
    selectedVoiceId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val voices = VoiceModelCatalog.forEngine(engineId).voices
    Column {
        voices.forEach { voice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSelect(voice.id) },
            ) {
                RadioButton(
                    selected = voice.id == selectedVoiceId,
                    onClick = { onSelect(voice.id) },
                    enabled = enabled,
                )
                Text(voice.displayName, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LookaheadSlider(current: Int, onCommit: (Int) -> Unit) {
    var sliderValue by remember(current) { mutableStateOf(current.toFloat()) }
    Column {
        Text(
            "${sliderValue.toInt()} sentences synthesized ahead of the playhead",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onCommit(sliderValue.toInt()) },
            valueRange = LOOKAHEAD_RANGE.first.toFloat()..LOOKAHEAD_RANGE.last.toFloat(),
            steps = LOOKAHEAD_RANGE.last - LOOKAHEAD_RANGE.first - 1,
        )
        Text(
            "Applies from the next app start.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val versionName = remember {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("About")
        Text("AI Reader $versionName", style = MaterialTheme.typography.bodyMedium)
        Text(
            "On-device text-to-speech models by k2-fsa/sherpa-onnx; voices: Kokoro and Piper.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
