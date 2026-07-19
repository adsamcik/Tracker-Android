package com.adsamcik.tracker.game.ui.compose

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Adaptive play-grid decision: two columns only when both width and font scale
 * are comfortable, single column otherwise.
 */
class MiniGamePlaySectionTest {

	@Test
	fun comfortableWidthAndFontUsesTwoColumns() {
		shouldUseSingleColumn(screenWidthDp = 411, fontScale = 1.0f) shouldBe false
	}

	@Test
	fun narrowWidthCollapsesToSingleColumn() {
		shouldUseSingleColumn(screenWidthDp = 320, fontScale = 1.0f) shouldBe true
	}

	@Test
	fun largeFontScaleCollapsesToSingleColumn() {
		shouldUseSingleColumn(screenWidthDp = 411, fontScale = 1.5f) shouldBe true
	}

	@Test
	fun boundaryValuesStayTwoColumns() {
		// 360dp and 1.3x are the inclusive comfortable limits.
		shouldUseSingleColumn(screenWidthDp = 360, fontScale = 1.3f) shouldBe false
	}

	@Test
	fun justBelowBoundaryCollapses() {
		shouldUseSingleColumn(screenWidthDp = 359, fontScale = 1.0f) shouldBe true
		shouldUseSingleColumn(screenWidthDp = 360, fontScale = 1.31f) shouldBe true
	}
}
