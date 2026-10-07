package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertFailsWith

class StepsDailyDisplayContractTest {
	@Test
	fun `display cannot silently omit a requested middle day`() {
		assertFailsWith<IllegalArgumentException> {
			StepsDailyDisplay.Snapshot(1L, listOf(
				StepsDailyDisplayDay(0L, StepsDailyDisplayValue.Complete(3L)),
				StepsDailyDisplayDay(2L, StepsDailyDisplayValue.Complete(4L)),
			))
		}
	}

	@Test
	fun `a recorded subtotal requires a real incomplete-coverage cause`() {
		assertFailsWith<IllegalArgumentException> { StepsDailyDisplayValue.Recorded(42L, emptySet()) }
		assertFailsWith<IllegalArgumentException> { StepsDailyDisplayValue.Recorded(-1L,
			setOf(AmbientStepsHistoryCause.EXPLICIT_GAP)) }
	}
}
