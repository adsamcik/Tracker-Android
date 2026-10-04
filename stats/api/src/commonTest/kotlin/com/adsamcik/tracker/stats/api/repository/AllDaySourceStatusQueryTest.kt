package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AllDaySourceStatusQueryTest {
	@Test
	fun sourceRequestsPreserveStoredCivilDayAndBoundedReadingWindow() {
		val day = AmbientStepsStructuralDay(20_541L, "Europe/Prague")
		val steps = AllDaySourceStatusRequest.Steps(AmbientStepsHistoryRangeRequest(listOf(day)))
		assertEquals(TrackingSource.STEPS, steps.source)
		assertEquals(listOf(day), steps.range.days)
		val window = HistoryObservationWindow(1_000L, 2_000L)
		assertEquals(window, AllDaySourceStatusRequest.Wifi(window, 1).window)
		assertEquals(window, AllDaySourceStatusRequest.Cell(window, 4_096).window)
		assertEquals(window, AllDaySourceStatusRequest.Location(window).window)
	}

	@Test
	fun finiteSourceRequestsRejectUnboundedLimits() {
		val window = HistoryObservationWindow(1_000L, 2_000L)
		for (limit in listOf(0, 4_097)) {
			assertFailsWith<IllegalArgumentException> { AllDaySourceStatusRequest.Wifi(window, limit) }
			assertFailsWith<IllegalArgumentException> { AllDaySourceStatusRequest.Cell(window, limit) }
			assertFailsWith<IllegalArgumentException> { AllDaySourceStatusRequest.Location(window, limit) }
		}
	}

	@Test
	fun unsupportedSessionlessSourcesStayExplicit() {
		assertEquals(TrackingSource.ACTIVITY, AllDaySourceStatusRequest.Unsupported(TrackingSource.ACTIVITY).source)
		assertEquals(TrackingSource.PRESSURE, AllDaySourceStatusRequest.Unsupported(TrackingSource.PRESSURE).source)
		assertFailsWith<IllegalArgumentException> { AllDaySourceStatusRequest.Unsupported(TrackingSource.STEPS) }
	}
}
