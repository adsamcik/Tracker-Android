package com.adsamcik.tracker.game.goals

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.StepsGoalEffectWriteResult
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarAuthority
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionWindow
import com.adsamcik.tracker.stats.api.repository.StepsNumericExactDecisionRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Repairs only already-recorded goal periods touched by one source-local dirty day. */
@Singleton
internal class StepsGoalHistoricalReconciler @Inject constructor(
	private val database: AppDatabase,
	private val source: StepsNumericDecisionRepository,
	private val dispatchers: DispatchersProvider,
	private val startupGate: TrackingStartupGate,
) {
	suspend fun reconcileNext(observedAtMs: Long): StepsGoalHistoricalReconcileResult {
		require(observedAtMs >= 0L)
		val queued = try {
			withContext(dispatchers.io) { database.stepsGoalRepairDayDao().next() }
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return retryable(StepsGoalHistoricalRetryableReason.STORAGE_UNAVAILABLE)
		} ?: return StepsGoalHistoricalReconcileResult.Empty

		val effects = try {
			withContext(dispatchers.io) {
				database.stepsGoalEffectDao().getStaleAffectedByDay(
					epochDay = queued.epochDay,
					sourceEvidenceRevision = queued.sourceEvidenceRevision,
					limit = MAX_EFFECTS_PER_PASS,
				)
			}
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return retryable(StepsGoalHistoricalRetryableReason.STORAGE_UNAVAILABLE)
		}
		val invalidAuthority = effects.any { effect ->
			effect.calendarAuthority != StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE &&
				effect.toExactRequest() == null
		}
		if (invalidAuthority) {
			return retryable(StepsGoalHistoricalRetryableReason.STORAGE_UNAVAILABLE)
		}
		val repairable = effects.mapNotNull { effect -> effect.toExactRequest()?.let { effect to it } }
		if (repairable.isEmpty()) return removeWithoutEffects(queued.epochDay, queued.sourceEvidenceRevision)

		val requests = repairable.map { it.second }.distinct()
		val sourceRead = try {
			source.readExactDecisionBatch(requests)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return retryable(StepsGoalHistoricalRetryableReason.STORAGE_UNAVAILABLE)
		}
		val snapshot = sourceRead as? StepsNumericDecisionBatch.Snapshot
			?: return retryable(StepsGoalHistoricalRetryableReason.STORAGE_UNAVAILABLE)
		if (snapshot.windows.size != requests.size ||
			snapshot.windows.map(StepsNumericDecisionWindow::request) != requests.map { it.request } ||
			snapshot.windows.map(StepsNumericDecisionWindow::calendarAuthority) !=
				requests.map(StepsNumericExactDecisionRequest::calendarAuthority) ||
			snapshot.windows.any { window ->
				(window.summary as? StepsNumericSummary.Unverifiable)?.reason ==
					StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE
			}
		) {
			return retryable(StepsGoalHistoricalRetryableReason.STORAGE_UNAVAILABLE)
		}
		if (snapshot.windows.any { it.summary == StepsNumericSummary.Materializing }) {
			return retryable(StepsGoalHistoricalRetryableReason.MATERIALIZING)
		}
		if (snapshot.sourceEvidenceRevision < queued.sourceEvidenceRevision) {
			return retryable(StepsGoalHistoricalRetryableReason.SOURCE_CHANGED)
		}

		val windowByRequest = requests.zip(snapshot.windows).toMap()
		val expectedGeneration = startupGate.currentGeneration
		return try {
			startupGate.withReadyGenerationOperation(expectedGeneration) {
				withContext(dispatchers.io) {
					database.withTransaction {
						if (database.sourceEvidenceStateDao().get()?.revision !=
							snapshot.sourceEvidenceRevision
						) {
							return@withTransaction retryable(
								StepsGoalHistoricalRetryableReason.SOURCE_CHANGED,
							)
						}
						if (database.stepsGoalRepairDayDao().get(queued.epochDay) != queued) {
							return@withTransaction retryable(
								StepsGoalHistoricalRetryableReason.SOURCE_CHANGED,
							)
						}
						for ((original, request) in repairable) {
							val current = database.stepsGoalEffectDao().get(original.effectIdentity)
							if (current != original) {
								return@withTransaction retryable(
									StepsGoalHistoricalRetryableReason.SOURCE_CHANGED,
								)
							}
							val window = requireNotNull(windowByRequest[request])
							val candidate = current.revisedCandidate(
								window,
								snapshot.sourceEvidenceRevision,
								observedAtMs,
							)
							check(
								database.stepsGoalEffectDao().recordDecision(candidate) !=
									StepsGoalEffectWriteResult.STALE,
							) {
								"Historical Steps goal decision became stale inside its transaction"
							}
						}
						if (!database.stepsGoalEffectDao().hasStaleAffectedByDay(
								epochDay = queued.epochDay,
								sourceEvidenceRevision = queued.sourceEvidenceRevision,
							)
						) {
							check(
								database.stepsGoalRepairDayDao().removeIfExact(
									queued.epochDay,
									queued.sourceEvidenceRevision,
								) == 1,
							) { "Exact Steps goal repair day could not be acknowledged" }
						}
						StepsGoalHistoricalReconcileResult.Applied(
							epochDay = queued.epochDay,
							effectCount = repairable.size,
						)
					}
				}
			} ?: retryable(StepsGoalHistoricalRetryableReason.STARTUP_GENERATION_CHANGED)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			retryable(StepsGoalHistoricalRetryableReason.STORAGE_UNAVAILABLE)
		}
	}

	private suspend fun removeWithoutEffects(
		epochDay: Long,
		sourceEvidenceRevision: Long,
	): StepsGoalHistoricalReconcileResult = try {
		withContext(dispatchers.io) {
			if (database.stepsGoalRepairDayDao().removeIfExact(epochDay, sourceEvidenceRevision) == 1) {
				StepsGoalHistoricalReconcileResult.Applied(epochDay, 0)
			} else {
				retryable(StepsGoalHistoricalRetryableReason.SOURCE_CHANGED)
			}
		}
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		retryable(StepsGoalHistoricalRetryableReason.STORAGE_UNAVAILABLE)
	}

	private fun StepsGoalEffectEntity.toExactRequest(): StepsNumericExactDecisionRequest? {
		if (calendarAuthority == StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE) return null
		val parsed = mutableListOf<StepsNumericCalendarDay>()
		for (line in calendarAuthority.lineSequence()) {
			val separator = line.indexOf('=')
			if (separator <= 0 || separator == line.lastIndex) return null
			val epochDay = line.substring(0, separator).toLongOrNull() ?: return null
			val zoneId = line.substring(separator + 1)
			try {
				ZoneId.of(zoneId)
			} catch (_: DateTimeException) {
				return null
			}
			parsed += StepsNumericCalendarDay(epochDay, zoneId)
		}
		val request = StepsNumericSummaryRequest(
			firstEpochDay = periodStartEpochDay,
			lastEpochDayInclusive = qualifiedThroughEpochDay,
			fallbackCalendarZoneId = parsed.firstOrNull()?.zoneId ?: return null,
		)
		val authority = runCatching { StepsNumericCalendarAuthority.Exact(parsed) }.getOrNull()
			?: return null
		return runCatching { StepsNumericExactDecisionRequest(request, authority) }.getOrNull()
	}

	private fun StepsGoalEffectEntity.revisedCandidate(
		window: StepsNumericDecisionWindow,
		sourceEvidenceRevision: Long,
		observedAtMs: Long,
	): StepsGoalEffectEntity {
		val qualified = when (periodKind) {
			StepsGoalEffectEntity.PERIOD_DAY ->
				(window.summary as? StepsNumericSummary.Ready)?.totalSteps
			StepsGoalEffectEntity.PERIOD_WEEK ->
				(window.summary as? StepsNumericSummary.Ready)?.let { ready ->
					WeeklyProgressCalculator.cappedTotal(
						dailySteps = ready.days.associate { day ->
							LocalDate.ofEpochDay(day.epochDay) to
								day.steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
						},
						today = LocalDate.ofEpochDay(qualifiedThroughEpochDay),
						todayLiveSteps = null,
						weeklyGoal = targetSteps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
						dailyLimit = Float.fromBits(requireNotNull(weeklyDailyLimitBits)),
					).toLong()
				}
			else -> null
		}
		val complete = qualified != null && qualified >= targetSteps
		val state = when {
			window.summary is StepsNumericSummary.Unverifiable ->
				StepsGoalEffectEntity.STATE_UNVERIFIABLE
			complete -> StepsGoalEffectEntity.STATE_READY_COMPLETE
			else -> StepsGoalEffectEntity.STATE_READY_INCOMPLETE
		}
		return copy(
			decisionState = state,
			unavailableReason = (window.summary as? StepsNumericSummary.Unverifiable)?.reason?.name,
			qualifiedSteps = qualified,
			sourceAuthorityDigest = window.sourceResultDigest,
			sourceEvidenceRevision = sourceEvidenceRevision,
			effectRevision = 1L,
			desiredPointsMicros = if (complete) completionPointsMicros else 0L,
			desiredXp = if (complete) completionXp else 0,
			firstCompletedAtMs = observedAtMs.takeIf { complete },
			pointsAppliedRevision = 0L,
			xpAppliedRevision = 0L,
			notificationClaimedRevision = null,
			notificationClaimedAtMs = null,
			updatedAtMs = observedAtMs,
		)
	}

	private fun retryable(reason: StepsGoalHistoricalRetryableReason) =
		StepsGoalHistoricalReconcileResult.RetryableFailure(reason)

	private companion object {
		const val MAX_EFFECTS_PER_PASS = 2
	}
}

internal sealed interface StepsGoalHistoricalReconcileResult {
	data object Empty : StepsGoalHistoricalReconcileResult
	data class Applied(val epochDay: Long, val effectCount: Int) : StepsGoalHistoricalReconcileResult
	data class RetryableFailure(
		val reason: StepsGoalHistoricalRetryableReason,
	) : StepsGoalHistoricalReconcileResult
}

internal enum class StepsGoalHistoricalRetryableReason {
	STORAGE_UNAVAILABLE,
	SOURCE_CHANGED,
	STARTUP_GENERATION_CHANGED,
	MATERIALIZING,
}
