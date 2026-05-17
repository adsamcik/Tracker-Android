package com.adsamcik.tracker.app.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Map destination within the app navigation graph.
 */
internal fun NavGraphBuilder.mapGraph(useSideRail: Boolean) {
    composable<Map> {
        val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        com.adsamcik.tracker.map.ui.MapRoute(
            contentPadding = if (useSideRail) {
                PaddingValues()
            } else {
                PaddingValues(bottom = 96.dp + navBarPad)
            },
        )
    }

    composable<MapTripContext> {
        val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        com.adsamcik.tracker.map.ui.MapRoute(
            contentPadding = if (useSideRail) {
                PaddingValues()
            } else {
                PaddingValues(bottom = 96.dp + navBarPad)
            },
        )
    }
}
