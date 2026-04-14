package com.adsamcik.tracker.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.adsamcik.tracker.app.onboarding.ui.SetupRoute

/**
 * First-time setup destination within the app navigation graph.
 */
internal fun NavGraphBuilder.setupGraph(
    navController: NavHostController,
) {
    composable<Setup> {
        SetupRoute(
            onSetupComplete = {
                navController.navigate(Dashboard) {
                    popUpTo<Setup> { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}
