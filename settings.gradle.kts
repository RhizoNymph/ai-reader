pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle download the JDK 17 toolchain the JVM modules need when it isn't installed.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // sherpa-onnx is not on Maven Central; resolve its AAR straight from GitHub releases.
        // The SHA-256 is pinned in gradle/verification-metadata.xml.
        exclusiveContent {
            forRepository {
                ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download/") {
                    name = "sherpaOnnxGitHubReleases"
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.k2fsa", "sherpa-onnx") }
        }
    }
}

rootProject.name = "ai-reader"

include(":app")
include(":core:model")
include(":core:pipeline")
include(":core:extraction")
include(":core:tts")
include(":core:playback")
include(":core:data")
