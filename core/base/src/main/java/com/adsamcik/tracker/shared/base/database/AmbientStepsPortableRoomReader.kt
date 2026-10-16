package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_AMBIENT_STEPS_DAY_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_AMBIENT_STEPS_FACT_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_AMBIENT_STEPS_GAP_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Nonempty half-open UTC millisecond selection for a complete all-day Steps archive snapshot.
 */
data class AmbientStepsPortableReadRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
) {
	init {
		require(fromInclusiveMs >= 0L)
		require(toExclusiveMs > fromInclusiveMs)
	}
}

/**
 * Prepared all-day Steps archive, empty selection or a typed failure to verify complete source
 * data.
 */
sealed interface AmbientStepsPortableSnapshot {
	/**
	 * Contains the prepared all-day Steps archive and, for v2 reads, its verified original
	 * count-domain graph.
	 */
	data class Ready(
		val archive: PortableAmbientStepsArchiveV1,
		val authenticatedArchive: PortableAmbientStepsArchiveV2? = null,
	) : AmbientStepsPortableSnapshot
	/**
	 * The selected saved source scope contains no exportable product data.
	 */
	data object NoData : AmbientStepsPortableSnapshot
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(val reason: AmbientStepsPortableReadFailure) : AmbientStepsPortableSnapshot
}

/**
 * Count-domain, source, retention, deletion, materialization or dependency problem preventing a
 * complete Steps snapshot.
 */
enum class AmbientStepsPortableReadFailure {
	COUNT_DOMAIN_GRAPH_UNAVAILABLE,
	SOURCE_AUTHORITY_UNAVAILABLE,
	DELETION_PENDING,
	CORRUPT_RETAINED_STATE,
	RETENTION_CROSSES_FACT,
	MATERIALIZING,
	DEPENDENCY_OVERFLOW,
}

internal enum class AmbientStepsPortableReadCheckpoint {
	TRANSACTION_STARTED,
	AUTHORITY_AUTHENTICATED,
	SNAPSHOT_ASSEMBLED,
}

internal data class AmbientStepsPortableReadLimits(
	val maintenance: AmbientStepsMaintenanceLimits = AmbientStepsMaintenanceLimits(),
	val maximumDays: Int = AmbientStepsPortableFormatV1.MAX_DAYS,
	val maximumFacts: Int = AmbientStepsPortableFormatV1.MAX_FACTS,
	val maximumGaps: Int = AmbientStepsPortableFormatV1.MAX_GAPS,
	val maximumFactsPerDay: Int = AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY,
	val maximumGapsPerDay: Int = AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY,
) {
	init {
		listOf(maximumDays, maximumFacts, maximumGaps, maximumFactsPerDay, maximumGapsPerDay)
			.forEach { require(it in 1 until Int.MAX_VALUE) }
	}
}

/**
 * Read-only Ambient Steps transfer boundary. Every local authority is authenticated inside one
 * Room transaction, while only minimized portable values escape that snapshot.
 */
class AmbientStepsPortableRoomReader(private val database: AppDatabase) {
	/**
	 * Reads one bounded complete all-day Steps v1 snapshot without requiring a count-domain graph.
	 */
	suspend fun read(request: AmbientStepsPortableReadRequest): AmbientStepsPortableSnapshot = read(
		request,
		AmbientStepsPortableReadLimits(),
		{ currentCoroutineContext().ensureActive() },
		includeCountDomainGraph = false,
	)

	/**
	 * Reads one bounded complete all-day Steps snapshot including its verified v2 count-domain graph.
	 */
	suspend fun readV2(request: AmbientStepsPortableReadRequest): AmbientStepsPortableSnapshot = read(
		request,
		AmbientStepsPortableReadLimits(),
		{ currentCoroutineContext().ensureActive() },
		includeCountDomainGraph = true,
	)

