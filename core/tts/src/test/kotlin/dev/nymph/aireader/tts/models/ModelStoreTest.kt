package dev.nymph.aireader.tts.models

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ModelStore = ModelStore(tmp.root)

    @Test
    fun `modelDir is rootDir slash modelId`() {
        assertEquals(File(tmp.root, "kokoro-x"), store().modelDir("kokoro-x"))
    }

    @Test
    fun `partFile is rootDir slash modelId tar bz2 part`() {
        assertEquals(File(tmp.root, "kokoro-x.tar.bz2.part"), store().partFile("kokoro-x"))
    }

    @Test
    fun `completionMarker lives inside the model dir`() {
        assertEquals(
            File(File(tmp.root, "kokoro-x"), ".complete"),
            store().completionMarker("kokoro-x"),
        )
    }

    @Test
    fun `isInstalled is false when nothing exists`() {
        assertFalse(store().isInstalled("kokoro-x"))
    }

    @Test
    fun `isInstalled is false when dir exists without marker`() {
        val s = store()
        s.modelDir("kokoro-x").mkdirs()
        assertFalse(s.isInstalled("kokoro-x"))
    }

    @Test
    fun `markInstalled writes the marker and isInstalled becomes true`() {
        val s = store()
        s.modelDir("kokoro-x").mkdirs()
        s.markInstalled("kokoro-x")
        assertTrue(s.completionMarker("kokoro-x").isFile)
        assertTrue(s.isInstalled("kokoro-x"))
    }

    @Test
    fun `markInstalled creates parent dirs when missing`() {
        val s = store()
        s.markInstalled("fresh-model")
        assertTrue(s.isInstalled("fresh-model"))
    }

    @Test
    fun `uninstall removes model dir and part file`() {
        val s = store()
        s.modelDir("kokoro-x").mkdirs()
        File(s.modelDir("kokoro-x"), "model.onnx").writeText("weights")
        s.markInstalled("kokoro-x")
        s.partFile("kokoro-x").writeText("partial")

        s.uninstall("kokoro-x")

        assertFalse(s.modelDir("kokoro-x").exists())
        assertFalse(s.partFile("kokoro-x").exists())
        assertFalse(s.isInstalled("kokoro-x"))
    }

    @Test
    fun `uninstall on missing model is a no-op`() {
        store().uninstall("never-installed")
        assertFalse(store().isInstalled("never-installed"))
    }

    @Test
    fun `resolve returns file under model dir`() {
        assertEquals(
            File(File(tmp.root, "m"), "espeak-ng-data"),
            store().resolve("m", "espeak-ng-data"),
        )
    }
}
