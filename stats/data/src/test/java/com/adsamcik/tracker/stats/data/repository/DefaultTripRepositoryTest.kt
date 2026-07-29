package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.error.StatsError
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultTripRepositoryTest {

	private val tripDao: TripDao = mockk()
	private val repository = DefaultTripRepository(tripDao)

	@Test
	fun `observeTrips maps entity values and coerces invalid fields`() = runTest {
		everyRecentTripsFlow(
			Trip(
				id = 7L,
				startTimeMs = 2_000L,
				endTimeMs = 1_000L,
				distanceM = -20f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 9,
				source = SegmentSource.INFERRED_HIGH_CONFIDENCE,
				createdAt = 123L,
			),
		)

		val trip = repository.observeTrips().first().single()
		trip.distance.raw shouldBe 0f
		trip.steps.raw shouldBe 0
		trip.duration.raw shouldBe 0L
		trip.primaryMode shouldBe TransportMode.TRANSIT
	}

	@Test
	fun `getTripDetail returns NotFound when dao returns null`() = runTest {
		coEvery { tripDao.getById(42L) } returns null

		val result = repository.getTripDetail(42L)
		result.leftOrNull() shouldBe StatsError.NotFound("Trip not found", "Trip", "42")
	}

	@Test
	fun `getTripDetail wraps dao exceptions as database errors`() = runTest {
		val failure = IllegalStateException("boom")
		coEvery { tripDao.getById(5L) } throws failure

		val error = resultError(repository.getTripDetail(5L))
		error shouldBe StatsError.DatabaseError("Failed to load trip: boom", failure)
	}

	private fun everyRecentTripsFlow(vararg trips: Trip) {
		io.mockk.every { tripDao.getRecentTripsFlow(100) } returns flowOf(trips.toList())
	}

	private fun <T> resultError(result: arrow.core.Either<StatsError, T>): StatsError {
		return result.fold({ it }, { error("Expected Left but was Right") })
	}
}
