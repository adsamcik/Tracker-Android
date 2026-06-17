package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Maximum readable width for single-column detail/content surfaces. On large
 * screens (and forced-landscape under API 37) capping line length keeps content
 * legible instead of stretching edge-to-edge. A no-op on compact widths.
 */
val DetailContentMaxWidth: Dp = 640.dp

enum class MainNavigationLayout {
    BottomBar,
    SideRail,
}

@Composable
fun rememberMainNavigationLayout(): MainNavigationLayout {
    val configuration = LocalConfiguration.current
    val supportsExpandedPaneLayout = configuration.smallestScreenWidthDp >= 600
    return if (supportsExpandedPaneLayout) {
        MainNavigationLayout.SideRail
    } else {
        MainNavigationLayout.BottomBar
    }
}

@Composable
fun rememberContentColumnCount(): Int {
    val configuration = LocalConfiguration.current
    return if (configuration.smallestScreenWidthDp >= 600) 2 else 1
}
