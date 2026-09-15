package com.adsamcik.tracker.tracker.source.summary

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.RetainedStepsWallBounds
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetrics
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetricsDecision
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetricsRepository
import com.adsamcik.tracker.tracker.source.deletion.StepsDailySummaryRepairComposer
import com.adsamcik.tracker.tracker.source.deletion.StepsDayNumericComposition
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPlan
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPreflight
import com.adsamcik.tracker.tracker.source.deletion.StepsRetainedDayAuthorityDiscovery
import com.adsamcik.tracker.tracker.source.deletion.stepsNumericReadQueryBounds
import java.io.DataOutputStream
import java.io.DigestOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-only retained lifetime and best-day Steps decisions. */
@Singleton
class RoomStepsRetainedMetricsRepository @Inject constructor(
	private val database: AppDatabase,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : StepsRetainedMetricsRepository {
	override suspend fun readDecision(): StepsRetainedMetricsDecision = withContext(ioDispatcher) {
		try {
			database.useReaderConnection { connection ->
				connection.deferredTransaction {
					val before = database.sourceEvidenceStateDao().get()
						?: return@deferredTransaction StepsRetainedMetricsDecision.StorageUnavailable
					val retained = readRetainedDecision(before.retainedFromMs)
					val after = database.sourceEvidenceStateDao().get()
						?: return@deferredTransaction StepsRetainedMetricsDecision.StorageUnavailable
					check(before.revision == after.revision) {
						"Source evidence changed during a retained Steps decision snapshot"
					}
					StepsRetainedMetricsDecision.Snapshot(
						sourceEvidenceRevision = before.revision,
						result = retained.result,
						sourceResultDigest = retained.sourceResultDigest,
					)
				}
			}
		} catch (cancellation: CancellationException) {
			currentCoroutineContext().ensureActive()
			if (database.isOpen) throw cancellation
			StepsRetainedMetricsDecision.StorageUnavailable
		} catch (_: Exception) {
			StepsRetainedMetricsDecision.StorageUnavailable
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun readRetainedDecision(retainedFromMs: Long?): RetainedDecisionValue {
		val digester = StepsRetainedResultDigester(retainedFromMs)
		if (retainedFromMs?.let { it < 0L } == true) {
			return unavailable(digester, StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		val historyDao = database.trackingHistoryReadDao()
		if (historyDao.hasOrphanRetainedStepsManifestSource(
			stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		) ||
			historyDao.hasLegacyUnverifiableStepsPresentation(retainedFromMs) ||
			database.stepFactRevisionDao().hasUndiscoverableRetainedStepsFacts(retainedFromMs)
		) return unavailable(digester, StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		val nativeBounds = retainedNativeBounds(retainedFromMs)
			?: return unavailable(digester, StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		val factBounds = database.stepFactRevisionDao().retainedStepsFactWallBounds(retainedFromMs)
			.toCandidateBounds()
			?: return unavailable(digester, StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		val importedBounds = database.importedStepsDao().retainedRunWallBounds(retainedFromMs)
			.toCandidateBounds()
			?: return unavailable(digester, StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		val bounds = nativeBounds + factBounds + importedBounds
		if (bounds.candidateCount == 0L) {
			return completed(digester, notCaptured())
		}
		val firstWallTimeMs = bounds.firstWallTimeMs
			?: return unavailable(digester, StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		val lastWallTimeMs = bounds.lastWallTimeMs
			?: return unavailable(digester, StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		if (retainedFromMs != null && lastWallTimeMs <= retainedFromMs && !bounds.hasOpenNativeRun) {
			return completed(digester, notCaptured())
		}
		val retainedFirstWallTimeMs = retainedFromMs?.let { maxOf(firstWallTimeMs, it) }
			?: firstWallTimeMs
		val firstEpochDay = conservativeEpochDay(retainedFirstWallTimeMs, subtractOffset = true)
			?: return unavailable(digester, StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE)
		val lastEpochDay = conservativeEpochDay(lastWallTimeMs, subtractOffset = false)
			?: return unavailable(digester, StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE)
		val daySpan = try {
			Math.subtractExact(lastEpochDay, firstEpochDay)
		} catch (_: ArithmeticException) {
			return unavailable(digester, StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE)
		}
		if (daySpan < 0L || daySpan / AUTHORITY_PAGE_DAYS >= MAX_AUTHORITY_PAGES) {
			return unavailable(digester, StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}

		val accumulator = StepsRetainedMetricsAccumulator()
		val pendingZones = linkedMapOf<Long, ZoneId>()
		var priorEpochDay: Long? = null
		var priorZoneId: ZoneId? = null
		var terminal: StepsRetainedMetrics? = null
		val composer = StepsDailySummaryRepairComposer(database)
		var pageFirstDay = firstEpochDay
		while (true) {
			currentCoroutineContext().ensureActive()
			val pageLastDay = minOf(
				lastEpochDay,
				try {
					Math.addExact(pageFirstDay, AUTHORITY_PAGE_DAYS - 1L)
				} catch (_: ArithmeticException) {
					lastEpochDay
				},
			)
			val discovery = composer.discoverRetainedDayAuthorities(
				firstEpochDay = pageFirstDay,
				lastEpochDayInclusive = pageLastDay,
				retainedFromMs = retainedFromMs,
			)
			if (discovery !is StepsRetainedDayAuthorityDiscovery.Ready) {
				terminal = sourceUnavailable()
			} else {
				for ((epochDay, zoneId) in discovery.zoneByDay) {
					if (epochDay !in pageFirstDay..pageLastDay ||
						priorEpochDay?.let { epochDay <= it } == true
					) return unavailable(digester, StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE)
					digester.addAuthority(epochDay, zoneId.id)
					val previousDay = priorEpochDay
					val isContiguous = previousDay != null && previousDay != Long.MAX_VALUE &&
						epochDay == previousDay + 1L
					if (isContiguous && stepsNumericReadQueryBounds(
							linkedMapOf(previousDay to requireNotNull(priorZoneId), epochDay to zoneId),
						) == null
					) return unavailable(digester, StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE)
					if (pendingZones.isNotEmpty() &&
						(!isContiguous || pendingZones.size == AUTHORITY_PAGE_DAYS.toInt())
					) {
						terminal = composePending(
							composer,
							pendingZones,
							accumulator,
							terminal,
							retainedFromMs,
						)
						pendingZones.clear()
					}
					pendingZones[epochDay] = zoneId
					priorEpochDay = epochDay
					priorZoneId = zoneId
				}
			}
			if (pageLastDay == lastEpochDay) break
			pageFirstDay = try {
				Math.addExact(pageLastDay, 1L)
			} catch (_: ArithmeticException) {
				return unavailable(digester, StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE)
			}
		}
		if (pendingZones.isNotEmpty()) {
			terminal = composePending(
				composer,
				pendingZones,
				accumulator,
				terminal,
				retainedFromMs,
			)
		}
		return completed(digester, terminal ?: accumulator.result())
	}

	private suspend fun composePending(
		composer: StepsDailySummaryRepairComposer,
		zoneByDay: Map<Long, ZoneId>,
		accumulator: StepsRetainedMetricsAccumulator,
		current: StepsRetainedMetrics?,
		retainedFromMs: Long?,
	): StepsRetainedMetrics? {
		if (current is StepsRetainedMetrics.Unverifiable) return current
		return when (
			val preflight = composer.composeForRetainedNumericRead(zoneByDay, retainedFromMs)
		) {
			StepsDayRepairPreflight.Materializing -> current ?: StepsRetainedMetrics.Materializing
			is StepsDayRepairPreflight.Unsupported -> sourceUnavailable()
			is StepsDayRepairPreflight.Ready -> accumulator.consume(preflight.plans, zoneByDay) ?: current
		}
	}

	private suspend fun retainedNativeBounds(retainedFromMs: Long?): CandidateBounds? {
		val dao = database.trackingHistoryReadDao()
		var afterServiceRunId: String? = null
		var result = CandidateBounds.EMPTY
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = dao.retainedStepsAuthorityServiceRunIdPage(
				stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				afterServiceRunId = afterServiceRunId,
				limit = READ_PAGE_SIZE,
			)
			val ordered = afterServiceRunId?.let { prior -> listOf(prior) + page } ?: page
			if (page.any(String::isBlank) || ordered.zipWithNext().any { (left, right) -> right <= left }) {
				return null
			}
			val runs = dao.serviceRuns(page)
			if (runs.size != page.size || runs.map { it.serviceRunId }.toSet() != page.toSet()) return null
			for (run in runs) {
				if (run.startedAtMs < 0L || run.completedAtMs?.let { it < run.startedAtMs } == true) return null
				val endMs = run.completedAtMs ?: try {
					maxOf(
						Math.addExact(run.startedAtMs, 1L),
						retainedFromMs?.let { floor -> Math.addExact(floor, MILLIS_PER_DAY) }
							?: Long.MIN_VALUE,
					)
				} catch (_: ArithmeticException) {
					return null
				}
				result += CandidateBounds(
					firstWallTimeMs = run.startedAtMs,
					lastWallTimeMs = endMs,
					candidateCount = 1L,
					hasOpenNativeRun = run.completedAtMs == null,
				)
			}
			if (page.size < READ_PAGE_SIZE) return result
			afterServiceRunId = page.last()
		}
	}

	private fun RetainedStepsWallBounds.toCandidateBounds(): CandidateBounds? {
		if (candidateCount < 0L || invalidCount != 0L) return null
		if (candidateCount == 0L) {
			return if (firstWallTimeMs == null && lastWallTimeMs == null) CandidateBounds.EMPTY else null
		}
		val first = firstWallTimeMs ?: return null
		val last = lastWallTimeMs ?: return null
		return if (first >= 0L && last >= first) {
			CandidateBounds(first, last, candidateCount)
		} else {
			null
		}
	}

	private fun conservativeEpochDay(wallTimeMs: Long, subtractOffset: Boolean): Long? = try {
		val shifted = if (subtractOffset) {
			Math.subtractExact(wallTimeMs, MAX_ZONE_OFFSET_MS)
		} else {
			Math.addExact(wallTimeMs, MAX_ZONE_OFFSET_MS)
		}
		Math.floorDiv(shifted, MILLIS_PER_DAY)
	} catch (_: ArithmeticException) {
		null
	}

	private fun completed(
		digester: StepsRetainedResultDigester,
		result: StepsRetainedMetrics,
	) = RetainedDecisionValue(result, digester.finish(result))

	private fun unavailable(
		digester: StepsRetainedResultDigester,
		reason: StepsNumericUnverifiableReason,
	) = completed(digester, StepsRetainedMetrics.Unverifiable(reason))

	private fun sourceUnavailable() = StepsRetainedMetrics.Unverifiable(
		StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
	)

	private fun notCaptured() = StepsRetainedMetrics.Unverifiable(
		StepsNumericUnverifiableReason.NOT_CAPTURED,
	)

	private companion object {
		const val READ_PAGE_SIZE = 256
		const val AUTHORITY_PAGE_DAYS = 370L
		const val MAX_AUTHORITY_PAGES = 4_096L
		const val MAX_ZONE_OFFSET_MS = 18L * 60L * 60_000L
		const val MILLIS_PER_DAY = 86_400_000L
	}
}

private data class CandidateBounds(
	val firstWallTimeMs: Long?,
	val lastWallTimeMs: Long?,
	val candidateCount: Long,
	val hasOpenNativeRun: Boolean = false,
) {
	operator fun plus(other: CandidateBounds): CandidateBounds {
		if (candidateCount < 0L || other.candidateCount < 0L) return INVALID
		val totalCount = try {
			Math.addExact(candidateCount, other.candidateCount)
		} catch (_: ArithmeticException) {
			return INVALID
		}
		return CandidateBounds(
			firstWallTimeMs = listOfNotNull(firstWallTimeMs, other.firstWallTimeMs).minOrNull(),
			lastWallTimeMs = listOfNotNull(lastWallTimeMs, other.lastWallTimeMs).maxOrNull(),
			candidateCount = totalCount,
			hasOpenNativeRun = hasOpenNativeRun || other.hasOpenNativeRun,
		)
	}

	companion object {
		val EMPTY = CandidateBounds(null, null, 0L)
		val INVALID = CandidateBounds(null, null, -1L)
	}
}

private data class RetainedDecisionValue(
	val result: StepsRetainedMetrics,
	val sourceResultDigest: String,
)

/** Bounded fold over already-qualified retained day batches. */
internal class StepsRetainedMetricsAccumulator {
	private var totalSteps = 0L
	private var bestDailySteps = 0L
	private var qualifiedDayCount = 0L

	fun consume(
		plans: List<StepsDayRepairPlan>,
		zoneByDay: Map<Long, ZoneId>,
	): StepsRetainedMetrics? {
		val orderedPlans = plans.sortedBy(StepsDayRepairPlan::epochDay)
		if (orderedPlans.map(StepsDayRepairPlan::epochDay) != zoneByDay.keys.sorted() ||
			orderedPlans.any { plan -> zoneByDay[plan.epochDay] != plan.zoneId }
		) return sourceUnavailableResult()
		for (plan in orderedPlans) {
			when (val numeric = plan.numericSteps) {
				is StepsDayNumericComposition.Complete -> {
					if (numeric.steps > Long.MAX_VALUE - totalSteps || qualifiedDayCount == Long.MAX_VALUE) {
						return sourceUnavailableResult()
					}
					totalSteps += numeric.steps
					bestDailySteps = maxOf(bestDailySteps, numeric.steps)
					qualifiedDayCount += 1L
				}
				StepsDayNumericComposition.NotCaptured -> Unit
				StepsDayNumericComposition.PartialCapture -> return StepsRetainedMetrics.Unverifiable(
					StepsNumericUnverifiableReason.PARTIAL_CAPTURE,
				)
			}
		}
		return null
	}

	fun result(): StepsRetainedMetrics = if (qualifiedDayCount == 0L) {
		StepsRetainedMetrics.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED)
	} else {
		StepsRetainedMetrics.Ready(totalSteps, bestDailySteps, qualifiedDayCount)
	}
}

private fun sourceUnavailableResult() = StepsRetainedMetrics.Unverifiable(
	StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
)

/** Streaming deterministic digest; retained day authority is never accumulated without a bound. */
internal class StepsRetainedResultDigester(retainedFromMs: Long?) {
	private val digest = MessageDigest.getInstance("SHA-256")
	private val output = DataOutputStream(DigestOutputStream(DiscardingOutputStream, digest))
	private var previousEpochDay: Long? = null
	private var finished = false

	init {
		output.writeInt(DIGEST_VERSION)
		output.writeBoolean(retainedFromMs != null)
		retainedFromMs?.let(output::writeLong)
	}

	fun addAuthority(epochDay: Long, zoneId: String) {
		check(!finished) { "Retained Steps digest is already complete" }
		check(previousEpochDay?.let { epochDay > it } != false) {
			"Retained Steps digest authority must be strictly increasing"
		}
		output.writeByte(AUTHORITY_TAG)
		output.writeLong(epochDay)
		output.writeSizedUtf8(zoneId)
		previousEpochDay = epochDay
	}

	fun finish(result: StepsRetainedMetrics): String {
		check(!finished) { "Retained Steps digest can be finalized once" }
		finished = true
		output.writeByte(RESULT_TAG)
		when (result) {
			is StepsRetainedMetrics.Ready -> {
				output.writeByte(READY_TAG)
				output.writeLong(result.totalSteps)
				output.writeLong(result.bestDailySteps)
				output.writeLong(result.qualifiedDayCount)
			}
			StepsRetainedMetrics.Materializing -> output.writeByte(MATERIALIZING_TAG)
			is StepsRetainedMetrics.Unverifiable -> {
				output.writeByte(UNVERIFIABLE_TAG)
				output.writeSizedUtf8(result.reason.name)
			}
		}
		output.flush()
		return digest.digest().toLowerHex()
	}

	private companion object {
		const val DIGEST_VERSION = 1
		const val AUTHORITY_TAG = 1
		const val RESULT_TAG = 2
		const val READY_TAG = 1
		const val MATERIALIZING_TAG = 2
		const val UNVERIFIABLE_TAG = 3
	}
}

private object DiscardingOutputStream : OutputStream() {
	override fun write(value: Int): Unit = Unit
	override fun write(buffer: ByteArray, offset: Int, length: Int): Unit = Unit
}

private fun DataOutputStream.writeSizedUtf8(value: String) {
	val encoded = value.toByteArray(StandardCharsets.UTF_8)
	writeInt(encoded.size)
	write(encoded)
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
	for (byte in this@toLowerHex) {
		val unsigned = byte.toInt() and 0xff
		append(HEX_DIGITS[unsigned ushr 4])
		append(HEX_DIGITS[unsigned and 0x0f])
	}
}

private const val HEX_DIGITS = "0123456789abcdef"
