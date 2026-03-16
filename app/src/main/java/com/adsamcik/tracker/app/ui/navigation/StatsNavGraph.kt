package com.adsamcik.tracker.app.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute

/**
 * Statistics-related destinations: Stats list, History, and Trip detail.
 */
internal fun NavGraphBuilder.statsGraph(
    navController: NavHostController,
    getTripDetailFallbackRoute: () -> AppRoute,
    onSetTripDetailFallback: (AppRoute) -> Unit,
) {
    composable<Stats> {
        com.adsamcik.tracker.statistics.fragment.StatsRoute(
            onTripClick = { tripId ->
                onSetTripDetailFallback(Stats)
                navController.navigate(TripDetail(tripId)) {
                    launchSingleTop = true
                }
            },
            onTripViewOnMap = {
                navController.navigate(Map) {
                    launchSingleTop = true
                    restoreState = true
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
        )
    }

    composable<History> {
        com.adsamcik.tracker.statistics.ui.HistoryRoute(
            onNavigateToTripDetail = { tripId ->
                onSetTripDetailFallback(Stats)
                navController.navigate(TripDetail(tripId)) {
                    launchSingleTop = true
                }
            },
        )
    }

    composable<TripDetail> { backStackEntry ->
        val route = backStackEntry.toRoute<TripDetail>()
        com.adsamcik.tracker.statistics.ui.TripDetailRoute(
            tripId = route.tripId,
            onBack = {
                if (!navController.popBackStack()) {
                    navController.navigate(getTripDetailFallbackRoute()) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            },
        )
    }
}
