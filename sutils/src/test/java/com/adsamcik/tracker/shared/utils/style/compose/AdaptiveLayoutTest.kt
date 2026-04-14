package com.adsamcik.tracker.shared.utils.style.compose

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("AdaptiveLayout")
class AdaptiveLayoutTest {

	@Nested
	@DisplayName("MainNavigationLayout enum")
	inner class MainNavigationLayoutEnumTest {

		@Test
		fun `has BottomBar value`() {
			MainNavigationLayout.valueOf("BottomBar") shouldBe MainNavigationLayout.BottomBar
		}

		@Test
		fun `has SideRail value`() {
			MainNavigationLayout.valueOf("SideRail") shouldBe MainNavigationLayout.SideRail
		}

		@Test
		fun `has exactly 2 entries`() {
			MainNavigationLayout.entries.size shouldBe 2
		}

		@Test
		fun `entries contain BottomBar and SideRail`() {
			val entries = MainNavigationLayout.entries
			entries.contains(MainNavigationLayout.BottomBar) shouldBe true
			entries.contains(MainNavigationLayout.SideRail) shouldBe true
		}

		@Test
		fun `ordinal values are sequential`() {
			MainNavigationLayout.BottomBar.ordinal shouldBe 0
			MainNavigationLayout.SideRail.ordinal shouldBe 1
		}
	}
}
