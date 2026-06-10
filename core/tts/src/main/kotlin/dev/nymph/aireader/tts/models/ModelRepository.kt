package dev.nymph.aireader.tts.models

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.TtsError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Install lifecycle of one engine's voice model. */
sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val progressPct: Int) : ModelState
    data object Installed : ModelState
    data class Failed(val error: TtsError) : ModelState
}

/**
 * Per-engine model install state, observable by the UI. Thin coordinator over
 * [ModelStore] (truth on disk) and a [ModelInstaller] (download + extraction).
 *
 * Concurrency: one install at a time per engine (per-engine [Mutex]); a second
 * concurrent [install] call for the same engine suspends and then
 * short-circuits on the `.complete` marker.
 */
class ModelRepository(
    private val store: ModelStore,
    private val installer: ModelInstaller,
    catalog: List<VoiceModelDescriptor> = VoiceModelCatalog.all,
) {

    private val states: Map<TtsEngineId, MutableStateFlow<ModelState>> =
        catalog.associate { descriptor ->
            descriptor.engineId to MutableStateFlow(
                if (store.isInstalled(descriptor.modelId)) ModelState.Installed
                else ModelState.NotInstalled,
            )
        }

    private val installLocks: Map<TtsEngineId, Mutex> =
        catalog.associate { it.engineId to Mutex() }

    fun observe(engineId: TtsEngineId): StateFlow<ModelState> = states.getValue(engineId)

    suspend fun install(descriptor: VoiceModelDescriptor): Outcome<Unit, TtsError> {
        val state = states.getValue(descriptor.engineId)
        return installLocks.getValue(descriptor.engineId).withLock {
            if (store.isInstalled(descriptor.modelId)) {
                state.value = ModelState.Installed
                return@withLock Outcome.Ok(Unit)
            }
            state.value = ModelState.Downloading(0)
            val result = installer.downloadAndInstall(descriptor) { bytesRead, totalBytes ->
                state.value = ModelState.Downloading(
                    progressPct(bytesRead, totalBytes, descriptor.archiveSizeBytes),
                )
            }
            state.value = when (result) {
                is Outcome.Ok -> ModelState.Installed
                is Outcome.Err -> ModelState.Failed(result.error)
            }
            result
        }
    }

    fun uninstall(descriptor: VoiceModelDescriptor) {
        store.uninstall(descriptor.modelId)
        states.getValue(descriptor.engineId).value = ModelState.NotInstalled
    }

    private companion object {
        fun progressPct(bytesRead: Long, totalBytes: Long, fallbackTotal: Long): Int {
            val total = if (totalBytes > 0) totalBytes else fallbackTotal
            if (total <= 0) return 0
            return (bytesRead * 100 / total).toInt().coerceIn(0, 100)
        }
    }
}
