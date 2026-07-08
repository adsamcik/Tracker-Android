package com.adsamcik.tracker.map.export

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MapShareResolutionTest {

	@Nested
	inner class ResolveOutputSizePx {

		@Test
		fun `square aspect ratio produces square output`() {
			val size = MapShareResolution.STANDARD.resolveOutputSizePx(1f)
			size.widthPx shouldBe 1080
			size.heightPx shouldBe 1080
		}

		@Test
		fun `wide aspect ratio keeps width as the long edge`() {
			val size = MapShareResolution.STANDARD.resolveOutputSizePx(2f)
			size.widthPx shouldBe 1080
			size.heightPx shouldBe 540
		}

		@Test
		fun `tall aspect ratio keeps height as the long edge`() {
			val size = MapShareResolution.STANDARD.resolveOutputSizePx(0.5f)
			size.widthPx shouldBe 540
			size.heightPx shouldBe 1080
		}

		@Test
		fun `invalid aspect ratio falls back to square`() {
			MapShareResolution.STANDARD.resolveOutputSizePx(0f).let { size ->
				size.widthPx shouldBe 1080
				size.heightPx shouldBe 1080
			}
			MapShareResolution.STANDARD.resolveOutputSizePx(Float.NaN).let { size ->
				size.widthPx shouldBe 1080
				size.heightPx shouldBe 1080
			}
			MapShareResolution.STANDARD.resolveOutputSizePx(-1f).let { size ->
				size.widthPx shouldBe 1080
				size.heightPx shouldBe 1080
			}
		}

		@Test
		fun `every resolution respects the long edge cap`() {
			MapShareResolution.entries.forEach { resolution ->
				val size = resolution.resolveOutputSizePx(3f)
				maxOf(size.widthPx, size.heightPx) shouldBeLessThanOrEqual MapShareResolution.MAX_LONG_EDGE_PX
			}
		}

		@Test
		fun `ultra long edge matches its preset value under extreme aspect ratios`() {
			val size = MapShareResolution.ULTRA.resolveOutputSizePx(100f)
			size.widthPx shouldBe MapShareResolution.ULTRA.longEdgePx
			size.heightPx shouldBe 38 // 3840 / 100, rounded
		}

		@Test
		fun `output dimensions are never zero`() {
			val size = MapShareResolution.STANDARD.resolveOutputSizePx(0.0001f)
			size.widthPx shouldBeLessThanOrEqual size.heightPx
			(size.widthPx > 0) shouldBe true
			(size.heightPx > 0) shouldBe true
		}
	}
}
