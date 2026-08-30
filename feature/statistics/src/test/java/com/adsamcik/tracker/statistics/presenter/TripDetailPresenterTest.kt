package com.adsamcik.tracker.statistics.presenter

import app.cash.turbine.test
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TripDetailPresenterTest {

	private val tripRepository: TripRepository = mockk()
	private val trackingHistoryRepository: TrackingHistoryRepository = mockk()
	private val presenter = TripDetailPresenter(tripRepository, trackingHistoryRepository)

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
		every { trackingHistoryRepository.observeSession(42L) } returns flowOf(foundHistory(42L, 300L))
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			awaitItem() shouldBe TripDetailState.Loaded(
				trip = sampleTrip,
				steps = TripDetailStepsState.Materializing,
			)
			val loaded = awaitItem()
			loaded.shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.trip shouldBe sampleTrip
			loaded.steps shouldBe TripDetailStepsState.Complete(300L)
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
		every { trackingHistoryRepository.observeSession(1L) } returns flowOf(foundHistory(1L, 10L))
		every { trackingHistoryRepository.observeSession(2L) } returns flowOf(foundHistory(2L, 20L))
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(1L))
			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>().steps shouldBe
				TripDetailStepsState.Materializing
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>().steps shouldBe
				TripDetailStepsState.Complete(10L)

			// New event replaces the old flow
			events.emit(TripDetailEvent.LoadTrip(2L))
			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>().steps shouldBe
				TripDetailStepsState.Materializing
			val loaded = awaitItem()
			loaded.shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.trip.id shouldBe 2L
			loaded.steps shouldBe TripDetailStepsState.Complete(20L)
		}
	}

	@Test
	fun `history not found invalidates an otherwise loaded trip`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		every { trackingHistoryRepository.observeSession(42L) } returns flowOf(SessionHistoryQuery.NotFound)
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe TripDetailState.Loaded(sampleTrip, TripDetailStepsState.Materializing)
			awaitItem() shouldBe TripDetailState.NotFound(42L)
		}
	}

	@Test
	fun `history read failure degrades only Steps and preserves the trip`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		every { trackingHistoryRepository.observeSession(42L) } returns flow {
			throw IllegalStateException("history failed")
		}
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe TripDetailState.Loaded(sampleTrip, TripDetailStepsState.Materializing)
			val loaded = awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.trip shouldBe sampleTrip
			loaded.steps shouldBe TripDetailStepsState.Failed
		}
	}

	@Test
	fun `history failure after a value keeps unrelated trip content loaded`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		every { trackingHistoryRepository.observeSession(42L) } returns flow {
			emit(foundHistory(42L, 300L))
			throw IllegalStateException("later history failure")
		}
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe TripDetailState.Loaded(sampleTrip, TripDetailStepsState.Materializing)
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>().steps shouldBe
				TripDetailStepsState.Complete(300L)
			val degraded = awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()
			degraded.trip shouldBe sampleTrip
			degraded.steps shouldBe TripDetailStepsState.Failed
		}
	}

	@Test
	fun `durable history corrections update the selected trip`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		val history = MutableSharedFlow<SessionHistoryQuery>()
		every { trackingHistoryRepository.observeSession(42L) } returns history
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe TripDetailState.Loaded(sampleTrip, TripDetailStepsState.Materializing)

			history.emit(foundHistory(42L, 300L))
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>().steps shouldBe
				TripDetailStepsState.Complete(300L)

			history.emit(foundHistory(42L, 280L))
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>().steps shouldBe
				TripDetailStepsState.Complete(280L)
		}
	}

	@Test
	fun `retry recovers history after a terminal observer failure`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		every { trackingHistoryRepository.observeSession(42L) } returnsMany listOf(
			flow { throw IllegalStateException("history failed") },
			flowOf(foundHistory(42L, 280L)),
		)
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe TripDetailState.Loaded(sampleTrip, TripDetailStepsState.Materializing)
			awaitItem() shouldBe TripDetailState.Loaded(sampleTrip, TripDetailStepsState.Failed)

			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe TripDetailState.Loaded(sampleTrip, TripDetailStepsState.Materializing)
			awaitItem() shouldBe TripDetailState.Loaded(
				trip = sampleTrip,
				steps = TripDetailStepsState.Complete(280L),
			)
		}
	}

	private fun foundHistory(segmentId: Long, count: Long): SessionHistoryQuery =
		SessionHistoryQuery.Found(
			SessionHistory(
				segmentId = segmentId,
				steps = StepsHistory(
					count = count,
					availability = HistoryAvailability.AVAILABLE,
					evidence = when (count) {
						0L -> HistoryEvidence.ACTIVE
						else -> HistoryEvidence.RECORDED
					},
					productState = HistoryProductState.READY,
					coverage = StepsHistoryCoverage.COMPLETE,
				),
			),
		)
}
