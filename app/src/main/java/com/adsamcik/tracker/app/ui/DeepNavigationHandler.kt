package com.adsamcik.tracker.app.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.adsamcik.tracker.app.activity.DeepNavigationRequest
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.ui.navigation.AppRoute
import com.adsamcik.tracker.app.ui.navigation.Dashboard
import com.adsamcik.tracker.app.ui.navigation.Game
import com.adsamcik.tracker.app.ui.navigation.Settings
import com.adsamcik.tracker.app.ui.navigation.Stats
import com.adsamcik.tracker.app.ui.navigation.toSettingsOrigin

/**
 * Handles incoming deep navigation requests by dispatching to the
 * correct route on the provided [navController].
 */
@Composable
internal fun DeepNavigationHandler(
    deepNavigationRequest: DeepNavigationRequest?,
    navController: NavHostController,
    lastTopLevelRoute: AppRoute,
    onSettingsLaunchNonceIncrement: () -> Long,
    onDeepNavigationHandled: () -> Unit,
) {
    LaunchedEffect(deepNavigationRequest) {
        val request = deepNavigationRequest ?: return@LaunchedEffect

        when (request.target) {
            MainActivityCompose.TARGET_IMPEXP -> {
                val nonce = onSettingsLaunchNonceIncrement()
                navController.navigate(
                    Settings(
                        origin = lastTopLevelRoute.toSettingsOrigin(),
                        nonce = nonce,
                    ),
                ) {
                    launchSingleTop = true
                }
            }

            MainActivityCompose.TARGET_GAME -> {
                navController.navigate(Game) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            MainActivityCompose.TARGET_DASHBOARD -> {
                if (request.scrollTo == "goals") {
                    Log.d("MainRoot", "Deep link requested dashboard goals section")
                }
                navController.navigate(Dashboard) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            MainActivityCompose.TARGET_STATS -> {
                navController.navigate(Stats) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }

        onDeepNavigationHandled()
    }
}
