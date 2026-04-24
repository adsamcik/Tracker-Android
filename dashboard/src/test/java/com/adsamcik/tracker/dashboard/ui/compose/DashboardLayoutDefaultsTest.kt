package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DashboardLayoutDefaults")
class DashboardLayoutDefaultsTest {

	@Nested
	@DisplayName("contentBottomClearance")
	inner class ContentBottomClearanceTest {
		@Test
		fun `SideRail returns PillClearance plus bottomInset`() {
			val result = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.SideRail,
				bottomInset = 16.dp,
			)
			result shouldBe DashboardLayoutDefaults.PillClearance + 16.dp
		}

		@Test
		fun `SideRail with zero inset returns PillClearance`() {
			val result = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.SideRail,
			)
			result shouldBe DashboardLayoutDefaults.PillClearance
		}

		@Test
		fun `BottomBar portrait returns max of NavBarClearance and PillClearance plus inset`() {
			val result = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				bottomInset = 0.dp,
				isLandscape = false,
			)
			val expected = maxOf(AppDimensions.FloatingNavBarClearance, DashboardLayoutDefaults.PillClearance)
			result shouldBe expected
		}

		@Test
		fun `BottomBar landscape uses landscape clearance`() {
			val result = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				bottomInset = 10.dp,
				isLandscape = true,
			)
			// LandscapeBottomBarClearance (96.dp) vs PillClearance (112.dp) -> max is 112.dp
			val expected = maxOf(96.dp, DashboardLayoutDefaults.PillClearance) + 10.dp
			result shouldBe expected
		}
	}

	@Nested
	@DisplayName("floatingActionBottomPadding")
	inner class FloatingActionTest {
		@Test
		fun `landscape returns landscape clearance plus inset`() {
			val result = DashboardLayoutDefaults.floatingActionBottomPadding(
				bottomInset = 8.dp,
				isLandscape = true,
			)
			// LandscapeBottomBarClearance = 96.dp
			result shouldBe 96.dp + 8.dp
		}

		@Test
		fun `portrait returns floating action margin plus inset`() {
			val result = DashboardLayoutDefaults.floatingActionBottomPadding(
				bottomInset = 8.dp,
				isLandscape = false,
			)
			// FloatingActionMargin = 16.dp
			result shouldBe 16.dp + 8.dp
		}
	}
}
