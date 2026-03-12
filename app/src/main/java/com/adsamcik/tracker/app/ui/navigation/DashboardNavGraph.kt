package com.adsamcik.tracker.app.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/**
 * Dashboard destination within the app navigation graph.
 */
internal fun NavGraphBuilder.dashboardGraph(
    navController: NavHostController,
    useSideRail: Boolean,
    onOpenSettings: () -> Unit,
    onSetTripDetailFallback: (AppRoute) -> Unit,
    onOpenChallenges: () -> Unit,
) {
    composable<Dashboard> {
        val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
            onOpenChallenges = onOpenChallenges,
            onSessionDetailClick = { sessionId ->
                onSetTripDetailFallback(Dashboard)
                navController.navigate(TripDetail(sessionId)) {
                    launchSingleTop = true
                }
            },
            contentPadding = if (useSideRail) {
                PaddingValues()
            } else {
                PaddingValues(bottom = 96.dp + navBarPad)
            },
        )
    }
}
