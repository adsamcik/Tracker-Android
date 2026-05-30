package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Visual breathing space added above the floating navigation pill in
 * [MainNavigationLayout.BottomBar] mode. Matches the gap between the last
 * scrollable item and the pill's top edge.
 */
private val BOTTOM_BAR_VISUAL_MARGIN = 28.dp

/**
 * Visual breathing space added above the system nav inset in
 * [MainNavigationLayout.SideRail] mode, where there is no floating bottom pill.
 */
private val SIDE_RAIL_VISUAL_MARGIN = 24.dp

/**
 * Bottom `contentPadding` a top-level screen's scroll container (typically a
 * `LazyColumn`) should use so its last item clears the floating navigation
 * pill without double-counting the space already reserved upstream.
 *
 * # Why this helper exists
 *
 * `MainRoot.kt` already reserves `96.dp + navBarInset` of bottom padding at
 * the `NavHost` level whenever the floating bottom navigation pill is shown.
 * The pill itself occupies `80dp + 12dp` top breathing padding = 92dp of that
 * area, with a ~4dp residual. So in [MainNavigationLayout.BottomBar] mode a
 * screen only needs a small visual margin above the pill — anything more
 * triple-counts the reservation and produces hundreds of dp of dead space at
 * the bottom of the list (see commit `9d1b1980d` for the historic bug).
 *
 * In [MainNavigationLayout.SideRail] mode there is no floating bottom nav —
 * the rail is on the side — so `MainRoot` does NOT reserve any bottom padding.
 * In that case the screen must consume the system bottom inset itself
 * (gesture handle, soft nav bar) plus a small visual margin.
 *
 * # Usage
 *
 * Callers should prefer the [Composable] overload, which reads
 * [WindowInsets.safeDrawing] automatically. This non-composable overload is
 * provided for unit-testing the layout math with explicit inset values.
 *
 * @param navigationLayout current top-level navigation layout.
 * @param systemBottomInset bottom inset reported by
 *   `WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()`.
 *   Only consulted in [MainNavigationLayout.SideRail] mode; ignored in
 *   [MainNavigationLayout.BottomBar] mode because `MainRoot` already reserves
 *   the system inset along with the floating-pill clearance.
 */
fun bottomNavSafeClearance(
    navigationLayout: MainNavigationLayout,
    systemBottomInset: Dp,
): Dp = when (navigationLayout) {
    MainNavigationLayout.SideRail -> systemBottomInset + SIDE_RAIL_VISUAL_MARGIN
    MainNavigationLayout.BottomBar -> BOTTOM_BAR_VISUAL_MARGIN
}

/**
 * Composable convenience for [bottomNavSafeClearance] that reads the system
 * bottom inset from [WindowInsets.safeDrawing] automatically.
 *
 * @see bottomNavSafeClearance
 */
@Composable
fun bottomNavSafeClearance(navigationLayout: MainNavigationLayout): Dp {
    val systemBottomInset = WindowInsets.safeDrawing
        .asPaddingValues()
        .calculateBottomPadding()
    return bottomNavSafeClearance(navigationLayout, systemBottomInset)
}
