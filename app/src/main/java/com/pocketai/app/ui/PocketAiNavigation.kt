package com.pocketai.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.ui.chat.ChatScreen
import com.pocketai.app.ui.benchmark.BenchmarkScreen
import com.pocketai.app.ui.benchmark.BenchmarkViewModel
import com.pocketai.app.ui.chat.ChatViewModel
import com.pocketai.app.ui.models.ModelsScreen
import com.pocketai.app.ui.models.ModelsViewModel
import com.pocketai.app.ui.onboarding.OnboardingScreen
import com.pocketai.app.ui.privacy.LicensesScreen
import com.pocketai.app.ui.privacy.PrivacyScreen
import com.pocketai.app.ui.privacy.PrivacyViewModel
import com.pocketai.app.ui.settings.SettingsScreen
import com.pocketai.app.ui.settings.SettingsViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val LICENSES = "licenses"
    const val BENCHMARK = "benchmark"
}

@Composable
fun PocketAiNavigation(settings: AppSettings) {
    val navController = rememberNavController()
    val start = if (settings.onboardingComplete) Routes.CHAT else Routes.ONBOARDING

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            val modelsViewModel: ModelsViewModel = viewModel()
            OnboardingScreen(
                viewModel = modelsViewModel,
                onFinished = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.CHAT) {
            val chatViewModel: ChatViewModel = viewModel()
            ChatScreen(
                viewModel = chatViewModel,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenModels = { navController.navigate(Routes.MODELS) },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) }
            )
        }
        composable(Routes.MODELS) {
            val modelsViewModel: ModelsViewModel = viewModel()
            ModelsScreen(viewModel = modelsViewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenModels = { navController.navigate(Routes.MODELS) },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                onOpenBenchmark = { navController.navigate(Routes.BENCHMARK) }
            )
        }
        composable(Routes.PRIVACY) {
            val privacyViewModel: PrivacyViewModel = viewModel()
            PrivacyScreen(viewModel = privacyViewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.LICENSES) {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.BENCHMARK) {
            val benchmarkViewModel: BenchmarkViewModel = viewModel()
            BenchmarkScreen(viewModel = benchmarkViewModel, onBack = { navController.popBackStack() })
        }
    }
}
