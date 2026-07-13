package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.viz.VizRequest
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.dao.InferredTripDao
import com.adsamcik.tracker.shared.base.database.data.FrequentPlaceEntity
import com.adsamcik.tracker.shared.base.database.data.InferredTripEntity
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Trip Constellations")
class TripConstellationsTest {

	@Test
	fun `joins inferred trips to local frequent-place coordinates`() = runTest {
		val tripDao = mockk<InferredTripDao>()
		val placeDao = mockk<FrequentPlaceDao>()
		coEvery { tripDao.getAllBetween(any(), any()) } returns listOf(trip())
		coEvery { placeDao.getAll(any()) } returns listOf(place(1L, 50.0, 14.0), place(2L, 50.2, 14.4))

		val features = tripConstellationSource(tripDao, placeDao)
			.load(VizRequest(0L..Long.MAX_VALUE, bounds = null))

		features shouldHaveSize 1
		features.single().groupKey shouldBe "1:2:car"
		features.single().startLat shouldBe 50.0
		features.single().endLon shouldBe 14.4
	}

	@Test
	fun `maps persisted run and high speed rail modes to their visual families`() = runTest {
		val tripDao = mockk<InferredTripDao>()
		val placeDao = mockk<FrequentPlaceDao>()
		coEvery { tripDao.getAllBetween(any(), any()) } returns listOf(
			trip(transportMode = "RUN"),
			trip(transportMode = "HIGH_SPEED_RAIL").copy(id = 11L),
		)
		coEvery { placeDao.getAll(any()) } returns listOf(place(1L, 50.0, 14.0), place(2L, 50.2, 14.4))

		val features = tripConstellationSource(tripDao, placeDao)
			.load(VizRequest(0L..Long.MAX_VALUE, bounds = null))

		features.map { it.category } shouldBe listOf("walk", "transit")
	}

	@Test
	fun `keeps an arc when its curve enters the viewport but its endpoints do not`() = runTest {
		val tripDao = mockk<InferredTripDao>()
		val placeDao = mockk<FrequentPlaceDao>()
		coEvery { tripDao.getAllBetween(any(), any()) } returns listOf(trip())
		coEvery { placeDao.getAll(any()) } returns listOf(place(1L, 50.0, 14.0), place(2L, 50.0, 16.0))

		val features = tripConstellationSource(tripDao, placeDao).load(
			VizRequest(
				dateRange = 0L..Long.MAX_VALUE,
				bounds = Bounds(north = 50.12, east = 15.1, south = 50.08, west = 14.9),
			),
		)

		features shouldHaveSize 1
	}

	@Test
	fun `drops trips whose endpoint place is unavailable`() = runTest {
		val tripDao = mockk<InferredTripDao>()
		val placeDao = mockk<FrequentPlaceDao>()
		coEvery { tripDao.getAllBetween(any(), any()) } returns listOf(trip())
		coEvery { placeDao.getAll(any()) } returns listOf(place(1L, 50.0, 14.0))

		tripConstellationSource(tripDao, placeDao)
			.load(VizRequest(0L..Long.MAX_VALUE, bounds = null)) shouldHaveSize 0
	}

	private fun trip(transportMode: String = "Car") = InferredTripEntity(
		id = 10L,
		segmentId = 20L,
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		distanceM = 5_000f,
		steps = null,
		primaryActivity = null,
		transportMode = transportMode,
		departurePlaceId = 1L,
		arrivalPlaceId = 2L,
		source = "test",
		inferenceVersion = "1",
		legCount = 1,
		createdAt = 2_000L,
	)

	private fun place(id: Long, lat: Double, lon: Double) = FrequentPlaceEntity(
		id = id,
		centerLatE7 = (lat * 1e7).toInt(),
		centerLonE7 = (lon * 1e7).toInt(),
		radiusM = 50f,
		visitCount = 10,
		firstVisitMs = 0L,
		lastVisitMs = 2_000L,
		autoCategory = null,
		createdAt = 0L,
	)
}
