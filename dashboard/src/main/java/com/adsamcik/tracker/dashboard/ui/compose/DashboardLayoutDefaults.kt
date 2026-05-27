package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout

internal object DashboardLayoutDefaults {
	/** Height of the tracking pill (96dp) plus the floating-action margin (16dp). */
	val PillClearance = 112.dp
	private val LandscapeBottomBarClearance = 96.dp
	private val FloatingActionMargin = 16.dp

	/**
	 * Trailing inset for the pill at [androidx.compose.ui.Alignment.BottomEnd].
	 * Keeps the pill clear of the screen edge so the elevation shadow reads cleanly.
	 */
	val FloatingActionEndMargin = FloatingActionMargin

	fun contentBottomClearance(
		navigationLayout: MainNavigationLayout,
		bottomInset: Dp = 0.dp,
		isLandscape: Boolean = false,
	): Dp = when (navigationLayout) {
		MainNavigationLayout.SideRail -> PillClearance + bottomInset
		else -> {
			val navigationClearance = if (isLandscape) {
				LandscapeBottomBarClearance
			} else {
				AppDimensions.FloatingNavBarClearance
			}
			// Reserve space for BOTH the floating nav bar *and* the TrackingPill that sits
			// above it, so cards in the scrollable list never slide up behind the pill.
			navigationClearance + PillClearance + bottomInset
		}
	}

	/**
	 * Bottom padding for the floating tracking pill when it is rendered as an overlay
	 * inside the dashboard surface.
	 *
	 * The outer `MainRoot` already reserves `bottomPadding = 96.dp + navBarInset` at the
	 * NavHost level for the floating navigation bar, so the dashboard surface ends just
	 * above that bar. The pill only needs a small margin from the bottom edge of that
	 * surface — adding `FloatingNavBarClearance` here would double-count the reservation
	 * and push the pill halfway up the screen.
	 *
	 * In landscape the bottom bar is shorter and the outer reservation matches, so the
	 * same margin applies. In SideRail layouts the nav rail is on the side rather than
	 * the bottom, so the pill anchors directly above the system nav inset (which the
	 * NavHost padding does not reserve in that mode).
	 */
	@Suppress("UnusedParameter")
	fun floatingActionBottomPadding(
		bottomInset: Dp = 0.dp,
		isLandscape: Boolean = false,
		navigationLayout: MainNavigationLayout = MainNavigationLayout.BottomBar,
	): Dp = when (navigationLayout) {
		MainNavigationLayout.SideRail -> FloatingActionMargin + bottomInset
		// Outer NavHost padding already accounts for the floating nav bar (96dp +
		// systemNavInset). The dashboard's overlay Box ends just above that bar, so
		// we only need a small margin to sit cleanly above the bar instead of
		// touching it. Landscape uses the same value since the outer reservation in
		// landscape also collapses to the bar's footprint.
		else -> FloatingActionMargin
	}
}
