package dev.nymph.aireader.di

import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.tts.models.ModelRepository
import dev.nymph.aireader.tts.models.VoiceModelDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns model download jobs in the app scope so they survive leaving the Models
 * screen, and makes them cancellable.
 *
 * Cancellation caveat: [ModelRepository] only updates its state when an install
 * *finishes*, so after a cancel its StateFlow can be stuck at `Downloading`.
 * [activeInstalls] is the corrective signal: a `Downloading` state whose engine
 * is not in [activeInstalls] means "paused/cancelled, resumable" (the partial
 * download stays on disk and a new install resumes it).
 */
class ModelInstallCoordinator(
    private val repository: ModelRepository,
    private val scope: CoroutineScope,
) {

    private val lock = Any()
    private val jobs = mutableMapOf<TtsEngineId, Job>()

    private val _activeInstalls = MutableStateFlow<Set<TtsEngineId>>(emptySet())

    /** Engines with an install coroutine currently running. */
    val activeInstalls: StateFlow<Set<TtsEngineId>> = _activeInstalls.asStateFlow()

    /** Start (or resume) installing; no-op when an install for this engine is already running. */
    fun install(descriptor: VoiceModelDescriptor) {
        synchronized(lock) {
            if (jobs[descriptor.engineId]?.isActive == true) return
            val job = scope.launch { repository.install(descriptor) }
            jobs[descriptor.engineId] = job
            _activeInstalls.update { it + descriptor.engineId }
            job.invokeOnCompletion {
                synchronized(lock) { if (jobs[descriptor.engineId] === job) jobs.remove(descriptor.engineId) }
                _activeInstalls.update { it - descriptor.engineId }
            }
        }
    }

    /** Cancel a running install; the partial archive is kept for a later resume. */
    fun cancel(engineId: TtsEngineId) {
        synchronized(lock) { jobs[engineId]?.cancel() }
    }
}
