package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

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
