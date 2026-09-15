package com.adsamcik.tracker.statistics.presenter

import app.cash.turbine.test
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistorySnapshot
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
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
		duration = DurationMs(4000L),
		primaryMode = TransportMode.WALK,
		sampleCount = 20,
	)

	@Test
	fun `emits Loading then Loaded on success`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flowOf(foundHistory(42L, 300L))
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem().shouldBeInstanceOf<TripDetailState.Loading>()
			awaitItem() shouldBe TripDetailState.Loaded(
				trip = sampleTrip,
				steps = TripDetailStepsState.Materializing,
				sourcePresentation = TripDetailSourcePresentation.Resolving,
			)
			val loaded = awaitItem()
			loaded.shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.trip shouldBe sampleTrip
			loaded.steps shouldBe TripDetailStepsState.Complete(300L)
			loaded.sourcePresentation shouldBe TripDetailSourcePresentation.LegacyUnverifiable
		}
	}

	@Test
	fun `exact Activity-only capture selects retained Activity detail`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		val activity = unavailableActivity(capturesOnlyActivity = true)
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flowOf(
			foundHistory(
				segmentId = 42L,
				count = null,
				capture = exactCapture(
					sources = setOf(HistorySource.ACTIVITY),
					controlSources = setOf(HistorySource.LOCATION),
				),
				activity = activity,
			),
		)
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>().sourcePresentation shouldBe
				TripDetailSourcePresentation.ActivityOnly(activity)
		}
	}

	@Test
	fun `exact Steps-only capture selects retained Steps detail and covered zero`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		val steps = completeSteps(0L)
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flowOf(
			foundHistory(
				segmentId = 42L,
				count = null,
				capture = exactCapture(
					sources = setOf(HistorySource.STEPS),
					controlSources = setOf(HistorySource.ACTIVITY),
				),
				steps = steps,
			),
		)
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			val loaded = awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.steps shouldBe TripDetailStepsState.Complete(0L)
			loaded.sourcePresentation shouldBe TripDetailSourcePresentation.StepsOnly(steps)
		}
	}

	@Test
	fun `exact Pressure-only capture selects contained Pressure detail before any fact`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		val pressure = unavailablePressure()
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flowOf(
			foundHistory(
				segmentId = 42L,
				count = null,
				capture = exactCapture(setOf(HistorySource.PRESSURE)),
				pressure = pressure,
			),
		)
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			val loaded = awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.sourcePresentation shouldBe TripDetailSourcePresentation.PressureOnly(pressure)
		}
	}

	@Test
	fun `exact mixed capture without Location never falls back to Location detail`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flowOf(
			foundHistory(
				segmentId = 42L,
				count = null,
				capture = exactCapture(setOf(HistorySource.ACTIVITY, HistorySource.STEPS)),
			),
		)
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>().sourcePresentation shouldBe
				TripDetailSourcePresentation.CapturedWithoutLocation(
					setOf(HistorySource.ACTIVITY, HistorySource.STEPS),
				)
		}
	}

	@Test
	fun `mixed capture keeps existing Location-capable detail`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flowOf(
			foundHistory(
				segmentId = 42L,
				count = null,
				capture = exactCapture(setOf(HistorySource.LOCATION, HistorySource.PRESSURE)),
			),
		)
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			val loaded = awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.sourcePresentation shouldBe TripDetailSourcePresentation.Standard
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
		every { trackingHistoryRepository.observeLiveSession(1L) } returns flowOf(foundHistory(1L, 10L))
		every { trackingHistoryRepository.observeLiveSession(2L) } returns flowOf(foundHistory(2L, 20L))
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
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flowOf(notFoundHistory(42L))
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			awaitItem() shouldBe TripDetailState.NotFound(42L)
		}
	}

	@Test
	fun `history read failure preserves the trip and fails source presentation closed`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flow {
			throw IllegalStateException("history failed")
		}
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			val loaded = awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()
			loaded.trip shouldBe sampleTrip
			loaded.steps shouldBe TripDetailStepsState.Failed
			loaded.sourcePresentation shouldBe TripDetailSourcePresentation.Failed
		}
	}

	@Test
	fun `history failure replaces stale Pressure presentation and keeps trip loaded`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		val pressure = unavailablePressure()
		every { trackingHistoryRepository.observeLiveSession(42L) } returns flow {
			emit(
				foundHistory(
					segmentId = 42L,
					count = 300L,
					capture = exactCapture(setOf(HistorySource.PRESSURE)),
					pressure = pressure,
				),
			)
			throw IllegalStateException("later history failure")
		}
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))

			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			val beforeFailure = awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()
			beforeFailure.steps shouldBe TripDetailStepsState.Complete(300L)
			beforeFailure.sourcePresentation shouldBe TripDetailSourcePresentation.PressureOnly(pressure)
			val degraded = awaitItem().shouldBeInstanceOf<TripDetailState.Loaded>()
			degraded.trip shouldBe sampleTrip
			degraded.steps shouldBe TripDetailStepsState.Failed
			degraded.sourcePresentation shouldBe TripDetailSourcePresentation.Failed
		}
	}

	@Test
	fun `durable history corrections update the selected trip`() = runTest {
		coEvery { tripRepository.getTripDetail(42L) } returns sampleTrip.right()
		val history = MutableSharedFlow<LiveSessionHistorySnapshot>()
		every { trackingHistoryRepository.observeLiveSession(42L) } returns history
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()

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
		every { trackingHistoryRepository.observeLiveSession(42L) } returnsMany listOf(
			flow { throw IllegalStateException("history failed") },
			flowOf(foundHistory(42L, 280L)),
		)
		val events = MutableSharedFlow<TripDetailEvent>()

		presenter.present(events).test {
			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			awaitItem() shouldBe TripDetailState.Loaded(
				trip = sampleTrip,
				steps = TripDetailStepsState.Failed,
				sourcePresentation = TripDetailSourcePresentation.Failed,
			)

			events.emit(TripDetailEvent.LoadTrip(42L))
			awaitItem() shouldBe TripDetailState.Loading
			awaitItem() shouldBe resolvingState()
			awaitItem() shouldBe TripDetailState.Loaded(
				trip = sampleTrip,
				steps = TripDetailStepsState.Complete(280L),
				sourcePresentation = TripDetailSourcePresentation.LegacyUnverifiable,
			)
		}
	}

	private fun foundHistory(
		segmentId: Long,
		count: Long?,
		capture: HistoryCapture = HistoryCapture.Unverifiable,
		pressure: PressureHistory = unavailablePressure(),
		steps: StepsHistory = count?.let(::completeSteps) ?: notCapturedSteps(),
		activity: ActivityHistoryEntry = unavailableActivity(
			capturesOnlyActivity = capture.capturesOnlyActivity(),
		),
	): LiveSessionHistorySnapshot = LiveSessionHistorySnapshot(
		segmentId = segmentId,
		session = SessionHistoryQuery.Found(
			SessionHistory(
				segmentId = segmentId,
				capture = capture,
				qualifiedSources = emptySet(),
				steps = steps,
			),
		),
		activity = ActivityHistoryQuery.Found(activity),
		pressure = PressureSessionHistoryQuery.Found(
			PressureSessionHistory(
				segmentId = segmentId,
				capture = capture,
				qualifiedSources = if (pressure.hasRetainedObservation) {
					setOf(HistorySource.PRESSURE)
				} else {
					emptySet()
				},
				pressure = pressure,
			),
		),
	)

	private fun notFoundHistory(segmentId: Long) = LiveSessionHistorySnapshot(
		segmentId = segmentId,
		session = SessionHistoryQuery.NotFound,
		activity = ActivityHistoryQuery.NotFound,
		pressure = PressureSessionHistoryQuery.NotFound,
	)

	private fun resolvingState(steps: TripDetailStepsState = TripDetailStepsState.Materializing) =
		TripDetailState.Loaded(
			trip = sampleTrip,
			steps = steps,
			sourcePresentation = TripDetailSourcePresentation.Resolving,
		)

	private fun exactCapture(
		sources: Set<HistorySource>,
		controlSources: Set<HistorySource> = emptySet(),
	) = HistoryCapture.Exact(
		listOf(
			HistoryCaptureRevision(
				revision = 1L,
				effectiveAt = EpochMs(1_000L),
				capturedSources = sources,
				controlSources = controlSources,
			),
		),
	)

	private fun HistoryCapture.capturesOnlyActivity(): Boolean =
		(this as? HistoryCapture.Exact)?.revisions?.all { revision ->
			revision.capturedSources == setOf(HistorySource.ACTIVITY)
		} == true

	private fun unavailableActivity(capturesOnlyActivity: Boolean) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(5_000L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
		capturesOnlyActivity = capturesOnlyActivity,
	)

	private fun unavailablePressure() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE),
	)

	private fun completeSteps(count: Long) = StepsHistory(
		count = count,
		availability = HistoryAvailability.AVAILABLE,
		evidence = if (count == 0L) HistoryEvidence.ACTIVE else HistoryEvidence.RECORDED,
		productState = HistoryProductState.READY,
		coverage = StepsHistoryCoverage.COMPLETE,
	)

	private fun notCapturedSteps() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.PARTIAL,
		coverage = StepsHistoryCoverage.NONE,
		causes = setOf(StepsHistoryCause.SOURCE_NOT_CAPTURED),
	)
}
