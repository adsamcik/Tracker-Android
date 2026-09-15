package com.adsamcik.tracker.dashboard.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard
import com.adsamcik.tracker.feature.game.api.navigation.Game
import com.adsamcik.tracker.feature.map.api.navigation.Map
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailHandoff
import com.adsamcik.tracker.feature.statistics.api.navigation.TripDetail

/**
 * Dashboard destination within the app navigation graph.
 */
fun NavGraphBuilder.dashboardGraph(
    navController: NavHostController,
    useSideRail: Boolean,
    onOpenSettings: () -> Unit,
    onSetTripDetailFallback: (Any) -> Unit,
) {
    composable<Dashboard> {
        com.adsamcik.tracker.dashboard.ui.compose.DashboardRoute(
            onOpenSettings = onOpenSettings,
            onOpenMap = {
                navController.navigate(Map) {
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onOpenGame = {
                navController.navigate(Game) {
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onSessionDetailClick = { sessionId ->
                onSetTripDetailFallback(Dashboard)
                navController.navigate(TripDetail(sessionId)) {
                    launchSingleTop = true
                }
            },
            onSourceHistoryDetailClick = { selection ->
                onSetTripDetailFallback(Dashboard)
                val route = SourceHistoryDetailHandoff.register(selection)
                try {
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                } catch (error: RuntimeException) {
                    SourceHistoryDetailHandoff.release(route.selectionToken)
                    throw error
                }
            },
            // No extra contentPadding: the DashboardScreen's Scaffold declares
            // contentWindowInsets = WindowInsets(0) and the internal LazyColumn content applies
            // its own nav-bar clearance via DashboardLayoutDefaults.contentBottomClearance.
            // Injecting another 144dp here would shove the TrackingPill into the middle of the
            // card stack and prevent the surface from reaching the bottom of the screen.
            contentPadding = PaddingValues(),
        )
    }
}



