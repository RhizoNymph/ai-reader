package dev.nymph.aireader.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.aireader.di.ModelInstallCoordinator
import dev.nymph.aireader.tts.models.ModelRepository
import dev.nymph.aireader.tts.models.ModelState
import dev.nymph.aireader.tts.models.VoiceModelCatalog
import dev.nymph.aireader.tts.models.VoiceModelDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI-facing install state for one catalog model. */
sealed interface ModelRowStatus {
    data object NotInstalled : ModelRowStatus

    /** A previous download was cancelled/interrupted; a new install resumes it. */
    data object Paused : ModelRowStatus

    data class Downloading(val progressPct: Int) : ModelRowStatus

    data object Installed : ModelRowStatus

    data class Failed(val message: String) : ModelRowStatus
}

data class ModelRow(
    val descriptor: VoiceModelDescriptor,
    val status: ModelRowStatus,
)

class ModelsViewModel(
    private val modelRepository: ModelRepository,
    private val installs: ModelInstallCoordinator,
) : ViewModel() {

    private val catalog = VoiceModelCatalog.all

    val rows: StateFlow<List<ModelRow>> = combine(
        combine(catalog.map { modelRepository.observe(it.engineId) }) { it.toList() },
        installs.activeInstalls,
    ) { states, active ->
        catalog.mapIndexed { i, descriptor ->
            ModelRow(descriptor, rowStatus(states[i], descriptor.engineId in active))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialRows())

    fun download(descriptor: VoiceModelDescriptor) {
        installs.install(descriptor)
    }

    fun cancel(descriptor: VoiceModelDescriptor) {
        installs.cancel(descriptor.engineId)
    }

    fun uninstall(descriptor: VoiceModelDescriptor) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { modelRepository.uninstall(descriptor) }
        }
    }

    private fun initialRows(): List<ModelRow> = catalog.map { descriptor ->
        ModelRow(descriptor, rowStatus(modelRepository.observe(descriptor.engineId).value, installing = false))
    }

    private companion object {
        /**
         * [ModelRepository]'s state can read `Downloading` after a cancelled
         * job (it only updates on completion); an inactive install marks that
         * case as [ModelRowStatus.Paused].
         */
        fun rowStatus(state: ModelState, installing: Boolean): ModelRowStatus = when (state) {
            is ModelState.NotInstalled -> ModelRowStatus.NotInstalled
            is ModelState.Installed -> ModelRowStatus.Installed
            is ModelState.Failed -> ModelRowStatus.Failed(state.error.message)
            is ModelState.Downloading ->
                if (installing) ModelRowStatus.Downloading(state.progressPct) else ModelRowStatus.Paused
        }
    }
}
