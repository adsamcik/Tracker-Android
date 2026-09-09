package com.adsamcik.tracker.game.repository

import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceQualifiedStepsSummaryTest {
	private val now = ZonedDateTime.of(
		2026,
		9,
		2,
		12,
		0,
		0,
		0,
		ZoneId.of("Europe/Prague"),
	)

	@Test
	fun `ready zero today and positive week remain source-qualified`() = runTest {
		val requests = mutableListOf<StepsNumericSummaryRequest>()
		val repository = FakeStepsNumericSummaryRepository { request ->
			requests += request
			readyFor(request) { epochDay ->
				if (request.dayCount == 1) {
					0L
				} else {
					epochDay - request.firstEpochDay + 1L
				}
			}
		}

		val summary = summaryFlow(repository).first {
			it.stepsToday is QualifiedStepCount.Ready && it.stepsWeek is QualifiedStepCount.Ready
		}

		summary.stepsToday shouldBe QualifiedStepCount.Ready(0)
		summary.stepsWeek shouldBe QualifiedStepCount.Ready(6)
		summary.goalDay shouldBe 10_000
		summary.goalWeek shouldBe 70_000
		requests shouldHaveSize 2
		requests[0] shouldBe StepsNumericSummaryRequest(
			firstEpochDay = LocalDate.of(2026, 9, 2).toEpochDay(),
			lastEpochDayInclusive = LocalDate.of(2026, 9, 2).toEpochDay(),
			fallbackCalendarZoneId = "Europe/Prague",
		)
		requests[1] shouldBe StepsNumericSummaryRequest(
			firstEpochDay = LocalDate.of(2026, 8, 31).toEpochDay(),
			lastEpochDayInclusive = LocalDate.of(2026, 9, 2).toEpochDay(),
			fallbackCalendarZoneId = "Europe/Prague",
		)
	}

	@Test
	fun `daily Ready is preserved when the week is partial`() = runTest {
		val repository = FakeStepsNumericSummaryRepository { request ->
			if (request.dayCount == 1) {
				readyFor(request) { 321L }
			} else {
				StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
			}
		}

		val summary = summaryFlow(repository).first {
			it.stepsToday is QualifiedStepCount.Ready &&
				it.stepsWeek == QualifiedStepCount.Unavailable(QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE)
		}

		summary.stepsToday shouldBe QualifiedStepCount.Ready(321)
		summary.stepsWeek shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
		)
	}

	@Test
	fun `daily Ready is visible before the weekly observer produces its first value`() = runTest {
		val weeklyStarted = CompletableDeferred<Unit>()
		val repository = ObservableStepsRepository { request ->
			if (request.dayCount > 1) {
				weeklyStarted.complete(Unit)
				awaitCancellation()
			}
		}
		val results = mutableListOf<StepsSummaryData>()
		backgroundScope.launch { summaryFlow(repository).collect(results::add) }
		runCurrent()
		weeklyStarted.await()
		val dayRequest = repository.requests.single { it.dayCount == 1 }
		repository.emit(dayRequest, readyFor(dayRequest) { 42L })
		runCurrent()
		results.last().stepsToday shouldBe QualifiedStepCount.Ready(42)
		results.last().stepsWeek shouldBe materializing()
	}

	@Test
	fun `materializing and storage outcomes remain independently nonnumeric`() = runTest {
		val materializing = summaryFlow(
			FakeStepsNumericSummaryRepository { request ->
				if (request.dayCount == 1) {
					StepsNumericSummary.Materializing
				} else {
					StepsNumericSummary.Unverifiable(
						StepsNumericUnverifiableReason.PARTIAL_CAPTURE,
					)
				}
			},
		).first { it.stepsWeek == QualifiedStepCount.Unavailable(QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE) }
		var storageReads = 0
		val storageUnavailable = summaryFlow(
			FakeStepsNumericSummaryRepository {
				storageReads += 1
				StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE)
			},
		).toList().last()

		materializing.stepsToday shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.MATERIALIZING,
		)
		materializing.stepsWeek shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
		)
		storageUnavailable.stepsToday shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
		)
		storageUnavailable.stepsWeek shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
		)
		storageReads shouldBe 2
	}

	@Test
	fun `locale week boundary is structural across a year boundary`() {
		val sunday = ZonedDateTime.of(
			2026,
			1,
			4,
			12,
			0,
			0,
			0,
			ZoneId.of("Europe/Prague"),
		)

		stepsCalendarAuthority(sunday, Locale.GERMANY).startOfWeek shouldBe
			LocalDate.of(2025, 12, 29)
		stepsCalendarAuthority(sunday, Locale.US).startOfWeek shouldBe
			LocalDate.of(2026, 1, 4)
	}

	@Test
	fun `weekly Ready caps every qualified day before summing`() = runTest {
		val stepsByDay = mapOf(
			LocalDate.of(2026, 8, 31).toEpochDay() to 12_000L,
			LocalDate.of(2026, 9, 1).toEpochDay() to 5_000L,
			LocalDate.of(2026, 9, 2).toEpochDay() to 9_000L,
		)
		val repository = FakeStepsNumericSummaryRepository { request ->
			readyFor(request) { epochDay -> stepsByDay[epochDay] ?: 9_000L }
		}

		val summary = summaryFlow(
			repository = repository,
			weeklyGoal = 20_000,
			weeklyDailyLimit = 0.3f,
		).first { it.stepsToday is QualifiedStepCount.Ready && it.stepsWeek is QualifiedStepCount.Ready }

		summary.stepsToday shouldBe QualifiedStepCount.Ready(9_000)
		summary.stepsWeek shouldBe QualifiedStepCount.Ready(17_000)
	}

	@Test
	fun `late source-only settlement correction and deletion update without raw invalidation`() = runTest {
		val repository = ObservableStepsRepository()
		val results = mutableListOf<StepsSummaryData>()
		val collection = backgroundScope.launch {
			summaryFlow(repository, invalidations = MutableSharedFlow()).collect(results::add)
		}
		runCurrent()
		repository.requests shouldHaveSize 2
		results.last().stepsToday shouldBe materializing()
		advanceTimeBy(60_000L)
		runCurrent()
		repository.requests shouldHaveSize 2

		repository.emitDays { 12_000L }
		runCurrent()
		results.last().stepsToday shouldBe QualifiedStepCount.Ready(12_000)
		results.last().stepsWeek shouldBe QualifiedStepCount.Ready(36_000)
		repository.emitDays { 500L }
		runCurrent()
		results.last().stepsToday shouldBe QualifiedStepCount.Ready(500)
		repository.emitState(StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE))
		runCurrent()
		results.last().stepsToday shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
		)
		repository.emitDays { 0L }
		runCurrent()
		results.last().stepsToday shouldBe QualifiedStepCount.Ready(0)
		repository.emitState(StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED))
		runCurrent()
		results.last().stepsToday shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.NOT_CAPTURED,
		)
		repository.requests shouldHaveSize 2
		collection.cancel()
		runCurrent()
		repository.activeCount shouldBe 0
	}

	@Test
	fun `calendar replacement cancels old observers and withholds their numbers while loading`() = runTest {
		val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
		val repository = ObservableStepsRepository()
		var clock = now
		val results = mutableListOf<StepsSummaryData>()
		backgroundScope.launch {
			summaryFlow(
				repository = repository,
				invalidations = invalidations,
				currentDateTime = { clock },
			).collect(results::add)
		}
		runCurrent()
		repository.emitDays { 100L }
		runCurrent()
		results.last().stepsToday shouldBe QualifiedStepCount.Ready(100)
		val oldRequests = repository.requests.toList()
		clock = now.plusDays(1)
		invalidations.emit(Unit)
		runCurrent()
		repository.requests shouldHaveSize 4
		repository.activeCount shouldBe 2
		repository.cancelledCount shouldBe 2
		results.last().stepsToday shouldBe materializing()
		results.last().stepsWeek shouldBe materializing()
		oldRequests.forEach { request -> repository.emit(request, readyFor(request) { 999L }) }
		runCurrent()
		results.last().stepsToday shouldBe materializing()
		repository.requests.takeLast(2).forEach { request ->
			request.lastEpochDayInclusive shouldBe clock.toLocalDate().toEpochDay()
			repository.emit(request, readyFor(request) { 7L })
		}
		runCurrent()
		results.last().stepsToday shouldBe QualifiedStepCount.Ready(7)
	}

	@Test
	fun `settings and same-calendar invalidations remap without restarting observers`() = runTest {
		val repository = ObservableStepsRepository()
		val invalidations = MutableSharedFlow<Unit>()
		val dayGoal = MutableStateFlow(10_000)
		val weekGoal = MutableStateFlow(70_000)
		val dailyLimit = MutableStateFlow(0.5f)
		val results = mutableListOf<StepsSummaryData>()
		backgroundScope.launch {
			sourceQualifiedStepsSummaryFlow(
				repository, invalidations, dayGoal, weekGoal, dailyLimit,
				currentDateTime = { now }, currentLocale = { Locale.GERMANY },
			).collect(results::add)
		}
		runCurrent()
		repository.emitDays { 12_000L }
		runCurrent()
		dayGoal.value = 20_000
		weekGoal.value = 20_000
		dailyLimit.value = 0.3f
		invalidations.emit(Unit)
		runCurrent()
		repository.requests shouldHaveSize 2
		repository.cancelledCount shouldBe 0
		results.last().goalDay shouldBe 20_000
		results.last().goalWeek shouldBe 20_000
		results.last().stepsWeek shouldBe QualifiedStepCount.Ready(18_000)
	}

	@Test
	fun `explicit locale and zone invalidations rebind structural authority`() = runTest {
		val repository = ObservableStepsRepository()
		val invalidations = MutableSharedFlow<Unit>()
		var locale = Locale.GERMANY
		var clock = now
		backgroundScope.launch {
			summaryFlow(repository, invalidations, currentDateTime = { clock }, currentLocale = { locale })
				.collect()
		}
		runCurrent()
		locale = Locale.US
		invalidations.emit(Unit)
		runCurrent()
		repository.requests shouldHaveSize 4
		repository.requests.last().firstEpochDay shouldBe LocalDate.of(2026, 8, 30).toEpochDay()
		clock = now.withZoneSameInstant(ZoneId.of("UTC"))
		invalidations.emit(Unit)
		runCurrent()
		repository.requests shouldHaveSize 6
		repository.requests.takeLast(2).forEach { it.fallbackCalendarZoneId shouldBe "UTC" }
		repository.activeCount shouldBe 2
		repository.cancelledCount shouldBe 4
	}

	@Test
	fun `observation is cold and cancellation retires every subscriber`() = runTest {
		val repository = ObservableStepsRepository()
		val summaries = summaryFlow(repository, invalidations = MutableSharedFlow())
		repository.requests shouldHaveSize 0
		val collection = backgroundScope.launch { summaries.collect() }
		runCurrent()
		repository.activeCount shouldBe 2
		collection.cancel()
		runCurrent()
		repository.activeCount shouldBe 0
		repository.cancelledCount shouldBe 2
		advanceTimeBy(60_000L)
		runCurrent()
		repository.requests shouldHaveSize 2
		val resumed = backgroundScope.launch { summaries.collect() }
		runCurrent()
		repository.requests shouldHaveSize 4
		repository.activeCount shouldBe 2
		resumed.cancel()
		runCurrent()
		repository.activeCount shouldBe 0
	}

	private fun summaryFlow(
		repository: StepsNumericSummaryRepository,
		invalidations: Flow<Unit> = flowOf(Unit),
		weeklyGoal: Int = 70_000,
		weeklyDailyLimit: Float = 0.5f,
		currentDateTime: () -> ZonedDateTime = { now },
		currentLocale: () -> Locale = { Locale.GERMANY },
	): Flow<StepsSummaryData> = sourceQualifiedStepsSummaryFlow(
		repository = repository,
		invalidations = invalidations,
		dailyGoal = flowOf(10_000),
		weeklyGoal = flowOf(weeklyGoal),
		weeklyDailyLimit = flowOf(weeklyDailyLimit),
		currentDateTime = currentDateTime,
		currentLocale = currentLocale,
	)
}

