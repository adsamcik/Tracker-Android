package com.adsamcik.tracker.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.adsamcik.tracker.app.onboarding.ui.SetupRoute
import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard

/**
 * First-time setup destination within the app navigation graph.
 */
internal fun NavGraphBuilder.setupGraph(
    navController: NavHostController,
    showOnboardingReadError: Boolean = false,
    onSetupComplete: () -> Unit = {},
) {
    composable<Setup> {
        SetupRoute(
            onSetupComplete = {
                onSetupComplete()
                navController.navigate(Dashboard) {
                    popUpTo<Setup> { inclusive = true }
                    launchSingleTop = true
                }
            },
            showOnboardingReadError = showOnboardingReadError,
        )
    }
}

