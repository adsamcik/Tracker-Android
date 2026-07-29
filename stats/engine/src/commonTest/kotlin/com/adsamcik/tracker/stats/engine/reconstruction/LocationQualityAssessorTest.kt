package com.adsamcik.tracker.stats.engine.reconstruction

import com.adsamcik.tracker.stats.api.reconstruction.LocationReconstructionObservation
import com.adsamcik.tracker.stats.api.reconstruction.ObservationHealth
import com.adsamcik.tracker.stats.api.reconstruction.ObservationQualityReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationQualityAssessorTest {
	private val assessor = LocationQualityAssessor()

	@Test
	fun weakEvidenceIsRetainedWithLowerInformation() {
		val precise = assessor.assess(observation("precise", horizontalAccuracyM = 5.0))
		val approximate = assessor.assess(
			observation(
				"approximate",
				horizontalAccuracyM = 80.0,
				permissionPrecision = "APPROXIMATE",
			),
		)

		assertEquals(ObservationHealth.DEGRADED, approximate.health)
		assertTrue(approximate.informationWeight > 0.0)
		assertTrue(approximate.informationWeight < precise.informationWeight)
		assertTrue(approximate.horizontalSigmaM >= 500.0)
	}

	@Test
	fun physicallyImpossibleJumpIsRejectedWithoutDeletingEvidence() {
		val first = observation("first", epochMs = 1_000L)
		val jump = observation(
			"jump",
			epochMs = 2_000L,
			latitudeE7 = 510_000_000,
			longitudeE7 = 150_000_000,
		)

		val assessed = assessor.assess(jump, first)

		assertEquals(ObservationHealth.REJECTED, assessed.health)
		assertEquals(0.0, assessed.informationWeight)
		assertTrue(ObservationQualityReason.MOTION_INFEASIBLE in assessed.reasons)
		assertEquals("jump", assessed.observation.sourceId)
	}

	@Test
	fun datelineCrossingUsesShortestLongitudeArc() {
		val first = observation(
			id = "west-edge",
			epochMs = 1_000L,
			latitudeE7 = 0,
			longitudeE7 = 1_799_999_000,
		)
		val crossing = observation(
			id = "east-edge",
			epochMs = 2_000L,
			latitudeE7 = 0,
			longitudeE7 = -1_799_999_000,
		)

		val assessed = assessor.assess(crossing, first)

		assertTrue(ObservationQualityReason.MOTION_INFEASIBLE !in assessed.reasons)
		assertTrue(assessed.informationWeight > 0.0)
	}

	private fun observation(
		id: String,
		epochMs: Long = 0L,
		latitudeE7: Int = 500_000_000,
		longitudeE7: Int = 140_000_000,
		horizontalAccuracyM: Double? = 5.0,
		permissionPrecision: String = "PRECISE",
	) = LocationReconstructionObservation(
		sourceId = id,
		epochMs = epochMs,
		latitudeE7 = latitudeE7,
		longitudeE7 = longitudeE7,
		horizontalAccuracyM = horizontalAccuracyM,
		permissionPrecision = permissionPrecision,
	)
}
