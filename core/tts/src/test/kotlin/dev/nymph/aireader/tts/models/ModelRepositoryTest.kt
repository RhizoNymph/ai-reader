package dev.nymph.aireader.tts.models

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.TtsError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val descriptor = VoiceModelCatalog.forEngine(TtsEngineId.KOKORO)
    private val piperDescriptor = VoiceModelCatalog.forEngine(TtsEngineId.PIPER)

    /** Fake installer: drives progress then returns a configured result. */
    private class FakeInstaller(
        private val progressSteps: List<Pair<Long, Long>> = emptyList(),
        private val result: (VoiceModelDescriptor, ModelStore) -> Outcome<Unit, TtsError>,
    ) : ModelInstaller {
        var calls = 0
        lateinit var store: ModelStore

        override suspend fun downloadAndInstall(
            descriptor: VoiceModelDescriptor,
            onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        ): Outcome<Unit, TtsError> {
            calls++
            progressSteps.forEach { (read, total) -> onProgress(read, total) }
            return result(descriptor, store)
        }
    }

    private fun repo(installer: FakeInstaller): Pair<ModelRepository, ModelStore> {
        val store = ModelStore(tmp.newFolder())
        installer.store = store
        return ModelRepository(store, installer) to store
    }

    @Test
    fun `initial state reflects store contents`() {
        val store = ModelStore(tmp.newFolder())
        store.markInstalled(descriptor.modelId)
        val repository = ModelRepository(store, FakeInstaller { _, _ -> Outcome.Ok(Unit) }.also { it.store = store })

        assertEquals(ModelState.Installed, repository.observe(TtsEngineId.KOKORO).value)
        assertEquals(ModelState.NotInstalled, repository.observe(TtsEngineId.PIPER).value)
    }

    @Test
    fun `successful install transitions through Downloading to Installed`() = runTest {
        val installer = FakeInstaller(
            progressSteps = listOf(0L to 200L, 50L to 200L, 200L to 200L),
        ) { d, store ->
            store.markInstalled(d.modelId)
            Outcome.Ok(Unit)
        }
        val (repository, store) = repo(installer)

        val seen = mutableListOf<ModelState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observe(TtsEngineId.KOKORO).toList(seen)
        }

        val result = repository.install(descriptor)

        assertIs<Outcome.Ok<Unit>>(result)
        assertTrue(store.isInstalled(descriptor.modelId))
        assertEquals(ModelState.Installed, repository.observe(TtsEngineId.KOKORO).value)
        assertTrue(seen.contains(ModelState.Downloading(0)))
        assertTrue(seen.contains(ModelState.Downloading(25)))
        assertTrue(seen.contains(ModelState.Downloading(100)))
        assertEquals(ModelState.Installed, seen.last())
        job.cancel()
    }

    @Test
    fun `failed install transitions to Failed and returns the error`() = runTest {
        val error: TtsError = TtsError.DownloadFailed(TtsEngineId.KOKORO, "http 500")
        val installer = FakeInstaller { _, _ -> Outcome.Err(error) }
        val (repository, _) = repo(installer)

        val result = repository.install(descriptor)

        assertIs<Outcome.Err<TtsError>>(result)
        assertEquals(error, result.error)
        assertEquals(ModelState.Failed(error), repository.observe(TtsEngineId.KOKORO).value)
    }

    @Test
    fun `install of an already installed model short-circuits`() = runTest {
        val installer = FakeInstaller { _, _ -> Outcome.Ok(Unit) }
        val (repository, store) = repo(installer)
        store.markInstalled(descriptor.modelId)

        val result = repository.install(descriptor)

        assertIs<Outcome.Ok<Unit>>(result)
        assertEquals(0, installer.calls)
        assertEquals(ModelState.Installed, repository.observe(TtsEngineId.KOKORO).value)
    }

    @Test
    fun `uninstall resets state and removes files`() = runTest {
        val installer = FakeInstaller { d, store ->
            store.markInstalled(d.modelId)
            Outcome.Ok(Unit)
        }
        val (repository, store) = repo(installer)
        repository.install(descriptor)
        assertEquals(ModelState.Installed, repository.observe(TtsEngineId.KOKORO).value)

        repository.uninstall(descriptor)

        assertEquals(ModelState.NotInstalled, repository.observe(TtsEngineId.KOKORO).value)
        assertTrue(!store.isInstalled(descriptor.modelId))
    }

    @Test
    fun `engines have independent state flows`() = runTest {
        val installer = FakeInstaller { d, store ->
            store.markInstalled(d.modelId)
            Outcome.Ok(Unit)
        }
        val (repository, _) = repo(installer)

        repository.install(piperDescriptor)

        assertEquals(ModelState.Installed, repository.observe(TtsEngineId.PIPER).value)
        assertEquals(ModelState.NotInstalled, repository.observe(TtsEngineId.KOKORO).value)
    }

    @Test
    fun `progress with unknown total falls back to descriptor size`() = runTest {
        // totalBytes <= 0 means "unknown" from the downloader
        val installer = FakeInstaller(
            progressSteps = listOf((descriptor.archiveSizeBytes / 2) to -1L),
        ) { d, store ->
            store.markInstalled(d.modelId)
            Outcome.Ok(Unit)
        }
        val (repository, _) = repo(installer)

        val seen = mutableListOf<ModelState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observe(TtsEngineId.KOKORO).toList(seen)
        }
        repository.install(descriptor)

        assertTrue(seen.contains(ModelState.Downloading(50)))
        job.cancel()
    }
}
