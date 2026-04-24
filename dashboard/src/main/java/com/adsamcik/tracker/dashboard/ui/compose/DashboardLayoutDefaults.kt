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

	fun floatingActionBottomPadding(
		bottomInset: Dp = 0.dp,
		isLandscape: Boolean = false,
		navigationLayout: MainNavigationLayout = MainNavigationLayout.BottomBar,
	): Dp = when {
		isLandscape -> LandscapeBottomBarClearance + bottomInset
		navigationLayout == MainNavigationLayout.SideRail -> FloatingActionMargin + bottomInset
		// Pill anchors to screen bottom (Scaffold uses contentWindowInsets = WindowInsets(0)
		// so its content Box extends below the system nav bar). Stack: system nav inset
		// (bottomInset) + floating nav-bar clearance (120dp) + margin above it (16dp).
		else -> AppDimensions.FloatingNavBarClearance + FloatingActionMargin + bottomInset
	}
}