	internal suspend fun read(
		request: AmbientStepsPortableReadRequest,
		limits: AmbientStepsPortableReadLimits,
		checkpoint: suspend (AmbientStepsPortableReadCheckpoint) -> Unit,
		includeCountDomainGraph: Boolean = false,
	): AmbientStepsPortableSnapshot = try {
		database.withTransaction {
			checkpoint(AmbientStepsPortableReadCheckpoint.TRANSACTION_STARTED)
			readInTransaction(request, limits, checkpoint, includeCountDomainGraph)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: AmbientStepsMaintenanceLimitExceeded) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.DEPENDENCY_OVERFLOW,
		)
	} catch (_: AmbientStepsPortableLimitExceeded) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.DEPENDENCY_OVERFLOW,
		)
	} catch (_: IllegalArgumentException) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	} catch (_: IllegalStateException) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	} catch (_: ArithmeticException) {
		AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun readInTransaction(
		request: AmbientStepsPortableReadRequest,
		limits: AmbientStepsPortableReadLimits,
		checkpoint: suspend (AmbientStepsPortableReadCheckpoint) -> Unit,
		includeCountDomainGraph: Boolean,
	): AmbientStepsPortableSnapshot {
		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
		)
		val evidence = database.sourceEvidenceStateDao().get()
		if (owner?.owner != SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS || evidence == null) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.SOURCE_AUTHORITY_UNAVAILABLE,
			)
		}
		if (database.ambientStepsFactRevisionDao().countUnrecognizedPayloadRows(
				AmbientStepsFactRevisionEntity.WRITER_ID,
				AmbientStepsFactRevisionEntity.WRITER_VERSION,
			) != 0L
		) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
			)
		}
		// This audit uses each saved fact's original cursor, policy and consent. Current
		// source choices govern new collection only, including after a data-only restore.
		val audit = database.loadAuthenticatedAmbientStepsState(
			limits.maintenance,
			checkpoint = { current ->
				if (current == AmbientStepsMaintenanceCheckpoint.AUTHORITY_AUTHENTICATED) {
					checkpoint(AmbientStepsPortableReadCheckpoint.AUTHORITY_AUTHENTICATED)
				} else {
					currentCoroutineContext().ensureActive()
				}
			},
			authenticateRetractedPayloadAuthority = false,
		)
		val effectiveFacts = audit.lineages.mapNotNull { lineage ->
			lineage.latest.takeIf {
				it.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT
			}
		}
		if (effectiveFacts.isEmpty()) {
			return AmbientStepsPortableSnapshot.NoData
		}
		val retainedFromMs = evidence.retainedFromMs
		if (retainedFromMs != null && effectiveFacts.any {
			requireNotNull(it.windowStartTimeMs) < retainedFromMs
		}) {
			return AmbientStepsPortableSnapshot.Unverifiable(
				AmbientStepsPortableReadFailure.RETENTION_CROSSES_FACT,
			)
		}
		val overlappingFacts = effectiveFacts.filter { fact ->
			requireNotNull(fact.windowEndTimeMs) > request.fromInclusiveMs &&
				requireNotNull(fact.windowStartTimeMs) < request.toExclusiveMs
		}
		if (overlappingFacts.isEmpty()) {
			return AmbientStepsPortableSnapshot.NoData
		}
		val selectedKeys = overlappingFacts.mapTo(linkedSetOf()) { it.dayKey() }
		requirePortableBound(selectedKeys.size <= limits.maximumDays)
		val selectedFacts = effectiveFacts.filter { it.dayKey() in selectedKeys }
		requirePortableBound(selectedFacts.size <= limits.maximumFacts)
		val activeCursors = audit.cursors.filter {
			it.status == AmbientStepsImportCursorEntity.STATUS_ACTIVE
		}
		val effectiveGaps = mutableListOf<AmbientStepsPortableEffectiveGap>()
		audit.gaps.forEach { gap ->
			gap.subtractCoveredFacts(
				effectiveFacts,
				limits.maximumGaps - effectiveGaps.size,
			).forEach { part ->
				effectiveGaps += part
			}
		}
		val factsByDay = selectedFacts.groupBy(AmbientStepsFactRevisionEntity::dayKey)
		var portableGapCount = 0
		val authenticatedDays = mutableListOf<PortableAmbientStepsDayV2>()
		val days = selectedKeys.map { day ->
			val gaps = mutableListOf<PortableAmbientStepsGapV1>()
			effectiveGaps.forEach { gap ->
				gap.clipTo(day, retainedFromMs)?.let { portableGap ->
					requirePortableBound(portableGapCount < limits.maximumGaps)
					requirePortableBound(gaps.size < limits.maximumGapsPerDay)
					gaps += portableGap
					portableGapCount++
				}
			}
			val facts = factsByDay[day].orEmpty()
			val portableDay = day.toPortableDay(facts, gaps, retainedFromMs, limits,
				pendingPrefix = activeCursors.any { it.isMaterializing(day) })
			if (includeCountDomainGraph) {
				val roots = facts.map { fact ->
					PortableCountDomainRootSeed(
						containerIdentity = portableDay.identity.value,
						productIdentity = AmbientStepsPortableOpaqueIdentity.derive(
							AmbientStepsPortableIdentityKind.FACT,
							fact.logicalFactId,
						).value,
						ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
						ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
							fact.writerId,
							fact.writerVersion,
							fact.logicalFactId,
						),
						ownerRevision = fact.semanticRevision,
						ownerEffectChecksum = fact.effectChecksum,
					)
				}
				val graph = when (val graphRead = PortableCountDomainGraphReader(database).read(roots)) {
					is PortableCountDomainGraphRead.Ready -> graphRead.graph
					PortableCountDomainGraphRead.Overflow -> throw AmbientStepsPortableLimitExceeded()
					PortableCountDomainGraphRead.Unproven ->
						return AmbientStepsPortableSnapshot.Unverifiable(
							AmbientStepsPortableReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
						)
					PortableCountDomainGraphRead.Unverifiable ->
						return AmbientStepsPortableSnapshot.Unverifiable(
							AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
						)
				}
				if (!graph.hasCompletePortableOwnerLineages()) {
					return AmbientStepsPortableSnapshot.Unverifiable(
						AmbientStepsPortableReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
					)
				}
				authenticatedDays += PortableAmbientStepsDayV2(portableDay, graph)
			}
			portableDay
		}.sortedWith(PORTABLE_AMBIENT_STEPS_DAY_ORDER)
		val archive = PortableAmbientStepsArchiveV1.create(days)
		val authenticatedArchive = if (includeCountDomainGraph) {
			PortableAmbientStepsArchiveV2.create(authenticatedDays)
		} else {
			null
		}
		checkpoint(AmbientStepsPortableReadCheckpoint.SNAPSHOT_ASSEMBLED)
		return AmbientStepsPortableSnapshot.Ready(archive, authenticatedArchive)
	}
}

