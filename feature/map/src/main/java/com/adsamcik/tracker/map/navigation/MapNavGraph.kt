package com.adsamcik.tracker.map.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.adsamcik.tracker.feature.map.api.navigation.Map
import com.adsamcik.tracker.feature.map.api.navigation.MapTripContext
import com.adsamcik.tracker.map.ui.MapRoute
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions

/**
 * Map destination within the app navigation graph.
 */
fun NavGraphBuilder.mapGraph(useSideRail: Boolean) {
    composable<Map> {
        val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        MapRoute(
            contentPadding = if (useSideRail) {
                PaddingValues()
            } else {
                PaddingValues(bottom = AppDimensions.FloatingNavBarReserve + navBarPad)
            },
        )
    }

    composable<MapTripContext> {
        val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        MapRoute(
            contentPadding = if (useSideRail) {
                PaddingValues()
            } else {
                PaddingValues(bottom = AppDimensions.FloatingNavBarReserve + navBarPad)
            },
        )
    }
}


