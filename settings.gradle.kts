pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
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
