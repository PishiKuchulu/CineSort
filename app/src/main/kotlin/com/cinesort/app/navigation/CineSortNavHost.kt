package com.cinesort.app.navigation

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
import androidx.compose.runtime.getValue
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
import com.cinesort.app.feature.sources.SourcesScreen

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
fun CineSortNavHost(startOnOnboarding: Boolean) {
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
                )
            }
            composable(Routes.SOURCES) {
                SourcesScreen()
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenRun = { runId -> navController.navigate(Routes.report(runId)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
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
