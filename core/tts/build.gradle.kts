plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.nymph.aireader.tts"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// sherpa-onnx is not on Maven Central; fetch the pinned release AAR and verify it.
val sherpaOnnxVersion = "1.13.2"
val sherpaOnnxSha256 = "aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245"
val sherpaOnnxAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")

val downloadSherpaOnnx by tasks.registering {
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
        "v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    val expected = sherpaOnnxSha256
    val target = sherpaOnnxAar.asFile

    inputs.property("url", url)
    inputs.property("sha256", expected)
    outputs.file(target)

    doLast {
        fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (target.isFile && sha256(target) == expected) return@doLast

        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        java.net.URI(url).toURL().openStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256(partial)
        if (actual != expected) {
            partial.delete()
            throw GradleException("sherpa-onnx AAR checksum mismatch: expected $expected, got $actual")
        }
        if (!partial.renameTo(target)) {
            throw GradleException("could not move ${partial.path} to ${target.path}")
        }
    }
}

dependencies {
    api(project(":core:model"))
    implementation(files(sherpaOnnxAar).builtBy(downloadSherpaOnnx))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
