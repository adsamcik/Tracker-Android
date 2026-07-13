package com.adsamcik.tracker.map.export

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MapSnapshotRendererTest {

	@Test
	fun `snapshot symbol text respects system font scaling`() {
		MapSnapshotRenderer.snapshotTextSizePx(textSizeSp = 12f, fontScale = 1.5f) shouldBe 18f
	}
}
