package com.adsamcik.tracker.statistics.presenter

import app.cash.turbine.test
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.api.TransportMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TripDetailPresenterTest {

	private val tripRepository: TripRepository = mockk()
	private val presenter = TripDetailPresenter(tripRepository)

	private val sampleTrip = TripSummary(
		id = 42L,
		startTimeMs = EpochMs(1000L),
		endTimeMs = EpochMs(5000L),
		distance = DistanceM(1500f),
		steps = StepCount(300),
		duration = DurationMs(4000L),
		primaryMode = TransportMode.WALK,
		sampleCount = 20,
	)

	@Test
	fun `emits Loading then Loaded on success`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			val loaded = awaitItem()
			loaded.shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.trip shouldBe sampleTrip
		}
	}

	@Test
	fun `emits NotFound when repository returns NotFound error`() = runTest {
		coEvery { tripRepository.getTripDetail(999L) } returns StatsError.NotFound(
			message = "Trip not found",
			entityType = "Trip",
			id = "999",
		).left()
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(999L))

			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			val state = awaitItem()
			state.shouldBeInstanceOf<TripDetailState.NotFound>()
			state.tripId shouldBe 999L
		}
	}

	@Test
	fun `emits Error for non-NotFound errors`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns StatsError.DatabaseError(
			message = "db crashed",
		).left()
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			val state = awaitItem()
			state.shouldBeInstanceOf<TripDetailState.Error>()
			state.message shouldBe "db crashed"
		}
	}

	@Test
	fun `new LoadTrip event cancels previous and reloads`() = runTest {
		coEvery { tripRepository.getTripDetail(1L) } returns sampleTrip.right()
		coEvery { tripRepository.getTripDetail(2L) } returns sampleTrip.copy(id = 2L).right()
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(1L))
			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()

			// New event replaces the old flow
			events.emit(TripDetailEvent.LoadTrip(2L))
			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			val loaded = awaitItem()
			loaded.shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.trip.id shouldBe 2L
		}
	}
}
