package com.adsamcik.tracker.stats.api.value

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StatsValueClassesTest {

	@Test
	fun `distance supports equality arithmetic and coercion`() {
		val a = DistanceM(12.5f)
		val b = DistanceM(7.5f)

		(a + b) shouldBe DistanceM(20.0f)
		DistanceM.coerced(-10f) shouldBe DistanceM.ZERO
		DistanceM.orNull(-1f) shouldBe null
		DistanceM.orNull(3f) shouldBe DistanceM(3f)
		assertFailsWith<IllegalArgumentException> { DistanceM(-0.1f) }
	}

	@Test
	fun `duration conversion arithmetic and bounds behave correctly`() {
		val duration = DurationMs(1_500L)

		duration.toSeconds() shouldBe (1.5 plusOrMinus 0.0001)
		(DurationMs(500L) + DurationMs(250L)) shouldBe DurationMs(750L)
		DurationMs.fromSeconds(-5.0) shouldBe DurationMs.ZERO
		assertFailsWith<IllegalArgumentException> { DurationMs(-1L) }
	}

	@Test
	fun `speed minus is clamped and nullable factory filters negatives`() {
		(SpeedMps(2f) - SpeedMps(5f)) shouldBe SpeedMps.ZERO
		(SpeedMps(2f) + SpeedMps(1.5f)) shouldBe SpeedMps(3.5f)
		SpeedMps.coerced(-2f) shouldBe SpeedMps.ZERO
		SpeedMps.orNull(-2f) shouldBe null
		assertFailsWith<IllegalArgumentException> { SpeedMps(-0.01f) }
	}

	@Test
	fun `step count and epoch enforce non-negative inputs`() {
		(StepCount(10) + StepCount(5)) shouldBe StepCount(15)
		StepCount.coerced(-4) shouldBe StepCount.ZERO
		(EpochMs(2_000L) - EpochMs(500L)) shouldBe 1_500L
		assertFailsWith<IllegalArgumentException> { StepCount(-1) }
		assertFailsWith<IllegalArgumentException> { EpochMs(-1L) }
	}

	@Test
	fun `activity confidence and coordinate wrappers validate ranges`() {
		ActivityConfidence.coerced(101) shouldBe ActivityConfidence.MAX
		ActivityConfidence.coerced(-4) shouldBe ActivityConfidence.ZERO
		assertFailsWith<IllegalArgumentException> { ActivityConfidence(101) }

		LatE7.fromDegrees(48.1234567).toDegrees() shouldBe (48.1234567 plusOrMinus 0.000001)
		LonE7.fromDegrees(16.9876543).toDegrees() shouldBe (16.9876543 plusOrMinus 0.000001)
		assertFailsWith<IllegalArgumentException> { LatE7(900_000_001) }
		assertFailsWith<IllegalArgumentException> { LonE7(1_800_000_001) }
	}
}
