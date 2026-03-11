package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout

internal object DashboardLayoutDefaults {
	/** Height of the tracking pill plus spacing below content. */
	val PillClearance = 64.dp
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
			maxOf(navigationClearance, PillClearance) + bottomInset
		}
	}

	fun floatingActionBottomPadding(bottomInset: Dp = 0.dp): Dp = FloatingActionMargin + bottomInset
}
