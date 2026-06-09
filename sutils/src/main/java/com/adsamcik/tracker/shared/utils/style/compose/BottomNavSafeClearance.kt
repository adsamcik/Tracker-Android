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
 * `LazyColumn`) should use so its last item rests above the floating navigation
 * pill while the rest of the content flows edge-to-edge *behind* it.
 *
 * # Why this helper exists
 *
 * The floating navigation bar is a true floating overlay — `MainRoot.kt` no
 * longer pads the `NavHost`, so content fills the full height and the bar's
 * Haze blur reveals it underneath. Each top-level screen must therefore reserve
 * the bar's own footprint in [MainNavigationLayout.BottomBar] mode:
 * [AppDimensions.FloatingNavBarReserve] (`96dp` = 72dp bar + 24dp gap) plus the
 * system bottom inset (gesture handle / soft nav bar) plus a small visual margin.
 *
 * In [MainNavigationLayout.SideRail] mode there is no floating bottom nav — the
 * rail is on the side — so the screen only needs the system bottom inset plus a
 * small visual margin.
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
 */
fun bottomNavSafeClearance(
    navigationLayout: MainNavigationLayout,
    systemBottomInset: Dp,
): Dp = when (navigationLayout) {
    MainNavigationLayout.SideRail -> systemBottomInset + SIDE_RAIL_VISUAL_MARGIN
    MainNavigationLayout.BottomBar ->
        AppDimensions.FloatingNavBarReserve + systemBottomInset + BOTTOM_BAR_VISUAL_MARGIN
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
