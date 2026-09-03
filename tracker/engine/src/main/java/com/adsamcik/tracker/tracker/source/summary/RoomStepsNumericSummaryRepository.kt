package com.adsamcik.tracker.tracker.source.summary

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.tracker.source.deletion.StepsDailySummaryRepairComposer
import com.adsamcik.tracker.tracker.source.deletion.StepsDayNumericComposition
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPlan
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPreflight
import com.adsamcik.tracker.tracker.source.deletion.stepsNumericReadQueryBounds
import java.time.DateTimeException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Room-backed, read-only Steps totals for completeness-sensitive product decisions. */
@Singleton
class RoomStepsNumericSummaryRepository @Inject constructor(
	private val database: AppDatabase,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : StepsNumericSummaryRepository {
	override fun observe(request: StepsNumericSummaryRequest): Flow<StepsNumericSummary> =
		database.invalidationTracker.createFlow(
			*NUMERIC_SUMMARY_DEPENDENCY_TABLES,
			emitInitialState = true,
		)
			.conflate()
			.map { read(request) }
			.catch { failure ->
				if (failure is CancellationException) {
					throw failure
				}
				emit(storageUnavailable())
			}
			.distinctUntilChanged()

	override suspend fun read(request: StepsNumericSummaryRequest): StepsNumericSummary =
		withContext(ioDispatcher) {
			try {
				readInTransaction(request)
			} catch (cancellation: CancellationException) {
				currentCoroutineContext().ensureActive()
				if (database.isOpen) {
					throw cancellation
				}
				storageUnavailable()
			} catch (_: Exception) {
				storageUnavailable()
			}
		}

	private suspend fun readInTransaction(
		request: StepsNumericSummaryRequest,
	): StepsNumericSummary {
		return database.useReaderConnection { connection ->
			connection.deferredTransaction {
				val summaries = database.dailySummaryDao().getBetween(
					request.firstEpochDay,
					request.lastEpochDayInclusive,
				)
				val summariesByDay = summaries.associateBy(DailySummaryEntity::dateEpochDay)
				if (summariesByDay.size != summaries.size || summariesByDay.keys.any { day ->
					day !in request.firstEpochDay..request.lastEpochDayInclusive
				}) {
					return@deferredTransaction calendarUnavailable()
				}
				val zoneByDay = linkedMapOf<Long, ZoneId>()
				for (epochDay in request.firstEpochDay..request.lastEpochDayInclusive) {
					val storedZone = summariesByDay[epochDay]?.let { summary ->
						parseZone(
							summary.calendarZoneId
								?: return@deferredTransaction calendarUnavailable(),
						) ?: return@deferredTransaction calendarUnavailable()
					}
					zoneByDay[epochDay] = storedZone
						?: parseZone(request.fallbackCalendarZoneId)
						?: return@deferredTransaction calendarUnavailable()
				}
				if (stepsNumericReadQueryBounds(zoneByDay) == null) {
					return@deferredTransaction calendarUnavailable()
				}
				StepsDailySummaryRepairComposer(database)
					.composeForNumericRead(zoneByDay)
					.toNumericSummary(request, zoneByDay)
			}
		}
	}

	private fun parseZone(zoneId: String): ZoneId? = try {
		ZoneId.of(zoneId)
	} catch (_: DateTimeException) {
		null
	}

	private fun calendarUnavailable() = StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
	)

	private fun storageUnavailable() = StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE,
	)

	private companion object {
		val NUMERIC_SUMMARY_DEPENDENCY_TABLES = arrayOf(
			"daily_summary",
			"logical_tracking_session",
			"source_service_run",
			"session_manifest_version",
			"session_manifest_source",
			"source_session_completeness",
			"source_product_projection_lane",
			"source_projection_failure",
			"source_evidence_state",
			"source_deletion_fence",
			"step_fact_revision",
			"session_segment",
		)
	}
}

internal fun StepsDayRepairPreflight.toNumericSummary(
	request: StepsNumericSummaryRequest,
	zoneByDay: Map<Long, ZoneId>,
): StepsNumericSummary = when (this) {
	is StepsDayRepairPreflight.Ready -> plans.toNumericSummary(request, zoneByDay)
	StepsDayRepairPreflight.Materializing -> StepsNumericSummary.Materializing
	is StepsDayRepairPreflight.Unsupported -> StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
	)
}

private fun List<StepsDayRepairPlan>.toNumericSummary(
	request: StepsNumericSummaryRequest,
	zoneByDay: Map<Long, ZoneId>,
): StepsNumericSummary {
	val expectedDays = (request.firstEpochDay..request.lastEpochDayInclusive).toList()
	val orderedPlans = sortedBy { plan -> plan.epochDay }
	if (orderedPlans.map { plan -> plan.epochDay } != expectedDays ||
		orderedPlans.any { plan -> zoneByDay[plan.epochDay] != plan.zoneId }
	) {
		return sourceEvidenceUnavailable()
	}
	return orderedPlans.summarizeNumericPlans()
}

private fun List<StepsDayRepairPlan>.summarizeNumericPlans(): StepsNumericSummary = when {
	any { plan -> plan.numericSteps == StepsDayNumericComposition.PartialCapture } ->
		StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
	all { plan -> plan.numericSteps == StepsDayNumericComposition.NotCaptured } ->
		StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED)
	any { plan -> plan.numericSteps == StepsDayNumericComposition.NotCaptured } ->
		StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
	else -> completedNumericSummary()
}

private fun List<StepsDayRepairPlan>.completedNumericSummary(): StepsNumericSummary {
	val days = mapNotNull { plan ->
		(plan.numericSteps as? StepsDayNumericComposition.Complete)?.let { complete ->
			StepsNumericDay(plan.epochDay, complete.steps)
		}
	}
	return if (days.size == size) {
		StepsNumericSummary.Ready(days)
	} else {
		sourceEvidenceUnavailable()
	}
}

private fun sourceEvidenceUnavailable() = StepsNumericSummary.Unverifiable(
	StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
)
