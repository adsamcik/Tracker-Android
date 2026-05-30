package com.adsamcik.tracker.stats.data.speed

import com.adsamcik.tracker.osm.speed.OsmSpeedLimitSource
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.testing.fake.FakeTrackingParamsRepository
import io.kotest.matchers.doubles.shouldBeBetween
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for the Phase 2a dispatcher. Verifies that the source picks
 * the right backing implementation across the four-cell matrix:
 *
 *   coords?  ×  OSM imported?
 */
@DisplayName("DefaultSpeedLimitSource dispatcher")
class DefaultSpeedLimitSourceTest {

	private val baselineMps = 50.0 / 3.6

	private fun newFixed(): FixedSpeedLimitSource {
		val repo = FakeTrackingParamsRepository(
			initialState = TrackingParamsState(vehicleSpeedLimitBaselineMps = baselineMps),
		)
		return FixedSpeedLimitSource(repo)
	}

	@Test
	fun `no coords falls back to fixed even with imports`() = runTest {
		val osm = mockk<OsmSpeedLimitSource>()
		val importDao = mockk<OsmImportDao>()
		coEvery { importDao.count() } returns 1

		val source = DefaultSpeedLimitSource(newFixed(), osm, importDao)
		val limit = source.limitMpsAt(0L, null, null)

		limit.shouldBeBetween(baselineMps, baselineMps, 1e-9)
		coVerify(exactly = 0) { osm.findRoadLimitMps(any(), any()) }
	}

	@Test
	fun `no imports skips OSM and uses fixed`() = runTest {
		val osm = mockk<OsmSpeedLimitSource>()
		val importDao = mockk<OsmImportDao>()
		coEvery { importDao.count() } returns 0

		val source = DefaultSpeedLimitSource(newFixed(), osm, importDao)
		val limit = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		limit.shouldBeBetween(baselineMps, baselineMps, 1e-9)
		coVerify(exactly = 0) { osm.findRoadLimitMps(any(), any()) }
	}

	@Test
	fun `OSM hit returns OSM limit`() = runTest {
		val osmLimit = 90.0 / 3.6
		val osm = mockk<OsmSpeedLimitSource>()
		val importDao = mockk<OsmImportDao>()
		coEvery { importDao.count() } returns 1
		coEvery { osm.findRoadLimitMps(500_000_000, 144_000_000) } returns osmLimit

		val source = DefaultSpeedLimitSource(newFixed(), osm, importDao)
		val limit = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		limit.shouldBeBetween(osmLimit, osmLimit, 1e-9)
	}

	@Test
	fun `OSM miss falls back to fixed`() = runTest {
		val osm = mockk<OsmSpeedLimitSource>()
		val importDao = mockk<OsmImportDao>()
		coEvery { importDao.count() } returns 1
		coEvery { osm.findRoadLimitMps(any(), any()) } returns null

		val source = DefaultSpeedLimitSource(newFixed(), osm, importDao)
		val limit = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		limit.shouldBeBetween(baselineMps, baselineMps, 1e-9)
	}

	@Test
	fun `OSM count is re-read on every call`() = runTest {
		val osm = mockk<OsmSpeedLimitSource>()
		val importDao = mockk<OsmImportDao>()
		// Counts toggle between calls to simulate the user deleting a region mid-session.
		coEvery { importDao.count() } returnsMany listOf(0, 1)
		coEvery { osm.findRoadLimitMps(any(), any()) } returns 25.0

		val source = DefaultSpeedLimitSource(newFixed(), osm, importDao)
		// 1st call: count == 0 -> fixed, OSM not consulted.
		source.limitMpsAt(0L, 500_000_000, 144_000_000)
		// 2nd call: count == 1 -> OSM consulted, returns 25.
		val second = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		second.shouldBeBetween(25.0, 25.0, 1e-9)
		coVerify(exactly = 1) { osm.findRoadLimitMps(any(), any()) }
		coVerify(exactly = 2) { importDao.count() }
	}
}
