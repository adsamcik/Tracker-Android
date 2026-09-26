package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.coordinator.adaptiveLocationPlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationHistoricalProfileAgeTest {
	private val codec = SourcePlanCodec()
	@Test
	fun `stored adaptive profile carries useful evidence age independently of cadence`() {
		val plan = adaptiveLocationPlan(5, 1L, LocationBackend.FUSED, true)
		val restored = codec.decode(codec.encode(plan).bytes) as LocationPlan
		assertEquals(120_000_000_000L, restored.maximumLocationEvidenceAgeNanos())
		assertEquals(1_000L, restored.requestedIntervalMs)
	}

	@Test
	fun `historical legacy responsive age remains exact and is not expanded by new profile`() {
		val legacy = adaptiveLocationPlan(5, 1L, LocationBackend.FUSED, true).copy(adaptiveProfileVersion = null)
		val restored = codec.decode(codec.encode(legacy).bytes) as LocationPlan
		assertEquals(2_000_000_000L, restored.maximumLocationEvidenceAgeNanos())
	}
}
