package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout

internal object DashboardLayoutDefaults {
	/** Height of the tracking pill (96dp) plus the floating-action margin (16dp). */
	val PillClearance = 112.dp
	private val FloatingActionMargin = 16.dp

	/**
	 * Visual breathing space between the last item in the dashboard's scrollable
	 * area and the top edge of the tracking pill. Matches the value used by
	 * `bottomNavSafeClearance` for plain top-level screens (Game, Stats) so the
	 * dashboard's stack-aware variant is consistent with the rest of the app.
	 */
	private val TrackingPillTopMargin = 28.dp

	/**
	 * Trailing inset for the pill at [androidx.compose.ui.Alignment.BottomEnd].
	 * Keeps the pill clear of the screen edge so the elevation shadow reads cleanly.
	 */
	val FloatingActionEndMargin = FloatingActionMargin

	/**
	 * Bottom `contentPadding` for the dashboard's scrollable area, sized so that
	 * the last card rests above the floating tracking pill (which sits above the
	 * floating navigation bar in [MainNavigationLayout.BottomBar] mode), while the
	 * rest of the content flows edge-to-edge *behind* the floating bar.
	 *
	 * # What this reserves (BottomBar)
	 *
	 * - [AppDimensions.FloatingNavBarReserve] (`96.dp`): the floating navigation
	 *   bar's footprint. `MainRoot` no longer pads the `NavHost`, so the dashboard
	 *   reserves the bar itself.
	 * - [PillClearance] (`112.dp = 96.dp pill height + 16.dp floating-action margin`):
	 *   the tracking pill that floats above the nav bar.
	 * - [TrackingPillTopMargin] (`28.dp`): visual breathing room above the pill.
	 * - `bottomInset`: the system bottom inset (gesture handle, soft nav bar).
	 *
	 * The dashboard differs from Game/Stats because of the floating tracking pill
	 * that sits above the nav pill — that's why this function adds [PillClearance]
	 * on top of what `bottomNavSafeClearance` (used by Game/Stats) reserves.
	 *
	 * @param navigationLayout current top-level navigation layout.
	 * @param bottomInset value reported by
	 *   `WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()`
	 *   (or the larger of safeDrawing and navigationBars insets, as DashboardScreen
	 *   currently passes).
	 * @param isLandscape kept for call-site compatibility; the formula is the
	 *   same in portrait and landscape because the floating bar footprint is the
	 *   same in both orientations.
	 */
	@Suppress("UnusedParameter")
	fun contentBottomClearance(
		navigationLayout: MainNavigationLayout,
		bottomInset: Dp = 0.dp,
		isLandscape: Boolean = false,
	): Dp = when (navigationLayout) {
		MainNavigationLayout.SideRail -> PillClearance + bottomInset
		// BottomBar (portrait or landscape): content flows behind the floating nav
		// bar, so the dashboard reserves the bar footprint itself plus the tracking
		// pill that floats above it, a visual margin, and the system inset.
		MainNavigationLayout.BottomBar ->
			AppDimensions.FloatingNavBarReserve + PillClearance + TrackingPillTopMargin + bottomInset
	}

	/**
	 * Bottom padding for the floating tracking pill when it is rendered as an overlay
	 * inside the dashboard surface.
	 *
	 * `MainRoot` no longer pads the `NavHost` — content flows edge-to-edge behind the
	 * floating navigation bar — so the pill reserves the bar footprint
	 * ([AppDimensions.FloatingNavBarReserve]) plus the system bottom inset plus a small
	 * margin so it floats just above the bar.
	 *
	 * In SideRail layouts the nav rail is on the side rather than the bottom, so the
	 * pill anchors directly above the system nav inset only.
	 */
	@Suppress("UnusedParameter")
	fun floatingActionBottomPadding(
		bottomInset: Dp = 0.dp,
		isLandscape: Boolean = false,
		navigationLayout: MainNavigationLayout = MainNavigationLayout.BottomBar,
	): Dp = when (navigationLayout) {
		MainNavigationLayout.SideRail -> FloatingActionMargin + bottomInset
		// Content flows behind the floating nav bar, so the overlay pill reserves the
		// bar footprint + system inset + a small margin to float just above the bar.
		else -> AppDimensions.FloatingNavBarReserve + FloatingActionMargin + bottomInset
	}
}

