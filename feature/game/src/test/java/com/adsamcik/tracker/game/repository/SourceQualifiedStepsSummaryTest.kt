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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
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

		val summary = summaryFlow(repository).first()

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

		val summary = summaryFlow(repository).first()

		summary.stepsToday shouldBe QualifiedStepCount.Ready(321)
		summary.stepsWeek shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
		)
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
		).first()
		var storageReads = 0
		val storageUnavailable = summaryFlow(
			FakeStepsNumericSummaryRepository {
				storageReads += 1
				StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE)
			},
			materializingRetryDelaysMs = listOf(10L, 20L),
		).toList().single()

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
		).first()

		summary.stepsToday shouldBe QualifiedStepCount.Ready(9_000)
		summary.stepsWeek shouldBe QualifiedStepCount.Ready(17_000)
	}

	@Test
	fun `new invalidation cancels a stale in-flight read before it can emit`() = runTest {
		val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
		val firstWeeklyStarted = CompletableDeferred<Unit>()
		val firstWeeklyCancelled = CompletableDeferred<Unit>()
		var call = 0
		val repository = FakeStepsNumericSummaryRepository { request ->
			when (call++) {
				0 -> readyFor(request) { 1L }
				1 -> {
					firstWeeklyStarted.complete(Unit)
					try {
						awaitCancellation()
					} finally {
						firstWeeklyCancelled.complete(Unit)
					}
				}
				2 -> readyFor(request) { 2L }
				else -> readyFor(request) { 3L }
			}
		}
		val result = async {
			summaryFlow(
				repository = repository,
				invalidations = invalidations.onStart { emit(Unit) },
			).first()
		}

		firstWeeklyStarted.await()
		invalidations.emit(Unit)

		result.await().stepsToday shouldBe QualifiedStepCount.Ready(2)
		firstWeeklyCancelled.await()
	}

	@Test
	fun `materializing retry is finite and backs off only while materializing`() = runTest {
		var calls = 0
		val repository = FakeStepsNumericSummaryRepository {
			calls += 1
			StepsNumericSummary.Materializing
		}

		val results = summaryFlow(
			repository = repository,
			materializingRetryDelaysMs = listOf(10L, 20L),
		).toList()

		results shouldHaveSize 3
		results.forEach { summary ->
			summary.stepsToday shouldBe QualifiedStepCount.Unavailable(
				QualifiedStepCountUnavailableReason.MATERIALIZING,
			)
			summary.stepsWeek shouldBe QualifiedStepCount.Unavailable(
				QualifiedStepCountUnavailableReason.MATERIALIZING,
			)
		}
		calls shouldBe 6
	}

	@Test
	fun `collector cancellation stops a pending materializing retry`() = runTest {
		var calls = 0
		val repository = FakeStepsNumericSummaryRepository {
			calls += 1
			StepsNumericSummary.Materializing
		}

		summaryFlow(
			repository = repository,
			materializingRetryDelaysMs = listOf(100L, 200L),
		).first()
		advanceTimeBy(1_000L)

		calls shouldBe 2
	}

	private fun summaryFlow(
		repository: StepsNumericSummaryRepository,
		invalidations: Flow<Unit> = flowOf(Unit),
		weeklyGoal: Int = 70_000,
		weeklyDailyLimit: Float = 0.5f,
		materializingRetryDelaysMs: List<Long> = emptyList(),
	): Flow<StepsSummaryData> = sourceQualifiedStepsSummaryFlow(
		repository = repository,
		invalidations = invalidations,
		dailyGoal = flowOf(10_000),
		weeklyGoal = flowOf(weeklyGoal),
		weeklyDailyLimit = flowOf(weeklyDailyLimit),
		currentDateTime = { now },
		currentLocale = { Locale.GERMANY },
		materializingRetryDelaysMs = materializingRetryDelaysMs,
	)
}

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
