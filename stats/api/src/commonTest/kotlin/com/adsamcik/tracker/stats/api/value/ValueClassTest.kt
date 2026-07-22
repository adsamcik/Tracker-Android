package com.adsamcik.tracker.stats.api.value

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ValueClassTest {

	// ── ActivityConfidence ─────────────────────────────────────────────

	@Nested
	inner class ActivityConfidenceValidation {

		@Test
		fun `rejects negative confidence`() {
			assertThrows<IllegalArgumentException> { ActivityConfidence(-1) }
		}

		@Test
		fun `rejects confidence above 100`() {
			assertThrows<IllegalArgumentException> { ActivityConfidence(101) }
		}

		@Test
		fun `accepts boundary 0`() {
			ActivityConfidence(0).raw shouldBe 0
		}

		@Test
		fun `accepts boundary 100`() {
			ActivityConfidence(100).raw shouldBe 100
		}

		@Test
		fun `ZERO constant is 0`() {
			ActivityConfidence.ZERO.raw shouldBe 0
		}

		@Test
		fun `MAX constant is 100`() {
			ActivityConfidence.MAX.raw shouldBe 100
		}

		@Test
		fun `coerced clamps negative to 0`() {
			ActivityConfidence.coerced(-50).raw shouldBe 0
		}

		@Test
		fun `coerced clamps above 100 to 100`() {
			ActivityConfidence.coerced(200).raw shouldBe 100
		}

		@Test
		fun `coerced passes through valid value`() {
			ActivityConfidence.coerced(75).raw shouldBe 75
		}
	}

	// ── DistanceM ──────────────────────────────────────────────────────

	@Nested
	inner class DistanceMValidation {

		@Test
		fun `rejects negative distance`() {
			assertThrows<IllegalArgumentException> { DistanceM(-0.1f) }
		}

		@Test
		fun `accepts zero`() {
			DistanceM(0f).raw shouldBe 0f
		}

		@Test
		fun `ZERO constant`() {
			DistanceM.ZERO.raw shouldBe 0f
		}

		@Test
		fun `plus operator adds distances`() {
			val result = DistanceM(10f) + DistanceM(20f)
			result.raw shouldBe 30f
		}

		@Test
		fun `compareTo orders correctly`() {
			DistanceM(10f) shouldBeLessThan DistanceM(20f)
			DistanceM(20f) shouldBeGreaterThan DistanceM(10f)
		}

		@Test
		fun `coerced clamps negative to zero`() {
			DistanceM.coerced(-5f).raw shouldBe 0f
		}

		@Test
		fun `coerced passes through positive`() {
			DistanceM.coerced(42f).raw shouldBe 42f
		}

		@Test
		fun `orNull returns null for null input`() {
			DistanceM.orNull(null).shouldBeNull()
		}

		@Test
		fun `orNull returns null for negative input`() {
			DistanceM.orNull(-1f).shouldBeNull()
		}

		@Test
		fun `orNull returns value for valid input`() {
			val result = DistanceM.orNull(5f)
			result.shouldNotBeNull()
			result.raw shouldBe 5f
		}
	}

	// ── DurationMs ─────────────────────────────────────────────────────

	@Nested
	inner class DurationMsValidation {

		@Test
		fun `rejects negative duration`() {
			assertThrows<IllegalArgumentException> { DurationMs(-1L) }
		}

		@Test
		fun `accepts zero`() {
			DurationMs(0L).raw shouldBe 0L
		}

		@Test
		fun `ZERO constant`() {
			DurationMs.ZERO.raw shouldBe 0L
		}

		@Test
		fun `plus operator adds durations`() {
			val result = DurationMs(1000L) + DurationMs(2000L)
			result.raw shouldBe 3000L
		}

		@Test
		fun `compareTo orders correctly`() {
			DurationMs(1000L) shouldBeLessThan DurationMs(2000L)
		}

		@Test
		fun `toSeconds converts correctly`() {
			DurationMs(2500L).toSeconds() shouldBe 2.5
		}

		@Test
		fun `toSeconds of zero is zero`() {
			DurationMs.ZERO.toSeconds() shouldBe 0.0
		}

		@Test
		fun `fromSeconds converts correctly`() {
			DurationMs.fromSeconds(3.5).raw shouldBe 3500L
		}

		@Test
		fun `fromSeconds clamps negative to zero`() {
			DurationMs.fromSeconds(-1.0).raw shouldBe 0L
		}
	}

	// ── EpochMs ────────────────────────────────────────────────────────

	@Nested
	inner class EpochMsValidation {

		@Test
		fun `rejects negative timestamp`() {
			assertThrows<IllegalArgumentException> { EpochMs(-1L) }
		}

		@Test
		fun `accepts zero`() {
			EpochMs(0L).raw shouldBe 0L
		}

		@Test
		fun `ZERO constant`() {
			EpochMs.ZERO.raw shouldBe 0L
		}

		@Test
		fun `minus operator computes difference`() {
			val diff = EpochMs(5000L) - EpochMs(2000L)
			diff shouldBe 3000L
		}

		@Test
		fun `compareTo orders correctly`() {
			EpochMs(1000L) shouldBeLessThan EpochMs(2000L)
		}
	}

	// ── LatE7 ──────────────────────────────────────────────────────────

	@Nested
	inner class LatE7Validation {

		@Test
		fun `rejects below -900M`() {
			assertThrows<IllegalArgumentException> { LatE7(-900_000_001) }
		}

		@Test
		fun `rejects above 900M`() {
			assertThrows<IllegalArgumentException> { LatE7(900_000_001) }
		}

		@Test
		fun `accepts min boundary`() {
			LatE7(-900_000_000).raw shouldBe -900_000_000
		}

		@Test
		fun `accepts max boundary`() {
			LatE7(900_000_000).raw shouldBe 900_000_000
		}

		@Test
		fun `toDegrees converts correctly`() {
			LatE7(487_000_000).toDegrees() shouldBe 48.7
		}

		@Test
		fun `fromDegrees converts correctly`() {
			LatE7.fromDegrees(48.7).raw shouldBe 487_000_000
		}

		@Test
		fun `fromDegrees and toDegrees round-trip`() {
			val original = 51.5074
			val roundTripped = LatE7.fromDegrees(original).toDegrees()
			(kotlin.math.abs(roundTripped - original) < 1e-6).shouldBeTrue()
		}

		@Test
		fun `fromDegrees rejects non finite and out of Earth range`() {
			assertThrows<IllegalArgumentException> { LatE7.fromDegrees(Double.NaN) }
			assertThrows<IllegalArgumentException> { LatE7.fromDegrees(90.0000001) }
		}
	}

	// ── LonE7 ──────────────────────────────────────────────────────────

	@Nested
	inner class LonE7Validation {

		@Test
		fun `rejects below -1800M`() {
			assertThrows<IllegalArgumentException> { LonE7(-1_800_000_001) }
		}

		@Test
		fun `rejects above 1800M`() {
			assertThrows<IllegalArgumentException> { LonE7(1_800_000_001) }
		}

		@Test
		fun `accepts min boundary`() {
			LonE7(-1_800_000_000).raw shouldBe -1_800_000_000
		}

		@Test
		fun `accepts max boundary`() {
			LonE7(1_800_000_000).raw shouldBe 1_800_000_000
		}

		@Test
		fun `toDegrees converts correctly`() {
			LonE7(163_000_000).toDegrees() shouldBe 16.3
		}

		@Test
		fun `fromDegrees converts correctly`() {
			LonE7.fromDegrees(-0.1278).raw shouldBe -1_278_000
		}

		@Test
		fun `fromDegrees and toDegrees round-trip`() {
			val original = -73.9857
			val roundTripped = LonE7.fromDegrees(original).toDegrees()
			(kotlin.math.abs(roundTripped - original) < 1e-6).shouldBeTrue()
		}

		@Test
		fun `factory canonicalizes positive antimeridian`() {
			LonE7.fromDegrees(180.0).raw shouldBe -1_800_000_000
			LonE7(1_800_000_000).canonicalSpatial().raw shouldBe -1_800_000_000
		}
	}

	// ── SpeedMps ───────────────────────────────────────────────────────

	@Nested
	inner class SpeedMpsValidation {

		@Test
		fun `rejects negative speed`() {
			assertThrows<IllegalArgumentException> { SpeedMps(-0.1f) }
		}

		@Test
		fun `accepts zero`() {
			SpeedMps(0f).raw shouldBe 0f
		}

		@Test
		fun `ZERO constant`() {
			SpeedMps.ZERO.raw shouldBe 0f
		}

		@Test
		fun `plus operator adds speeds`() {
			(SpeedMps(1f) + SpeedMps(2f)).raw shouldBe 3f
		}

		@Test
		fun `minus operator clamps to zero`() {
			(SpeedMps(1f) - SpeedMps(5f)).raw shouldBe 0f
		}

		@Test
		fun `minus with larger first operand`() {
			(SpeedMps(5f) - SpeedMps(2f)).raw shouldBe 3f
		}

		@Test
		fun `compareTo orders correctly`() {
			SpeedMps(1f) shouldBeLessThan SpeedMps(2f)
		}

		@Test
		fun `coerced clamps negative to zero`() {
			SpeedMps.coerced(-10f).raw shouldBe 0f
		}

		@Test
		fun `orNull returns null for null`() {
			SpeedMps.orNull(null).shouldBeNull()
		}

		@Test
		fun `orNull returns null for negative`() {
			SpeedMps.orNull(-1f).shouldBeNull()
		}

		@Test
		fun `orNull returns value for valid input`() {
			SpeedMps.orNull(3.5f).shouldNotBeNull().raw shouldBe 3.5f
		}
	}

	// ── StepCount ──────────────────────────────────────────────────────

	@Nested
	inner class StepCountValidation {

		@Test
		fun `rejects negative count`() {
			assertThrows<IllegalArgumentException> { StepCount(-1) }
		}

		@Test
		fun `accepts zero`() {
			StepCount(0).raw shouldBe 0
		}

		@Test
		fun `ZERO constant`() {
			StepCount.ZERO.raw shouldBe 0
		}

		@Test
		fun `plus operator adds counts`() {
			(StepCount(100) + StepCount(200)).raw shouldBe 300
		}

		@Test
		fun `compareTo orders correctly`() {
			StepCount(10) shouldBeLessThan StepCount(20)
		}

		@Test
		fun `coerced clamps negative to zero`() {
			StepCount.coerced(-5).raw shouldBe 0
		}

		@Test
		fun `coerced passes through valid`() {
			StepCount.coerced(42).raw shouldBe 42
		}
	}

	// ── CoordinateE7 ───────────────────────────────────────────────────

	@Nested
	inner class CoordinateE7Test {

		@Test
		fun `equality with same components`() {
			val c1 = CoordinateE7(LatE7(487_000_000), LonE7(163_000_000))
			val c2 = CoordinateE7(LatE7(487_000_000), LonE7(163_000_000))
			c1 shouldBe c2
		}

		@Test
		fun `copy with changed lon`() {
			val original = CoordinateE7(LatE7(487_000_000), LonE7(163_000_000))
			val modified = original.copy(lon = LonE7(0))
			modified.lat shouldBe original.lat
			modified.lon.raw shouldBe 0
		}

		@Test
		fun `propagates LatE7 validation`() {
			assertThrows<IllegalArgumentException> {
				CoordinateE7(LatE7(999_999_999), LonE7(0))
			}
		}

		@Test
		fun `propagates LonE7 validation`() {
			assertThrows<IllegalArgumentException> {
				CoordinateE7(LatE7(0), LonE7(1_900_000_000))
			}
		}

		@Test
		fun `canonical spatial pair normalizes poles and legacy antimeridian`() {
			CoordinateE7(LatE7(900_000_000), LonE7(1_800_000_000)).canonicalSpatial() shouldBe
				CoordinateE7(LatE7(900_000_000), LonE7(0))
		}
	}
}
