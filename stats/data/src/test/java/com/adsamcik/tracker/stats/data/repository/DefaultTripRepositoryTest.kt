package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.TripSummary
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultTripRepositoryTest {

	private val tripDao: TripDao = mockk()
	private val savedLocationMetrics = mockk<SavedLocationMetricsReader> {
        coEvery { readTrip(any()) } returns com.adsamcik.tracker.stats.api.repository.SavedLocationSummary(0L, null, null, false)
        io.mockk.every { observeChanges() } returns flowOf(Unit)
    }
    private val repository = DefaultTripRepository(tripDao, savedLocationMetrics)

	@Test
	fun `observeTrips maps non-Steps values and coerces invalid fields`() = runTest {
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
		trip.hasMeasuredDistance shouldBe false
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

	@Test
	fun `cancelling a pending trip detail read remains cancellation`() = runTest {
		val enteredDao = CompletableDeferred<Unit>()
		coEvery { tripDao.getById(6L) } coAnswers {
			enteredDao.complete(Unit)
			awaitCancellation()
		}
		// Capture the repository result independently of the cancelled child job.
		val outcome = CompletableDeferred<Result<arrow.core.Either<StatsError, TripSummary>>>()
		val reading = launch(start = CoroutineStart.UNDISPATCHED) {
			outcome.complete(runCatching { repository.getTripDetail(6L) })
		}
		enteredDao.await()
		outcome.isCompleted shouldBe false

		reading.cancel(CancellationException("Trip detail request cancelled"))
		reading.join()

		(outcome.await().exceptionOrNull() is CancellationException) shouldBe true
		io.mockk.coVerify(exactly = 1) { tripDao.getById(6L) }
		io.mockk.confirmVerified(tripDao)
	}

	private fun everyRecentTripsFlow(vararg trips: Trip) {
		io.mockk.every { tripDao.getRecentTripsFlow(100) } returns flowOf(trips.toList())
	}

	@Test
	fun `exact imported zero-sample detail carries origin before any supplementary lookup`() = runTest {
		coEvery { tripDao.getById(10L) } returns Trip(
			id = 10L, startTimeMs = 1_000L, endTimeMs = 2_000L, distanceM = 0f,
			steps = null, primaryActivity = null, activityConfidence = null, sampleCount = 0,
			source = SegmentSource.PORTABLE_STEPS_IMPORT, createdAt = 3_000L,
		)
		val detail = repository.getTripDetail(10L).getOrNull()!!
		detail.hasMeasuredDistance shouldBe false
		detail.source shouldBe SegmentSource.PORTABLE_STEPS_IMPORT
		detail.primaryMode shouldBe TransportMode.UNKNOWN
		detail.sampleCount shouldBe 0
		io.mockk.coVerify(exactly = 1) { tripDao.getById(10L) }
		io.mockk.confirmVerified(tripDao)
	}

	@Test
	fun `one failed Location read keeps both trips and a measured stationary zero`() = runTest {
		val original = Trip(id = 21L, startTimeMs = 1_000L, endTimeMs = 2_000L, distanceM = 999f,
			steps = null, primaryActivity = 7, activityConfidence = null, sampleCount = 2,
			source = SegmentSource.USER_CREATED, createdAt = 2_000L)
		everyRecentTripsFlow(original, original.copy(id = 22L))
		coEvery { savedLocationMetrics.readTrip(21L) } throws IllegalStateException("Location unavailable")
		coEvery { savedLocationMetrics.readTrip(22L) } returns mockk {
			io.mockk.every { pointCount } returns 2L
			io.mockk.every { distanceMeters } returns 0.0
			io.mockk.every { partial } returns false
		}
		val trips = repository.observeTrips().first()
		trips.map { it.id } shouldBe listOf(21L, 22L)
		trips.first().hasMeasuredDistance shouldBe false
		trips.first().distanceIsPartial shouldBe true
		trips.last().hasMeasuredDistance shouldBe true
		trips.last().distance.raw shouldBe 0f
		trips.last().distanceIsPartial shouldBe false
	}

	private fun <T> resultError(result: arrow.core.Either<StatsError, T>): StatsError {
		return result.fold({ it }, { error("Expected Left but was Right") })
	}

	@Test
	fun `paging storage failures remain retryable page errors`() = runTest {
		val failure = IllegalStateException("Page unavailable")
		val delegate = mockk<androidx.paging.PagingSource<Int, Trip>> {
			io.mockk.every { registerInvalidatedCallback(any()) } returns Unit
			coEvery { load(any()) } throws failure
		}
		io.mockk.every { tripDao.getAllPaged() } returns delegate
		io.mockk.every { savedLocationMetrics.onSavedDataChange(any()) } returns {}
		val result = repository.getPagedTrips().load(androidx.paging.PagingSource.LoadParams.Refresh(
			key = null, loadSize = 20, placeholdersEnabled = false))
		(result as androidx.paging.PagingSource.LoadResult.Error<Int, com.adsamcik.tracker.shared.model.Trip>).throwable shouldBe failure
	}
}
