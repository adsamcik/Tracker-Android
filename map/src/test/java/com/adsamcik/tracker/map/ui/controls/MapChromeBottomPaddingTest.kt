package com.adsamcik.tracker.map.ui.controls

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Map chrome bottom padding")
class MapChromeBottomPaddingTest {

	@Test
	fun `IME closed returns just the gap`() {
		resolveMapChromeBottomPaddingPx(
			bottomInsetPx = 360,
			imeBottomPx = 0,
			gapPx = 12,
		) shouldBe 12
	}

	@Test
	fun `IME shorter than caller inset returns just the gap`() {
		resolveMapChromeBottomPaddingPx(
			bottomInsetPx = 360,
			imeBottomPx = 240,
			gapPx = 12,
		) shouldBe 12
	}

	@Test
	fun `IME taller than caller inset returns overflow plus gap`() {
		resolveMapChromeBottomPaddingPx(
			bottomInsetPx = 360,
			imeBottomPx = 900,
			gapPx = 12,
		) shouldBe 552
	}

	@Test
	fun `negative IME overflow is coerced to zero`() {
		resolveMapChromeBottomPaddingPx(
			bottomInsetPx = -10,
			imeBottomPx = -20,
			gapPx = 12,
		) shouldBe 12
	}

	@Test
	fun `zero gap returns only positive IME overflow`() {
		resolveMapChromeBottomPaddingPx(
			bottomInsetPx = 360,
			imeBottomPx = 900,
			gapPx = 0,
		) shouldBe 540
	}

	@Test
	fun `realistic xxhdpi keyboard returns positive overflow above bottom chrome`() {
		resolveMapChromeBottomPaddingPx(
			bottomInsetPx = 360,
			imeBottomPx = 2300,
			gapPx = 12,
		) shouldBe 1952
	}
}
