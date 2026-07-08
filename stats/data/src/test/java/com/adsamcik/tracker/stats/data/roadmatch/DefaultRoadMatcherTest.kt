package com.adsamcik.tracker.stats.data.roadmatch

import com.adsamcik.tracker.osm.match.OsmHmmMapMatcher
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.stats.api.roadmatch.MatchedEdge
import com.adsamcik.tracker.stats.api.roadmatch.RoadObservation
import com.adsamcik.tracker.stats.api.roadmatch.RoadPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [DefaultRoadMatcher]. The OSM-import gate uses the same
 * StateFlow snapshot pattern as `DefaultSpeedLimitSource`, so we drive
 * [OsmImportDao.observeCount] with a [MutableStateFlow] under an
 * [UnconfinedTestDispatcher] for a deterministic cached count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DefaultRoadMatcher")
class DefaultRoadMatcherTest {

	private fun newImportDao(count: Int): OsmImportDao {
		val dao = mockk<OsmImportDao>()
		val countFlow = MutableStateFlow(count)
		every { dao.observeCount() } returns countFlow
		coEvery { dao.count() } answers { countFlow.value }
		return dao
	}

	private val twoObs = listOf(
		RoadObservation(500_000_000, 140_000_000, 8f, 1_000L),
		RoadObservation(500_010_000, 140_000_000, 8f, 2_000L),
	)

	private val sampleEdge = MatchedEdge(
		fromIndex = 0,
		toIndex = 1,
		path = listOf(RoadPoint(500_000_000, 140_000_000), RoadPoint(500_010_000, 140_000_000)),
		maxspeedKmh = 50,
	)

	@Test
	fun `no OSM import returns empty without invoking the matcher`() = runTest {
		val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
		val hmm = mockk<OsmHmmMapMatcher>()
		val matcher = DefaultRoadMatcher(hmm, newImportDao(count = 0), scope)

		matcher.match(twoObs).shouldBeEmpty()

		coVerify(exactly = 0) { hmm.match(any()) }
		scope.cancel()
	}

	@Test
	fun `with an OSM import it delegates to the HMM matcher`() = runTest {
		val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
		val hmm = mockk<OsmHmmMapMatcher>()
		coEvery { hmm.match(twoObs) } returns listOf(sampleEdge)
		val matcher = DefaultRoadMatcher(hmm, newImportDao(count = 1), scope)

		val edges = matcher.match(twoObs)

		edges shouldHaveSize 1
		coVerify(exactly = 1) { hmm.match(twoObs) }
		scope.cancel()
	}

	@Test
	fun `fewer than two observations short-circuits to empty`() = runTest {
		val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
		val hmm = mockk<OsmHmmMapMatcher>()
		val matcher = DefaultRoadMatcher(hmm, newImportDao(count = 1), scope)

		matcher.match(twoObs.take(1)).shouldBeEmpty()

		coVerify(exactly = 0) { hmm.match(any()) }
		scope.cancel()
	}
}
