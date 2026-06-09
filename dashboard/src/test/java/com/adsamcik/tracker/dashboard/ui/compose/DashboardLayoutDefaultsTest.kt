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
		fun `BottomBar portrait clears the floating bar plus tracking pill plus margin plus inset`() {
			val result = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				bottomInset = 0.dp,
				isLandscape = false,
			)
			// Content flows behind the floating bar, so the dashboard reserves the bar
			// footprint (96dp) + the tracking pill (PillClearance = 112dp) + a visual
			// margin (28dp) + the system inset.
			val expected = AppDimensions.FloatingNavBarReserve +
				DashboardLayoutDefaults.PillClearance + 28.dp
			result shouldBe expected
		}

		@Test
		fun `BottomBar landscape matches portrait`() {
			val result = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				bottomInset = 10.dp,
				isLandscape = true,
			)
			// The floating bar footprint is the same in portrait and landscape, so the
			// dashboard's local reservation no longer branches on orientation.
			val expected = AppDimensions.FloatingNavBarReserve +
				DashboardLayoutDefaults.PillClearance + 28.dp + 10.dp
			result shouldBe expected
		}

		@Test
		fun `BottomBar portrait and landscape agree for the same inset`() {
			val portrait = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				bottomInset = 24.dp,
				isLandscape = false,
			)
			val landscape = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				bottomInset = 24.dp,
				isLandscape = true,
			)
			portrait shouldBe landscape
		}
	}

	@Nested
	@DisplayName("floatingActionBottomPadding")
	inner class FloatingActionTest {
		@Test
		fun `SideRail returns floating action margin plus inset`() {
			val result = DashboardLayoutDefaults.floatingActionBottomPadding(
				bottomInset = 8.dp,
				navigationLayout = MainNavigationLayout.SideRail,
			)
			// In SideRail layouts the nav rail is on the side, not bottom, so the pill
			// only needs to clear the system nav inset.
			result shouldBe 16.dp + 8.dp
		}

		@Test
		fun `BottomBar portrait reserves the bar footprint plus floating action margin plus inset`() {
			val result = DashboardLayoutDefaults.floatingActionBottomPadding(
				bottomInset = 8.dp,
				isLandscape = false,
			)
			// Content flows behind the floating bar, so the overlay pill reserves the
			// bar footprint (96dp) + system inset (8dp) + the small margin (16dp).
			result shouldBe AppDimensions.FloatingNavBarReserve + 8.dp + 16.dp
		}

		@Test
		fun `BottomBar landscape reserves the bar footprint plus floating action margin plus inset`() {
			val result = DashboardLayoutDefaults.floatingActionBottomPadding(
				bottomInset = 8.dp,
				isLandscape = true,
			)
			result shouldBe AppDimensions.FloatingNavBarReserve + 8.dp + 16.dp
		}
	}
}

