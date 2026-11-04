package com.cinesort.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cinesort.app.R
import com.cinesort.app.feature.flow.PreviewScreen
import com.cinesort.app.feature.flow.ReportScreen
import com.cinesort.app.feature.flow.RunScreen
import com.cinesort.app.feature.flow.ScanScreen
import com.cinesort.app.feature.history.HistoryScreen
import com.cinesort.app.feature.home.HomeScreen
import com.cinesort.app.feature.onboarding.OnboardingScreen
import com.cinesort.app.feature.settings.SettingsScreen
import com.cinesort.app.feature.update.AppUpdateController
import com.cinesort.app.feature.update.AppUpdateDialog
import com.cinesort.app.feature.sources.SourcesScreen
import com.cinesort.core.data.repository.SettingsRepository

private data class TabItem(val route: String, val labelRes: Int, val icon: ImageVector)

private val Tabs = listOf(
    TabItem(Routes.HOME, R.string.tab_home, Icons.Filled.Home),
    TabItem(Routes.SOURCES, R.string.tab_sources, Icons.Filled.Folder),
    TabItem(Routes.HISTORY, R.string.tab_history, Icons.Filled.History),
    TabItem(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)

/**
 * Single-activity navigation host (spec 10.3): bottom bar with four tabs on
 * tab routes only; Scan/Preview/Run/Report and Onboarding are full-screen.
 */
@Composable
fun CineSortNavHost(
    startOnOnboarding: Boolean,
    persianDigits: Boolean,
    settingsRepository: SettingsRepository,
) {
    // One update controller for the whole app: the launch popup and the
    // Settings → About row share its state (Anvex pattern). Owned by an
    // activity-scoped ViewModel (audit B.11) — remember{} + DisposableEffect
    // here used to dispose it on activity recreation, killing a download.
    val updateViewModel: com.cinesort.app.feature.update.AppUpdateViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val updateController = updateViewModel.controller
    var updateDialogDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(updateController.state) {
        // A fresh find re-opens the popup even after an earlier dismissal.
        if (updateController.state is AppUpdateController.State.Available) {
            updateDialogDismissed = false
        }
    }
    // The on-launch check is opt-out (review M-E): wait for the persisted
    // settings (null until loaded) so a disabled preference can never race
    // a check in before it is read. Manual checks in Settings are unaffected.
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val autoCheckUpdates = settings?.autoCheckUpdates
    LaunchedEffect(autoCheckUpdates) {
        if (autoCheckUpdates == true) {
            updateController.check()
        }
    }
    val updateState = updateController.state
    val showUpdateDialog = !updateDialogDismissed &&
        (
            updateState is AppUpdateController.State.Available ||
                updateState is AppUpdateController.State.Downloading ||
                updateState is AppUpdateController.State.Downloaded ||
                // Failed included (audit 1-14): a failed download used to
                // close the dialog silently — the user who started the
                // download from the launch popup never saw any error.
                updateState is AppUpdateController.State.Failed
            )
    if (showUpdateDialog) {
        AppUpdateDialog(
            controller = updateController,
            persianDigits = persianDigits,
            onDismiss = { updateDialogDismissed = true },
        )
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in Routes.TABS

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Tabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(imageVector = tab.icon, contentDescription = null)
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (startOnOnboarding) Routes.ONBOARDING else Routes.HOME,
            modifier = Modifier.padding(innerPadding),
            // A subtle fade/slide instead of the abrupt default cut
            // (audit 1-3: the app had no custom transitions anywhere).
            enterTransition = {
                fadeIn(animationSpec = tween(220)) +
                    slideInHorizontally(
                        animationSpec = tween(220),
                    ) { width -> width / 12 }
            },
            exitTransition = { fadeOut(animationSpec = tween(180)) },
            popEnterTransition = {
                fadeIn(animationSpec = tween(220)) +
                    slideInHorizontally(
                        animationSpec = tween(220),
                    ) { width -> -width / 12 }
            },
            popExitTransition = { fadeOut(animationSpec = tween(180)) },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onScanClick = { navController.navigate(Routes.SCAN) },
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onCompleteSetup = { navController.navigate(Routes.ONBOARDING) },
                )
            }
            composable(Routes.SOURCES) {
                SourcesScreen()
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenRun = { runId -> navController.navigate(Routes.report(runId)) },
                    onStartScan = { navController.navigate(Routes.SCAN) },
                    onCompleteSetup = { navController.navigate(Routes.ONBOARDING) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
                    updateController = updateController,
                )
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPreview = { navController.navigate(Routes.PREVIEW) },
                    onOpenRun = { navController.navigate(Routes.RUN) },
                )
            }
            composable(Routes.PREVIEW) {
                PreviewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRun = { navController.navigate(Routes.RUN) },
                )
            }
            composable(Routes.RUN) {
                RunScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReport = { runId ->
                        navController.navigate(Routes.report(runId)) {
                            popUpTo(Routes.RUN) { inclusive = true }
                        }
                    },
                    onOpenHome = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                )
            }
            composable(Routes.REPORT) { entry ->
                ReportScreen(
                    runId = entry.arguments?.getString(Routes.ARG_RUN_ID),
                    onBack = { navController.popBackStack() },
                    onOpenRun = { navController.navigate(Routes.RUN) },
                )
            }
        }
    }
}
