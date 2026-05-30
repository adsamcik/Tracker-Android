package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.ui.unit.dp
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
		fun `BottomBar portrait clears the tracking pill plus visual margin plus inset`() {
			val result = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				bottomInset = 0.dp,
				isLandscape = false,
			)
			// MainRoot reserves the floating nav bar (96dp + navBarInset) at the NavHost
			// level — DashboardLayoutDefaults only needs to reserve the tracking pill
			// (PillClearance = 112dp) plus a visual margin (28dp matching
			// bottomNavSafeClearance) plus the system inset (defense in depth).
			val expected = DashboardLayoutDefaults.PillClearance + 28.dp
			result shouldBe expected
		}

		@Test
		fun `BottomBar landscape matches portrait`() {
			val result = DashboardLayoutDefaults.contentBottomClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				bottomInset = 10.dp,
				isLandscape = true,
			)
			// MainRoot's NavHost reservation is the same in portrait and landscape
			// (96.dp + navBarInset), so the dashboard's local reservation no longer
			// branches on orientation.
			val expected = DashboardLayoutDefaults.PillClearance + 28.dp + 10.dp
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
		fun `BottomBar portrait returns the floating action margin`() {
			val result = DashboardLayoutDefaults.floatingActionBottomPadding(
				bottomInset = 8.dp,
				isLandscape = false,
			)
			// The outer MainRoot NavHost already reserves the floating nav bar +
			// system nav inset, so the overlay pill only needs the small margin
			// from the bottom edge of the dashboard surface.
			result shouldBe 16.dp
		}

		@Test
		fun `BottomBar landscape returns the floating action margin`() {
			val result = DashboardLayoutDefaults.floatingActionBottomPadding(
				bottomInset = 8.dp,
				isLandscape = true,
			)
			result shouldBe 16.dp
		}
	}
}