private data class AmbientStepsPortableDayKey(
	val epochDay: Long,
	val zoneId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
)

private data class AmbientStepsPortableEffectiveGap(
	val localGapId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val reason: PortableAmbientStepsGapReason,
)

private fun AmbientStepsFactRevisionEntity.dayKey() = AmbientStepsPortableDayKey(
	requireNotNull(structuralEpochDay),
	requireNotNull(storedZoneId),
	requireNotNull(structuralDayStartTimeMs),
	requireNotNull(structuralDayEndTimeMs),
)

private fun AmbientStepsImportCursorEntity.isMaterializing(day: AmbientStepsPortableDayKey): Boolean =
	status == AmbientStepsImportCursorEntity.STATUS_ACTIVE && segmentStartTimeMs < day.endTimeMs &&
		importedThroughTimeMs < day.endTimeMs

private fun AmbientStepsImportGapEntity.subtractCoveredFacts(
	facts: List<AmbientStepsFactRevisionEntity>,
	maximumParts: Int,
): List<AmbientStepsPortableEffectiveGap> {
	requirePortableBound(maximumParts >= 0)
	if (gapEndTimeMs == gapStartTimeMs) {
		return emptyList()
	}
	val coverage = coveredFactIntervals(facts)
	val result = mutableListOf<AmbientStepsPortableEffectiveGap>()
	var remainingStart = gapStartTimeMs
	coverage.forEach { (start, end) ->
		if (end <= remainingStart) {
			return@forEach
		}
		if (start > remainingStart) {
			requirePortableBound(result.size < maximumParts)
			result += AmbientStepsPortableEffectiveGap(
				gapId,
				remainingStart,
				minOf(start, gapEndTimeMs),
				reason.toPortableGapReason(),
			)
		}
		remainingStart = maxOf(remainingStart, end)
	}
	if (remainingStart < gapEndTimeMs) {
		requirePortableBound(result.size < maximumParts)
		result += AmbientStepsPortableEffectiveGap(
			gapId,
			remainingStart,
			gapEndTimeMs,
			reason.toPortableGapReason(),
		)
	}
	return result
}