private class ObservableStepsRepository(
	private val beforeObserve: suspend (StepsNumericSummaryRequest) -> Unit = {},
) : StepsNumericSummaryRepository {
	val requests = mutableListOf<StepsNumericSummaryRequest>()
	private val states = mutableMapOf<StepsNumericSummaryRequest, MutableStateFlow<StepsNumericSummary>>()
	var activeCount = 0
		private set
	var cancelledCount = 0
		private set

	override fun observe(request: StepsNumericSummaryRequest): Flow<StepsNumericSummary> = flow {
		requests += request
		activeCount += 1
		try {
			beforeObserve(request)
			emitAll(states.getOrPut(request) { MutableStateFlow(StepsNumericSummary.Materializing) })
		} finally {
			activeCount -= 1
			cancelledCount += 1
		}
	}

	override suspend fun read(request: StepsNumericSummaryRequest): StepsNumericSummary =
		error("The product must observe source settlement rather than poll")

	fun emit(request: StepsNumericSummaryRequest, summary: StepsNumericSummary) {
		states.getValue(request).value = summary
	}

	fun emitDays(steps: (Long) -> Long) = requests.distinct().forEach { request ->
		emit(request, readyFor(request, steps))
	}

	fun emitState(summary: StepsNumericSummary) = states.values.forEach { it.value = summary }
}

private fun materializing() = QualifiedStepCount.Unavailable(QualifiedStepCountUnavailableReason.MATERIALIZING)

private class FakeStepsNumericSummaryRepository(
	private val reader: suspend (StepsNumericSummaryRequest) -> StepsNumericSummary,
) : StepsNumericSummaryRepository {
	override fun observe(request: StepsNumericSummaryRequest): Flow<StepsNumericSummary> = flow {
		emit(read(request))
	}

	override suspend fun read(request: StepsNumericSummaryRequest): StepsNumericSummary = reader(request)
}

private fun readyFor(
	request: StepsNumericSummaryRequest,
	steps: (Long) -> Long,
): StepsNumericSummary.Ready = StepsNumericSummary.Ready(
	days = (request.firstEpochDay..request.lastEpochDayInclusive).map { epochDay ->
		StepsNumericDay(epochDay = epochDay, steps = steps(epochDay))
	},
)
