package com.adsamcik.tracker.statistics.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard
import com.adsamcik.tracker.feature.map.api.navigation.MapTripContext
import com.adsamcik.tracker.feature.map.api.preview.RoutePreviewRenderer
import com.adsamcik.tracker.feature.statistics.api.navigation.History
import com.adsamcik.tracker.feature.statistics.api.navigation.Stats
import com.adsamcik.tracker.feature.statistics.api.navigation.StatsSummary
import com.adsamcik.tracker.feature.statistics.api.navigation.StatsSignalReport
import com.adsamcik.tracker.feature.statistics.api.navigation.StatsWifi
import com.adsamcik.tracker.feature.statistics.api.navigation.TripDetail

/**
 * Statistics-related destinations: Stats list, History, and Trip detail.
 */
fun NavGraphBuilder.statsGraph(
    navController: NavHostController,
    getTripDetailFallbackRoute: () -> Any,
    onSetTripDetailFallback: (Any) -> Unit,
    routePreviewRenderer: RoutePreviewRenderer,
) {
    composable<Stats> {
        com.adsamcik.tracker.statistics.fragment.StatsRoute(
            onTripClick = { tripId ->
                onSetTripDetailFallback(Stats)
                navController.navigate(TripDetail(tripId)) {
                    launchSingleTop = true
                }
            },
            onTripViewOnMap = { tripId, startMs, endMs ->
                navController.navigate(MapTripContext(tripId = tripId, startMs = startMs, endMs = endMs)) {
                    launchSingleTop = true
                }
            },
            onNavigateToHistory = {
                navController.navigate(History) {
                    launchSingleTop = true
                }
            },
            onNavigateToTracker = {
                navController.navigate(Dashboard) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onNavigateToSummary = {
                navController.navigate(StatsSummary) {
                    launchSingleTop = true
                }
            },
            onNavigateToWifiStats = {
                navController.navigate(StatsWifi) {
                    launchSingleTop = true
                }
            },
            onNavigateToSignalReport = {
                navController.navigate(StatsSignalReport) {
                    launchSingleTop = true
                }
            },
        )
    }

    composable<StatsSummary> {
        com.adsamcik.tracker.statistics.ui.SummaryRoute(
            onBack = { navController.popBackStack() },
        )
    }

    composable<StatsWifi> {
        com.adsamcik.tracker.statistics.ui.WifiStatsRoute(
            onBack = { navController.popBackStack() },
        )
    }

    composable<StatsSignalReport> {
        com.adsamcik.tracker.statistics.ui.CellSignalReportRoute(
            onBack = { navController.popBackStack() },
        )
    }

    composable<History> {
        com.adsamcik.tracker.statistics.ui.HistoryRoute(
            onBack = { navController.popBackStack() },
            onNavigateToTripDetail = { tripId ->
                onSetTripDetailFallback(Stats)
                navController.navigate(TripDetail(tripId)) {
                    launchSingleTop = true
                }
            },
            onNavigateToMap = { tripId, startMs, endMs ->
                navController.navigate(MapTripContext(tripId = tripId, startMs = startMs, endMs = endMs)) {
                    launchSingleTop = true
                }
            },
        )
    }

    composable<TripDetail> { backStackEntry ->
        val route = backStackEntry.toRoute<TripDetail>()
        com.adsamcik.tracker.statistics.ui.TripDetailRoute(
            tripId = route.tripId,
            routePreviewRenderer = routePreviewRenderer,
            onBack = {
                if (!navController.popBackStack()) {
                    // Keep the start destination on the stack so hardware BACK still pops
                    // through Dashboard before exiting the app.
                    navController.navigate(getTripDetailFallbackRoute()) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            onViewOnMap = { tripId, startMs, endMs ->
                navController.navigate(MapTripContext(tripId = tripId, startMs = startMs, endMs = endMs)) {
                    launchSingleTop = true
                }
            },
        )
    }
}




