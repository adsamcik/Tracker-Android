package com.adsamcik.tracker.stats.data.speed

import com.adsamcik.tracker.osm.speed.OsmSpeedLimitSource
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.testing.fake.FakeTrackingParamsRepository
import io.kotest.matchers.doubles.shouldBeBetween
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for the Phase 2a dispatcher. Verifies that the source picks
 * the right backing implementation across the four-cell matrix:
 *
 *   coords?  ×  OSM imported?
 *
 * After R2 round-6 the dispatcher caches `osmImportDao.observeCount()` via a
 * StateFlow snapshot to keep the hot path off the IO thread. These tests use
 * a `MutableStateFlow` fake for [OsmImportDao.observeCount] so the cache
 * value is deterministic.
 *
 * **Why [UnconfinedTestDispatcher]?** `stateIn(Eagerly)` launches an internal
 * collector on its scope's dispatcher. With the default `StandardTestDispatcher`
 * that collector wouldn't run until [kotlinx.coroutines.test.advanceUntilIdle]
 * is called, AND its emissions still wouldn't be observable until the next
 * dispatch — making it impossible to assert "snapshot populated, no fallback
 * needed". `UnconfinedTestDispatcher` runs the collector synchronously so the
 * upstream's initial value reaches the StateFlow before any test code runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DefaultSpeedLimitSource dispatcher")
class DefaultSpeedLimitSourceTest {

	private val baselineMps = 50.0 / 3.6

	private fun newFixed(scope: CoroutineScope): FixedSpeedLimitSource {
		val repo = FakeTrackingParamsRepository(
			initialState = TrackingParamsState(vehicleSpeedLimitBaselineMps = baselineMps),
		)
		return FixedSpeedLimitSource(repo, scope)
	}

	private fun newImportDao(count: Int): Pair<OsmImportDao, MutableStateFlow<Int>> {
		val dao = mockk<OsmImportDao>()
		val countFlow = MutableStateFlow(count)
		every { dao.observeCount() } returns countFlow
		coEvery { dao.count() } answers { countFlow.value }
		return dao to countFlow
	}

	@Test
	fun `no coords falls back to fixed even with imports`() = runTest {
		val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
		val osm = mockk<OsmSpeedLimitSource>()
		val (importDao, _) = newImportDao(count = 1)

		val source = DefaultSpeedLimitSource(newFixed(scope), osm, importDao, scope)
		val limit = source.limitMpsAt(0L, null, null)

		limit.shouldBeBetween(baselineMps, baselineMps, 1e-9)
		coVerify(exactly = 0) { osm.findRoadLimitMps(any(), any()) }
		scope.cancel()
	}

	@Test
	fun `no imports skips OSM and uses fixed`() = runTest {
		val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
		val osm = mockk<OsmSpeedLimitSource>()
		val (importDao, _) = newImportDao(count = 0)

		val source = DefaultSpeedLimitSource(newFixed(scope), osm, importDao, scope)
		val limit = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		limit.shouldBeBetween(baselineMps, baselineMps, 1e-9)
		coVerify(exactly = 0) { osm.findRoadLimitMps(any(), any()) }
		scope.cancel()
	}

	@Test
	fun `OSM hit returns OSM limit`() = runTest {
		val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
		val osmLimit = 90.0 / 3.6
		val osm = mockk<OsmSpeedLimitSource>()
		val (importDao, _) = newImportDao(count = 1)
		coEvery { osm.findRoadLimitMps(500_000_000, 144_000_000) } returns osmLimit

		val source = DefaultSpeedLimitSource(newFixed(scope), osm, importDao, scope)
		val limit = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		limit.shouldBeBetween(osmLimit, osmLimit, 1e-9)
		scope.cancel()
	}

	@Test
	fun `OSM miss falls back to fixed`() = runTest {
		val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
		val osm = mockk<OsmSpeedLimitSource>()
		val (importDao, _) = newImportDao(count = 1)
		coEvery { osm.findRoadLimitMps(any(), any()) } returns null

		val source = DefaultSpeedLimitSource(newFixed(scope), osm, importDao, scope)
		val limit = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		limit.shouldBeBetween(baselineMps, baselineMps, 1e-9)
		scope.cancel()
	}

	@Test
	fun `OSM count snapshot updates when observeCount emits`() = runTest {
		val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
		val osm = mockk<OsmSpeedLimitSource>()
		val (importDao, countFlow) = newImportDao(count = 0)
		coEvery { osm.findRoadLimitMps(any(), any()) } returns 25.0

		val source = DefaultSpeedLimitSource(newFixed(scope), osm, importDao, scope)

		// 1st call: snapshot already populated with 0 (no imports) -> fixed.
		source.limitMpsAt(0L, 500_000_000, 144_000_000)

		// User imports a region: observeCount emits the new value and the
		// cached snapshot updates synchronously under Unconfined.
		countFlow.value = 1

		val second = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		second.shouldBeBetween(25.0, 25.0, 1e-9)
		coVerify(exactly = 1) { osm.findRoadLimitMps(any(), any()) }
		// dao.count() must NEVER be hit on the warm path — the whole point of
		// the snapshot is to keep hot callers off the IO thread.
		coVerify(exactly = 0) { importDao.count() }
		scope.cancel()
	}

	@Test
	fun `cold start falls back to direct count when cache is uninitialized`() = runTest {
		// Use a paused (never-advanced) scope so the stateIn collector is
		// launched but never gets to emit. Cache stays at the sentinel for the
		// duration of the test, proving the cold-path fallback works.
		val osm = mockk<OsmSpeedLimitSource>()
		val importDao = mockk<OsmImportDao>()
		val pausedFlow = MutableStateFlow(-1)
		every { importDao.observeCount() } returns pausedFlow
		coEvery { importDao.count() } returns 1
		coEvery { osm.findRoadLimitMps(any(), any()) } returns 25.0

		// StandardTestDispatcher without advance => collector queued but never runs.
		val pausedScope = TestScope().backgroundScope
		val source = DefaultSpeedLimitSource(newFixed(pausedScope), osm, importDao, pausedScope)

		val limit = source.limitMpsAt(0L, 500_000_000, 144_000_000)

		limit.shouldBeBetween(25.0, 25.0, 1e-9)
		coVerify(atLeast = 1) { importDao.count() }
	}
}
