package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout
import io.kotest.matchers.shouldBe
import org.junit.Test

class DashboardLayoutDefaultsTest {

	@Test
	fun contentBottomClearance_reservesPillSpaceForBottomNavigationLayout() {
		DashboardLayoutDefaults.contentBottomClearance(MainNavigationLayout.BottomBar) shouldBe 120.dp
	}

	@Test
	fun contentBottomClearance_reservesPillSpaceForSideRailLayout() {
		DashboardLayoutDefaults.contentBottomClearance(MainNavigationLayout.SideRail) shouldBe 64.dp
	}

	@Test
	fun contentBottomClearance_usesReducedLandscapeBottomBarClearancePlusInset() {
		DashboardLayoutDefaults.contentBottomClearance(
			navigationLayout = MainNavigationLayout.BottomBar,
			bottomInset = 24.dp,
			isLandscape = true,
		) shouldBe 120.dp
	}
}