private fun AmbientStepsImportGapEntity.coveredFactIntervals(
	facts: List<AmbientStepsFactRevisionEntity>,
): List<Pair<Long, Long>> =
	facts.asSequence().filter { fact ->
		fact.provider == provider && fact.sourceInstanceId == sourceInstanceId &&
			fact.registrationGeneration == registrationGeneration &&
			fact.collectedDataEpoch == collectedDataEpoch &&
			requireNotNull(fact.windowEndTimeMs) > gapStartTimeMs &&
			requireNotNull(fact.windowStartTimeMs) < gapEndTimeMs
	}.map { fact ->
		maxOf(gapStartTimeMs, requireNotNull(fact.windowStartTimeMs)) to
			minOf(gapEndTimeMs, requireNotNull(fact.windowEndTimeMs))
	}.sortedBy(Pair<Long, Long>::first).toList()

private fun AmbientStepsPortableEffectiveGap.clipTo(
	day: AmbientStepsPortableDayKey,
	retainedFromMs: Long?,
): PortableAmbientStepsGapV1? {
	val start = maxOf(startTimeMs, day.startTimeMs, retainedFromMs ?: day.startTimeMs)
	val end = minOf(endTimeMs, day.endTimeMs)
	if (end <= start) {
		return null
	}
	val identity = AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.GAP,
		"$localGapId|$start|$end",
	)
	return PortableAmbientStepsGapV1.create(identity, start, end, reason)
}

private fun AmbientStepsPortableDayKey.toPortableDay(
	localFacts: List<AmbientStepsFactRevisionEntity>,
	localGaps: List<PortableAmbientStepsGapV1>,
	retainedFromMs: Long?,
	limits: AmbientStepsPortableReadLimits,
	pendingPrefix: Boolean,
): PortableAmbientStepsDayV1 {
	requirePortableBound(localFacts.size <= limits.maximumFactsPerDay)
	requirePortableBound(localGaps.size <= limits.maximumGapsPerDay)
	val facts = localFacts.map { fact ->
		PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				fact.logicalFactId,
			),
			requireNotNull(fact.windowStartTimeMs),
			requireNotNull(fact.windowEndTimeMs),
			requireNotNull(fact.stepCount),
		)
	}.sortedWith(PORTABLE_AMBIENT_STEPS_FACT_ORDER)
	val gaps = localGaps.sortedWith(PORTABLE_AMBIENT_STEPS_GAP_ORDER)
	val retainedBoundary = retainedFromMs?.takeIf { it > startTimeMs && it < endTimeMs }
	val coverageIntervals = buildList {
		retainedBoundary?.let { add(startTimeMs to it) }
		facts.forEach { add(it.intervalStartTimeMs to it.intervalEndTimeMs) }
		gaps.forEach { add(it.intervalStartTimeMs to it.intervalEndTimeMs) }
	}.sortedBy(Pair<Long, Long>::first)
	val entireDayAccountedFor = coverageIntervals.covers(startTimeMs, endTimeMs)
	val completelyCoveredByFacts = !pendingPrefix && gaps.isEmpty() && retainedBoundary == null &&
		facts.map { it.intervalStartTimeMs to it.intervalEndTimeMs }.covers(startTimeMs, endTimeMs)
	val causes = portableDayPartialCauses(gaps, retainedBoundary, entireDayAccountedFor, pendingPrefix)
	val total = facts.fold(0L) { sum, fact -> Math.addExact(sum, fact.stepCount) }
	val identity = AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.DAY,
		"$epochDay|$zoneId|$startTimeMs|$endTimeMs",
	)
	return PortableAmbientStepsDayV1.create(
		identity = identity,
		structuralEpochDay = epochDay,
		storedZoneId = zoneId,
		structuralDayStartTimeMs = startTimeMs,
		structuralDayEndTimeMs = endTimeMs,
		retainedFromTimeMs = retainedBoundary,
		coverage = if (completelyCoveredByFacts) {
			PortableAmbientStepsCoverage.COMPLETE
		} else {
			PortableAmbientStepsCoverage.PARTIAL
		},
		partialCauses = causes,
		retainedStepCount = total,
		facts = facts,
		gaps = gaps,
	)
}

