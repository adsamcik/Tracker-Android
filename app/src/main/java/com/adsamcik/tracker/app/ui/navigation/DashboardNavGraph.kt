package com.adsamcik.tracker.app.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.adsamcik.tracker.feature.map.api.navigation.Map
import com.adsamcik.tracker.feature.statistics.api.navigation.TripDetail

/**
 * Dashboard destination within the app navigation graph.
 */
internal fun NavGraphBuilder.dashboardGraph(
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
            // No extra contentPadding: the DashboardScreen's Scaffold declares
            // contentWindowInsets = WindowInsets(0) and the internal LazyColumn content applies
            // its own nav-bar clearance via DashboardLayoutDefaults.contentBottomClearance.
            // Injecting another 144dp here would shove the TrackingPill into the middle of the
            // card stack and prevent the surface from reaching the bottom of the screen.
            contentPadding = PaddingValues(),
        )
    }
}



