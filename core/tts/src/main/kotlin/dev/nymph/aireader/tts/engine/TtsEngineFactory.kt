package dev.nymph.aireader.tts.engine

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.tts.TtsEngine
import dev.nymph.aireader.tts.models.ModelStore
import dev.nymph.aireader.tts.models.VoiceModelDescriptor
import java.io.File

/**
 * Sole entry point for obtaining a ready [TtsEngine]. Refuses to build an
 * engine unless the model's `.complete` marker is on disk, so the native
 * loader never sees a half-extracted model.
 *
 * [createEngine] exists for JVM tests; production uses the default, which
 * constructs a [SherpaTtsEngine] (and therefore loads the native library).
 */
class TtsEngineFactory(
    private val store: ModelStore,
    private val createEngine: (VoiceModelDescriptor, File) -> TtsEngine = { descriptor, dir ->
        SherpaTtsEngine(descriptor, dir)
    },
) {

    fun create(descriptor: VoiceModelDescriptor): Outcome<TtsEngine, TtsError> {
        if (!store.isInstalled(descriptor.modelId)) {
            return Outcome.Err(
                TtsError.ModelNotInstalled(
                    descriptor.engineId,
                    "model ${descriptor.modelId} is not installed",
                ),
            )
        }
        return Outcome.Ok(createEngine(descriptor, store.modelDir(descriptor.modelId)))
    }
}
