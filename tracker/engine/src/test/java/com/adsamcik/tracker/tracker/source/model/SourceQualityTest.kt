package com.adsamcik.tracker.tracker.source.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.Test

class SourceQualityTest {
	@Test
	fun `quality flags have a stable round trip`() {
		val quality = SourceQuality(
			confidence = 0.75f,
			flags = setOf(SourceQualityFlag.BATCHED, SourceQualityFlag.INCOMPLETE_WINDOW),
		)

		val restored = sourceQualityFromStableFlags(quality.toStableFlags(), quality.confidence)

		restored.confidence shouldBe 0.75f
		restored.flags shouldContainExactlyInAnyOrder quality.flags
	}
}

