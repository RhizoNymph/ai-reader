package dev.nymph.aireader.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.nymph.aireader.di.AppContainer
import dev.nymph.aireader.di.libraryViewModelFactory
import dev.nymph.aireader.di.modelsViewModelFactory
import dev.nymph.aireader.di.playerViewModelFactory
import dev.nymph.aireader.di.settingsViewModelFactory
import dev.nymph.aireader.ui.library.LibraryScreen
import dev.nymph.aireader.ui.library.LibraryViewModel
import dev.nymph.aireader.ui.models.ModelsScreen
import dev.nymph.aireader.ui.models.ModelsViewModel
import dev.nymph.aireader.ui.player.PlayerScreen
import dev.nymph.aireader.ui.player.PlayerViewModel
import dev.nymph.aireader.ui.settings.SettingsScreen
import dev.nymph.aireader.ui.settings.SettingsViewModel

object Routes {
    const val LIBRARY = "library"
    const val PLAYER = "player/{docId}"
    const val SETTINGS = "settings"
    const val MODELS = "models"

    fun player(docId: Long): String = "player/$docId"
}

@Composable
fun AiReaderNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            val viewModel: LibraryViewModel = viewModel(factory = libraryViewModelFactory(container))
            LibraryScreen(
                viewModel = viewModel,
                onOpenDocument = { docId -> navController.navigate(Routes.player(docId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenModels = { navController.navigate(Routes.MODELS) },
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("docId") { type = NavType.LongType }),
        ) { entry ->
            val docId = entry.arguments?.getLong("docId") ?: return@composable
            val viewModel: PlayerViewModel =
                viewModel(factory = playerViewModelFactory(container, docId))
            PlayerScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenModels = { navController.navigate(Routes.MODELS) },
            )
        }
        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = settingsViewModelFactory(container))
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.MODELS) {
            val viewModel: ModelsViewModel = viewModel(factory = modelsViewModelFactory(container))
            ModelsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
