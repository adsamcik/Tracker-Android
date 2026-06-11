package com.adsamcik.tracker.stats.api.threshold

import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ActivityTypeMappingTest {

	@Test
	fun `known play services codes map to expected domain activities`() {
		ActivityTypeMapping.fromPlayServicesCode(0) shouldBe DetectedActivityType.IN_VEHICLE
		ActivityTypeMapping.fromPlayServicesCode(1) shouldBe DetectedActivityType.ON_BICYCLE
		ActivityTypeMapping.fromPlayServicesCode(2) shouldBe DetectedActivityType.ON_FOOT
		ActivityTypeMapping.fromPlayServicesCode(3) shouldBe DetectedActivityType.STILL
		ActivityTypeMapping.fromPlayServicesCode(5) shouldBe DetectedActivityType.TILTING
		ActivityTypeMapping.fromPlayServicesCode(7) shouldBe DetectedActivityType.WALKING
		ActivityTypeMapping.fromPlayServicesCode(8) shouldBe DetectedActivityType.RUNNING
	}

	@Test
	fun `unknown play services codes map to UNKNOWN`() {
		ActivityTypeMapping.fromPlayServicesCode(-1) shouldBe DetectedActivityType.UNKNOWN
		ActivityTypeMapping.fromPlayServicesCode(999) shouldBe DetectedActivityType.UNKNOWN
	}

	@Test
	fun `to from mapping round trips for canonical activity codes`() {
		val canonical = listOf(
			DetectedActivityType.IN_VEHICLE,
			DetectedActivityType.ON_BICYCLE,
			DetectedActivityType.ON_FOOT,
			DetectedActivityType.STILL,
			DetectedActivityType.TILTING,
			DetectedActivityType.WALKING,
			DetectedActivityType.RUNNING,
		)

		canonical.forEach { activity ->
			val code = ActivityTypeMapping.toPlayServicesCode(activity)
			ActivityTypeMapping.fromPlayServicesCode(code) shouldBe activity
		}
		ActivityTypeMapping.toPlayServicesCode(DetectedActivityType.UNKNOWN) shouldBe 4
	}
}
