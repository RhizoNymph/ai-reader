package dev.nymph.aireader.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.nymph.aireader.ui.library.LibraryViewModel
import dev.nymph.aireader.ui.models.ModelsViewModel
import dev.nymph.aireader.ui.player.PlayerViewModel
import dev.nymph.aireader.ui.settings.SettingsViewModel

/**
 * Manual ViewModel factories over the [AppContainer]; each ViewModel receives
 * exactly the dependencies it uses (no container injection into ViewModels).
 */

private fun <VM : ViewModel> factoryOf(create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

fun libraryViewModelFactory(container: AppContainer): ViewModelProvider.Factory = factoryOf {
    LibraryViewModel(
        library = container.libraryRepository,
        importCoordinator = container.importCoordinator,
        chunkCache = container.chunkCache,
        modelRepository = container.modelRepository,
        appScope = container.appScope,
    )
}

fun playerViewModelFactory(container: AppContainer, docId: Long): ViewModelProvider.Factory =
    factoryOf {
        PlayerViewModel(
            docId = docId,
            controller = container.playbackController,
            library = container.libraryRepository,
            settings = container.settingsRepository,
            modelStore = container.modelStore,
            activePlayback = container.activePlayback,
            startPlaybackService = container::startPlaybackService,
            appScope = container.appScope,
            workManager = container.workManager,
        )
    }

fun modelsViewModelFactory(container: AppContainer): ViewModelProvider.Factory = factoryOf {
    ModelsViewModel(
        modelRepository = container.modelRepository,
        installs = container.modelInstalls,
    )
}

fun settingsViewModelFactory(container: AppContainer): ViewModelProvider.Factory = factoryOf {
    SettingsViewModel(
        settings = container.settingsRepository,
        modelRepository = container.modelRepository,
    )
}
