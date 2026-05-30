package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("bottomNavSafeClearance")
class BottomNavSafeClearanceTest {

	@Nested
	@DisplayName("BottomBar layout")
	inner class BottomBarLayoutTest {

		@Test
		fun `returns the constant visual margin when inset is zero`() {
			val result = bottomNavSafeClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				systemBottomInset = 0.dp,
			)
			result shouldBe 28.dp
		}

		@Test
		fun `ignores system bottom inset because MainRoot already reserves it`() {
			// MainRoot's `96.dp + navBarInset` NavHost reservation already covers
			// the system inset, so adding it here would double-count.
			val result = bottomNavSafeClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				systemBottomInset = 48.dp,
			)
			result shouldBe 28.dp
		}

		@Test
		fun `gesture-nav inset is also ignored`() {
			val result = bottomNavSafeClearance(
				navigationLayout = MainNavigationLayout.BottomBar,
				systemBottomInset = 24.dp,
			)
			result shouldBe 28.dp
		}
	}

	@Nested
	@DisplayName("SideRail layout")
	inner class SideRailLayoutTest {

		@Test
		fun `consumes system bottom inset plus the side-rail margin`() {
			val result = bottomNavSafeClearance(
				navigationLayout = MainNavigationLayout.SideRail,
				systemBottomInset = 16.dp,
			)
			result shouldBe 40.dp // 16 + 24
		}

		@Test
		fun `returns just the side-rail margin when no inset is present`() {
			val result = bottomNavSafeClearance(
				navigationLayout = MainNavigationLayout.SideRail,
				systemBottomInset = 0.dp,
			)
			result shouldBe 24.dp
		}

		@Test
		fun `handles 3-button nav inset`() {
			val result = bottomNavSafeClearance(
				navigationLayout = MainNavigationLayout.SideRail,
				systemBottomInset = 48.dp,
			)
			result shouldBe 72.dp // 48 + 24
		}
	}
}
