package com.adsamcik.tracker.shared.base.extension

import com.adsamcik.tracker.shared.base.extension.LocationExtensions.interpolateTimestampMillis
import io.kotest.matchers.longs.shouldBeExactly
import org.junit.Test

class LocationExtensionsTest {

	@Test
	fun `timestamp interpolation uses the delta between endpoints`() {
		interpolateTimestampMillis(fromTimeMs = 100L, toTimeMs = 200L, delta = 0.5)
			.shouldBeExactly(150L)
	}

	@Test
	fun `timestamp interpolation preserves endpoints`() {
		interpolateTimestampMillis(fromTimeMs = 100L, toTimeMs = 200L, delta = 0.0)
			.shouldBeExactly(100L)
		interpolateTimestampMillis(fromTimeMs = 100L, toTimeMs = 200L, delta = 1.0)
			.shouldBeExactly(200L)
	}
}
