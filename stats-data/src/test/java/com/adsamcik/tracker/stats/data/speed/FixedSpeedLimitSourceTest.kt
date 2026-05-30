package com.adsamcik.tracker.stats.data.speed

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.testing.fake.FakeTrackingParamsRepository
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Verifies the Phase 1 fixed baseline source returns the configured
 * `vehicleSpeedLimitBaselineMps` regardless of position or time, and
 * reflects user updates without restart.
 */
class FixedSpeedLimitSourceTest {

	@Test
	fun `returns configured baseline regardless of args`() = runTest {
		val baselineMps = 50.0 / 3.6 // 50 km/h
		val repo = FakeTrackingParamsRepository(
			initialState = TrackingParamsState(vehicleSpeedLimitBaselineMps = baselineMps),
		)
		val source = FixedSpeedLimitSource(repo)

		source.limitMpsAt(0L, null, null).shouldBeBetween(baselineMps, baselineMps, EPS)
		source.limitMpsAt(System.currentTimeMillis(), 500_000_000, 144_000_000)
			.shouldBeBetween(baselineMps, baselineMps, EPS)
		source.limitMpsAt(Long.MAX_VALUE, -900_000_000, 1_800_000_000)
			.shouldBeBetween(baselineMps, baselineMps, EPS)
	}

	@Test
	fun `reflects updates to repository state`() = runTest {
		val repo = FakeTrackingParamsRepository(
			initialState = TrackingParamsState(vehicleSpeedLimitBaselineMps = 30.0 / 3.6),
		)
		val source = FixedSpeedLimitSource(repo)

		source.limitMpsAt(0L, null, null).shouldBeBetween(30.0 / 3.6, 30.0 / 3.6, EPS)

		repo.setVehicleSpeedLimitBaselineMps(130.0 / 3.6)

		source.limitMpsAt(0L, null, null).shouldBeBetween(130.0 / 3.6, 130.0 / 3.6, EPS)
	}

	@Test
	fun `default repository state surfaces default baseline`() = runTest {
		val repo = FakeTrackingParamsRepository()
		val source = FixedSpeedLimitSource(repo)

		val expected = TrackingParamsState().vehicleSpeedLimitBaselineMps
		source.limitMpsAt(0L, 500_000_000, 144_000_000) shouldBe expected
	}

	companion object {
		private const val EPS = 1e-9
	}
}