private fun portableDayPartialCauses(
	gaps: List<PortableAmbientStepsGapV1>,
	retainedBoundary: Long?,
	entireDayAccountedFor: Boolean,
	pendingPrefix: Boolean,
): List<PortableAmbientStepsPartialCause> = buildList {
	if (gaps.isNotEmpty()) {
		add(PortableAmbientStepsPartialCause.EXPLICIT_GAP)
	}
	if (retainedBoundary != null) {
		add(PortableAmbientStepsPartialCause.RETENTION)
	}
	if (!entireDayAccountedFor || pendingPrefix) {
		add(PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY)
	}
}.distinct().sortedBy { it.ordinal }

private fun List<Pair<Long, Long>>.covers(startTimeMs: Long, endTimeMs: Long): Boolean {
	var coveredThrough = startTimeMs
	for ((start, end) in this) {
		if (end <= coveredThrough) {
			continue
		}
		if (start > coveredThrough) {
			return false
		}
		coveredThrough = maxOf(coveredThrough, end)
		if (coveredThrough >= endTimeMs) {
			return true
		}
	}
	return false
}

// Fixed saved gap-code conversion, including the existing unknown-code failure.
@Suppress("CyclomaticComplexMethod")
private fun String.toPortableGapReason(): PortableAmbientStepsGapReason = when (this) {
	AmbientStepsImportGapEntity.REASON_PROVIDER_NO_EVIDENCE ->
		PortableAmbientStepsGapReason.PROVIDER_NO_EVIDENCE
	AmbientStepsImportGapEntity.REASON_PROVIDER_RETENTION_LOSS ->
		PortableAmbientStepsGapReason.PROVIDER_RETENTION_LOSS
	AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE -> PortableAmbientStepsGapReason.PROCESS_ABSENCE
	AmbientStepsImportGapEntity.REASON_BOOT_CHANGED -> PortableAmbientStepsGapReason.BOOT_CHANGED
	AmbientStepsImportGapEntity.REASON_ZONE_CHANGED -> PortableAmbientStepsGapReason.ZONE_CHANGED
	AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED -> PortableAmbientStepsGapReason.PROVIDER_CHANGED
	AmbientStepsImportGapEntity.REASON_CLOCK_DISCONTINUITY ->
		PortableAmbientStepsGapReason.CLOCK_DISCONTINUITY
	AmbientStepsImportGapEntity.REASON_AUTHORITY_BOUNDARY_NOT_DRAINED ->
		PortableAmbientStepsGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED
	AmbientStepsImportGapEntity.REASON_INITIAL_ZONE_AUTHORITY_UNOBSERVED ->
		PortableAmbientStepsGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED
	else -> error("Unknown Ambient Steps gap reason")
}

private fun requirePortableBound(condition: Boolean) {
	if (!condition) {
		throw AmbientStepsPortableLimitExceeded()
	}
}

private class AmbientStepsPortableLimitExceeded : IllegalStateException()
