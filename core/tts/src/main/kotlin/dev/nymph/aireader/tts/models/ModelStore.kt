package dev.nymph.aireader.tts.models

import java.io.File

/**
 * Filesystem layout for installed voice models under a single [rootDir]
 * (typically `context.filesDir/tts`).
 *
 * Layout:
 * - `<root>/<modelId>/`              extracted model files
 * - `<root>/<modelId>/.complete`     marker written only after successful extraction
 * - `<root>/<modelId>.tar.bz2.part`  in-flight (resumable) download
 *
 * A model is installed iff its `.complete` marker exists; partially extracted
 * directories without the marker are treated as not installed.
 */
class ModelStore(val rootDir: File) {

    fun modelDir(modelId: String): File = File(rootDir, modelId)

    fun partFile(modelId: String): File = File(rootDir, "$modelId.tar.bz2.part")

    fun completionMarker(modelId: String): File = File(modelDir(modelId), ".complete")

    fun isInstalled(modelId: String): Boolean = completionMarker(modelId).isFile

    /** Writes the completion marker, creating the model dir if needed. */
    fun markInstalled(modelId: String) {
        modelDir(modelId).mkdirs()
        completionMarker(modelId).writeText("")
    }

    /** Removes the extracted model dir and any in-flight download. Idempotent. */
    fun uninstall(modelId: String) {
        modelDir(modelId).deleteRecursively()
        partFile(modelId).delete()
    }

    /** Resolves a [ModelFilesOfInterest] relative path inside the model dir. */
    fun resolve(modelId: String, relativePath: String): File =
        File(modelDir(modelId), relativePath)
}
