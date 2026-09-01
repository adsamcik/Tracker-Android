package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals
import com.adsamcik.tracker.shared.base.database.dao.ScopedStepFactState
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.math.BigInteger
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Bounded, deletion-local composer for affected daily summaries.
 *
 * It deliberately does not trust `sample_count` or the candidate presentation `steps` column.
 * A surviving v28 row contributes only after exact reverse binding, immutable manifest
 * attribution, durable logical identity, and settled candidate Steps facts all agree. Every
 * existing summary is recomposed under the exact ZoneId captured by its production materializer.
 * Anything that cannot be proven from those facts and row authority fails closed before deletion.
 */
@Suppress("LargeClass", "TooManyFunctions")
internal class StepsDailySummaryRepairComposer(
	private val database: AppDatabase,
) {
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	suspend fun compose(
		epochDays: List<Long>,
		excludedSegmentId: Long,
	): StepsDayRepairPreflight {
		val sortedDays = epochDays.distinct().sorted()
		if (sortedDays.isEmpty()) {
			return unverifiable()
		}
		val requested = sortedDays.toSet()
		val summaries = database.dailySummaryDao()
			.getBetween(sortedDays.first(), sortedDays.last())
			.filter { summary -> summary.dateEpochDay in requested }
		return compose(epochDays, excludedSegmentId, summaries)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	suspend fun compose(
		epochDays: List<Long>,
		excludedSegmentId: Long,
		dailySummaries: List<DailySummaryEntity>,
	): StepsDayRepairPreflight {
		val sortedDays = epochDays.distinct().sorted()
		val sortedDaySet = sortedDays.toSet()
		if (sortedDays.isEmpty() || dailySummaries.any { it.dateEpochDay !in sortedDaySet } ||
			dailySummaries.map(DailySummaryEntity::dateEpochDay).distinct().size != dailySummaries.size
		) {
			return unverifiable()
		}
		val zoneByDay = linkedMapOf<Long, ZoneId>()
		for (summary in dailySummaries.sortedBy(DailySummaryEntity::dateEpochDay)) {
			val zoneId = try {
				ZoneId.of(summary.calendarZoneId ?: return unverifiable())
			} catch (_: DateTimeException) {
				return unverifiable()
			}
			zoneByDay[summary.dateEpochDay] = zoneId
		}
		val queryBounds = allZoneQueryBounds(sortedDays) ?: return unverifiable()
		return composeQualifiedDays(
			excludedSegmentId = excludedSegmentId,
			zoneByDay = zoneByDay,
			queryBounds = queryBounds,
		)
	}

	/** Composes one production materialization key under its already-selected calendar authority. */
	suspend fun composeForMaterialization(
		epochDay: Long,
		zoneId: ZoneId,
	): StepsDayRepairPreflight {
		val fromMs = startOfDayMs(epochDay, zoneId) ?: return unverifiable()
		val toMs = startOfDayMs(epochDay + 1L, zoneId) ?: return unverifiable()
		return composeQualifiedDays(
			excludedSegmentId = null,
			zoneByDay = linkedMapOf(epochDay to zoneId),
			queryBounds = QueryBounds(fromMs = fromMs, toMs = toMs),
		)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun composeQualifiedDays(
		excludedSegmentId: Long?,
		zoneByDay: Map<Long, ZoneId>,
		queryBounds: QueryBounds,
	): StepsDayRepairPreflight {
		val segments = database.sessionSegmentDao()
			.getAllOverlappingForSourceRepair(
				fromMs = queryBounds.fromMs,
				toMs = queryBounds.toMs,
				excludedSegmentId = excludedSegmentId,
				limit = MAX_PHYSICAL_SEGMENTS + 1,
			)
		if (segments.size > MAX_PHYSICAL_SEGMENTS) {
			return unverifiable()
		}
		if (segments.isEmpty()) {
			return StepsDayRepairPreflight.Ready(
				zoneByDay.map { (epochDay, zoneId) ->
					StepsDayRepairPlan(epochDay, zoneId, totals = null)
				},
			)
		}

		val snapshot = loadSnapshot(segments) ?: return unverifiable()
		val contributions = mutableListOf<SegmentContribution>()
		for (segment in segments) {
			when (val qualified = qualify(segment, snapshot)) {
				is SegmentQualification.Ready -> contributions += qualified.contribution
				SegmentQualification.Materializing -> return StepsDayRepairPreflight.Materializing
				SegmentQualification.Unverifiable -> return unverifiable()
			}
		}
		if (contributions.sumOf { it.stepContributions.size } > MAX_STEP_FACTS) {
			return unverifiable()
		}

		val groups = contributions.groupBy(SegmentContribution::logicalTrackingId)
		if (groups.values.any(::hasIncompatibleLogicalGroup)) {
			return unverifiable()
		}
		val plans = mutableListOf<StepsDayRepairPlan>()
		for ((epochDay, zoneId) in zoneByDay) {
			when (val composition = composeDay(epochDay, zoneId, groups.values)) {
				is DayComposition.Ready -> plans += StepsDayRepairPlan(
					epochDay,
					zoneId,
					composition.totals,
				)
				DayComposition.Unverifiable -> return unverifiable()
			}
		}
		return StepsDayRepairPreflight.Ready(plans)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun loadSnapshot(segments: List<SessionSegment>): RepairSnapshot? {
		val logicalTrackingIds = segments.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank)
			.distinct()
		val serviceRunIds = segments.mapNotNull(SessionSegment::serviceRunId)
			.filter(String::isNotBlank)
			.distinct()
		if (logicalTrackingIds.size > MAX_PHYSICAL_SEGMENTS ||
			serviceRunIds.size > MAX_PHYSICAL_SEGMENTS
		) {
			return null
		}
		val readDao = database.trackingHistoryReadDao()
		val logicalSessions = if (logicalTrackingIds.isEmpty()) {
			emptyList()
		} else {
			database.sourceSessionDao().sessions(logicalTrackingIds)
		}
		val serviceRuns = if (serviceRunIds.isEmpty()) {
			emptyList()
		} else {
			readDao.serviceRuns(serviceRunIds)
		}
		val manifests = if (serviceRunIds.isEmpty()) {
			emptyList()
		} else {
			readDao.manifests(serviceRunIds, limit = MAX_MANIFESTS + 1)
		}
		val sources = if (serviceRunIds.isEmpty()) {
			emptyList()
		} else {
			readDao.manifestSources(serviceRunIds, limit = MAX_MANIFEST_SOURCES + 1)
		}
		val completeness = if (serviceRunIds.isEmpty()) {
			emptyList()
		} else {
			readDao.completeness(serviceRunIds, limit = MAX_COMPLETENESS_ROWS + 1)
		}
		val factStates = if (serviceRunIds.isEmpty()) {
			emptyList()
		} else {
			readDao.stepFactStates(serviceRunIds, limit = MAX_STEP_FACTS + 1)
		}
		if (manifests.size > MAX_MANIFESTS || sources.size > MAX_MANIFEST_SOURCES ||
			factStates.size > MAX_STEP_FACTS || completeness.size > MAX_COMPLETENESS_ROWS
		) {
			return null
		}
		val lanes = if (serviceRunIds.isEmpty()) {
			emptyList()
		} else {
			readDao.productLanesForServiceRuns(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				serviceRunIds = serviceRunIds,
			)
		}
		val scopeDigests = segments.mapNotNull { segment ->
			val logicalTrackingId = segment.logicalTrackingId?.takeIf(String::isNotBlank)
			val serviceRunId = segment.serviceRunId?.takeIf(String::isNotBlank)
			if (logicalTrackingId == null || serviceRunId == null) {
				null
			} else {
				SourceDeletionFenceEntity.logicalServiceRunIdentity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
				)
			}
		}.distinct()
		val deletionFences = if (scopeDigests.isEmpty()) {
			emptyList()
		} else {
			readDao.deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = scopeDigests,
			)
		}
		val afterOrdinal = lanes.mapNotNull { lane ->
			lane.activationOrdinal.takeIf { it > 0L }?.minus(1L)
		}.minOrNull()
		val throughOrdinal = completeness.mapNotNull { row -> row.lastAdmissionOrdinal }.maxOrNull()
		val failures = if (
			serviceRunIds.isEmpty() || afterOrdinal == null || throughOrdinal == null ||
			afterOrdinal >= throughOrdinal
		) {
			emptyList()
		} else {
			readDao.terminalFailuresForServiceRuns(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				serviceRunIds = serviceRunIds,
				afterOrdinal = afterOrdinal,
				throughOrdinal = throughOrdinal,
				limit = MAX_TERMINAL_FAILURES + 1,
			)
		}
		if (failures.size > MAX_TERMINAL_FAILURES) {
			return null
		}
		return RepairSnapshot(
			logicalSessions = logicalSessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			serviceRuns = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId),
			manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByManifest = sources.groupBy { source ->
				ManifestKey(source.logicalTrackingId, source.manifestRevision)
			},
			completenessByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId),
			factStatesByRun = factStates.groupBy(ScopedStepFactState::serviceRunId),
			lanes = lanes.associateBy { lane ->
				LaneKey(lane.bindingGeneration, lane.projectionId, lane.projectionVersion)
			},
			deletionFenceDigests = deletionFences.mapTo(hashSetOf()) { fence ->
				fence.scopeIdentityDigest
			},
			failures = failures,
			evidenceState = database.sourceEvidenceStateDao().get(),
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun qualify(
		segment: SessionSegment,
		snapshot: RepairSnapshot,
	): SegmentQualification {
		if (segment.endTimeMs <= segment.startTimeMs || !segment.distanceM.isFinite() || segment.distanceM < 0f) {
			return SegmentQualification.Unverifiable
		}
		val logicalTrackingId = segment.logicalTrackingId
		val serviceRunId = segment.serviceRunId
		// Released rows have no immutable calendar or logical-start authority.
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
			return SegmentQualification.Unverifiable
		}
		val logicalSession = snapshot.logicalSessions[logicalTrackingId]
			?: return SegmentQualification.Unverifiable
		if (logicalSession.startedAtMs < 0L || logicalSession.startedAtMs >= segment.endTimeMs) {
			return SegmentQualification.Unverifiable
		}
		val run = snapshot.serviceRuns[serviceRunId] ?: return SegmentQualification.Unverifiable
		if (run.logicalTrackingId != logicalTrackingId || run.sessionSegmentId != segment.id) {
			return SegmentQualification.Unverifiable
		}
		when (run.presentationAcknowledgement) {
			SourceServiceRunEntity.PRESENTATION_QUIESCED -> Unit
			SourceServiceRunEntity.PRESENTATION_PENDING -> return SegmentQualification.Materializing
			SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE ->
				return SegmentQualification.Unverifiable
			else -> return SegmentQualification.Unverifiable
		}

		val manifests = snapshot.manifestsByRun[serviceRunId].orEmpty()
		val authorities = manifestAuthorities(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifests = manifests,
			sourcesByManifest = snapshot.sourcesByManifest,
		) ?: return SegmentQualification.Unverifiable
		val zones = authorities.values.map(ManifestAuthority::zoneId).distinct()
		if (zones.size != 1) {
			// A physical segment is not revision-partitioned, so mixed-zone allocation is ambiguous.
			return SegmentQualification.Unverifiable
		}
		if (run.completedAtMs == null || run.state !in TERMINAL_SERVICE_RUN_STATES) {
			return SegmentQualification.Materializing
		}
		val exactBindings = authorities.values.mapNotNull(ManifestAuthority::stepsBinding)
		if (exactBindings.isEmpty()) {
			return nonStepsContribution(
				segment = segment,
				logicalSession = logicalSession,
				serviceRunId = serviceRunId,
				authorities = authorities,
				snapshot = snapshot,
			)
		}
		val writerBindings = exactBindings.map(::writerBinding).distinct()
		if (writerBindings.size != 1) {
			return SegmentQualification.Unverifiable
		}
		val writer = writerBindings.single()
		if (writer.owner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS) {
			// No legacy row, including a zero-sample row, is numeric source evidence for this repair.
			return SegmentQualification.Unverifiable
		}
		return candidateContribution(
			segment = segment,
			logicalSession = logicalSession,
			serviceRunId = serviceRunId,
			authorities = authorities,
			writer = writer,
			snapshot = snapshot,
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun candidateContribution(
		segment: SessionSegment,
		logicalSession: LogicalTrackingSessionEntity,
		serviceRunId: String,
		authorities: Map<Long, ManifestAuthority>,
		writer: WriterBinding,
		snapshot: RepairSnapshot,
	): SegmentQualification {
		val logicalTrackingId = logicalSession.logicalTrackingId
		val projectionId = writer.projectionId ?: return SegmentQualification.Unverifiable
		val projectionVersion = writer.projectionVersion ?: return SegmentQualification.Unverifiable
		val bindingGeneration = writer.bindingGeneration ?: return SegmentQualification.Unverifiable
		if (writer.ownerGeneration <= 0L || bindingGeneration <= 0L) {
			return SegmentQualification.Unverifiable
		}
		val evidenceState = snapshot.evidenceState ?: return SegmentQualification.Unverifiable
		if (evidenceState.retainedFromMs?.let { retained -> segment.startTimeMs < retained } == true) {
			return SegmentQualification.Unverifiable
		}
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		if (digest in snapshot.deletionFenceDigests) {
			return SegmentQualification.Unverifiable
		}

		val completeness = snapshot.completenessByRun[serviceRunId].orEmpty().filter { row ->
			row.logicalTrackingId == logicalTrackingId &&
				row.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS
		}
		if (completeness.isEmpty()) {
			return SegmentQualification.Unverifiable
		}
		if (completeness.any { row -> !row.appDrainComplete }) {
			return SegmentQualification.Materializing
		}
		if (completeness.any { row ->
			row.stopStatus != COMPLETE_STOP_STATUS || row.providerCoverage != COMPLETE_PROVIDER_COVERAGE ||
				row.unresolvedSequenceStart != null || row.unresolvedSequenceEnd != null
		}) {
			return SegmentQualification.Unverifiable
		}
		val targetOrdinal = completeness.mapNotNull { row -> row.lastAdmissionOrdinal }.maxOrNull()
			?: return SegmentQualification.Unverifiable

		val lane = snapshot.lanes[LaneKey(bindingGeneration, projectionId, projectionVersion)]
			?: return SegmentQualification.Unverifiable
		if (!validHistoricalLane(lane) || targetOrdinal < lane.activationOrdinal) {
			return SegmentQualification.Unverifiable
		}
		if (lane.contiguousAdmissionOrdinal < targetOrdinal) {
			return if (lane.status == SourceProductProjectionLaneEntity.STATUS_ACTIVE) {
				SegmentQualification.Materializing
			} else {
				SegmentQualification.Unverifiable
			}
		}
		if (snapshot.failures.any { failure ->
			failure.projectionId == projectionId && failure.projectionVersion == projectionVersion &&
				failure.admissionOrdinal in lane.activationOrdinal..targetOrdinal
		}) {
			return SegmentQualification.Unverifiable
		}

		val scopedStates = snapshot.factStatesByRun[serviceRunId].orEmpty()
		if (scopedStates.isEmpty()) {
			return SegmentQualification.Unverifiable
		}
		val covered = mutableListOf<StepFactRevisionEntity>()
		for (scoped in scopedStates) {
			val authority = authorities[scoped.manifestRevision]
				?: return SegmentQualification.Unverifiable
			val binding = authority.stepsBinding ?: return SegmentQualification.Unverifiable
			val state = scoped.state
			val admissionOrdinal = state.sourceAdmissionOrdinal
			if (
				scoped.logicalTrackingId != logicalTrackingId ||
				scoped.writerBindingGeneration != bindingGeneration ||
				state.writerProjectionId != projectionId ||
				state.writerProjectionVersion != projectionVersion ||
				state.writerBindingGeneration != bindingGeneration ||
				state.operation != StepFactRevisionEntity.OPERATION_UPSERT ||
				state.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
				admissionOrdinal == null || admissionOrdinal !in lane.activationOrdinal..targetOrdinal ||
				state.logicalTrackingId != logicalTrackingId ||
				state.serviceRunId != serviceRunId ||
				state.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
				state.manifestRevision != authority.manifest.manifestRevision ||
				state.sourcePolicyRevision != authority.manifest.sourcePolicyRevision ||
				state.captureConsentEpoch != binding.consentEpoch ||
				state.collectedDataEpoch != evidenceState.collectedDataEpoch ||
				state.coverageKind in UNSAFE_COVERAGE_KINDS
			) {
				return SegmentQualification.Unverifiable
			}
			if (state.coverageKind == StepFactRevisionEntity.COVERAGE_COVERED) {
				covered += state
			}
		}
		if (covered.isEmpty()) {
			return SegmentQualification.Unverifiable
		}
		val contributions = mutableListOf<StepContribution>()
		for (fact in covered) {
			val startMs = fact.intervalStartTimeMs ?: return SegmentQualification.Unverifiable
			val endMs = fact.intervalEndTimeMs ?: return SegmentQualification.Unverifiable
			val count = fact.effectiveStepCount ?: return SegmentQualification.Unverifiable
			if (startMs < segment.startTimeMs || endMs > segment.endTimeMs || endMs <= startMs || count < 0L) {
				return SegmentQualification.Unverifiable
			}
			contributions += StepContribution(startMs, endMs, count)
		}
		return SegmentQualification.Ready(
			SegmentContribution(
				logicalTrackingId = logicalTrackingId,
				logicalStartedAtMs = logicalSession.startedAtMs,
				zoneId = authorities.values.first().zoneId,
				segment = segment,
				stepContributions = contributions,
			),
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
	private fun nonStepsContribution(
		segment: SessionSegment,
		logicalSession: LogicalTrackingSessionEntity,
		serviceRunId: String,
		authorities: Map<Long, ManifestAuthority>,
		snapshot: RepairSnapshot,
	): SegmentQualification {
		val evidenceState = snapshot.evidenceState ?: return SegmentQualification.Unverifiable
		if (evidenceState.retainedFromMs?.let { retained -> segment.startTimeMs < retained } == true) {
			return SegmentQualification.Unverifiable
		}
		// A Steps fact attributed to a run whose immutable manifests never captured Steps is
		// contradictory source evidence, not a zero-valued non-Steps contribution.
		if (snapshot.factStatesByRun[serviceRunId].orEmpty().isNotEmpty()) {
			return SegmentQualification.Unverifiable
		}
		val capturedForWholeRun = authorities.values.drop(1).fold(
			authorities.values.first().capturedSourceKinds,
		) { common, authority -> common intersect authority.capturedSourceKinds }
			.filterTo(linkedSetOf()) { sourceKind ->
				sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS
			}
		if (capturedForWholeRun.isEmpty()) {
			return SegmentQualification.Unverifiable
		}
		val rowsBySource = snapshot.completenessByRun[serviceRunId].orEmpty()
			.filter { row -> row.logicalTrackingId == logicalSession.logicalTrackingId }
			.groupBy(SourceSessionCompletenessEntity::sourceKind)
		for (sourceKind in capturedForWholeRun) {
			val rows = rowsBySource[sourceKind].orEmpty()
			if (rows.isEmpty()) {
				continue
			}
			if (rows.any { row -> !row.appDrainComplete }) {
				continue
			}
			if (rows.any { row ->
				row.sourceInstanceId.isBlank() || row.registrationGeneration <= 0L ||
					row.stopStatus != COMPLETE_STOP_STATUS ||
					row.providerCoverage != COMPLETE_PROVIDER_COVERAGE ||
					row.unresolvedSequenceStart != null || row.unresolvedSequenceEnd != null
			}) {
				continue
			}
			val hasDurableObservation = rows.any { row ->
				row.lastAdmissionOrdinal?.let { ordinal -> ordinal > 0L } == true &&
					row.lastSourceSequence?.let { sequence -> sequence >= 0L } == true
			}
			if (hasDurableObservation) {
				return SegmentQualification.Ready(
					SegmentContribution(
						logicalTrackingId = logicalSession.logicalTrackingId,
						logicalStartedAtMs = logicalSession.startedAtMs,
						zoneId = authorities.values.first().zoneId,
						segment = segment,
						stepContributions = emptyList(),
					),
				)
			}
		}
		return SegmentQualification.Unverifiable
	}

	private fun hasIncompatibleLogicalGroup(group: List<SegmentContribution>): Boolean {
		if (group.map(SegmentContribution::zoneId).distinct().size != 1 ||
			group.map(SegmentContribution::logicalStartedAtMs).distinct().size != 1
		) {
			return true
		}
		val physical = group.map(SegmentContribution::segment).sortedBy(SessionSegment::startTimeMs)
		if (physical.zipWithNext().any { (first, second) -> second.startTimeMs < first.endTimeMs }) {
			return true
		}
		val facts = group.flatMap(SegmentContribution::stepContributions).sortedBy(StepContribution::startMs)
		return facts.zipWithNext().any { (first, second) -> second.startMs < first.endMs }
	}

	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun composeDay(
		epochDay: Long,
		zoneId: ZoneId,
		groups: Collection<List<SegmentContribution>>,
	): DayComposition {
		var distance = 0.0
		var steps = 0L
		var duration = 0L
		var trips = 0
		var hasContribution = false
		val dayStartMs = startOfDayMs(epochDay, zoneId) ?: return DayComposition.Unverifiable
		val dayEndMs = startOfDayMs(epochDay + 1L, zoneId) ?: return DayComposition.Unverifiable
		for (group in groups) {
			val overlapping = group.filter { contribution ->
				contribution.segment.startTimeMs < dayEndMs && contribution.segment.endTimeMs > dayStartMs
			}
			if (overlapping.isEmpty()) {
				continue
			}
			hasContribution = true
			for (contribution in overlapping) {
				val segment = contribution.segment
				val segmentDuration = segment.endTimeMs - segment.startTimeMs
				val inDayDuration = minOf(segment.endTimeMs, dayEndMs) -
					maxOf(segment.startTimeMs, dayStartMs)
				if (segmentDuration <= 0L || inDayDuration <= 0L) {
					return DayComposition.Unverifiable
				}
				distance += segment.distanceM.toDouble() *
					(inDayDuration.toDouble() / segmentDuration.toDouble())
				duration = try {
					Math.addExact(duration, inDayDuration)
				} catch (_: ArithmeticException) {
					return DayComposition.Unverifiable
				}
				for (fact in contribution.stepContributions) {
					val allocations = allocateSteps(fact.startMs, fact.endMs, fact.count, zoneId)
						?: return DayComposition.Unverifiable
					steps = try {
						Math.addExact(steps, allocations[epochDay] ?: 0L)
					} catch (_: ArithmeticException) {
						return DayComposition.Unverifiable
					}
				}
			}
			if (group.first().logicalStartedAtMs in dayStartMs until dayEndMs) {
				trips += 1
			}
		}
		if (!hasContribution) {
			return DayComposition.Ready(totals = null)
		}
		if (!distance.isFinite() || distance > Float.MAX_VALUE || steps !in 0L..Int.MAX_VALUE) {
			return DayComposition.Unverifiable
		}
		return DayComposition.Ready(
			DailySummaryTotals(
				distanceM = distance.toFloat(),
				steps = steps.toInt(),
				durationMs = duration,
				tripCount = trips,
			),
		)
	}

	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun allocateSteps(
		startMs: Long,
		endMs: Long,
		count: Long,
		zoneId: ZoneId,
	): Map<Long, Long>? {
		if (endMs <= startMs || count < 0L) {
			return null
		}
		val startDay = epochDay(startMs, zoneId) ?: return null
		val endDay = epochDay(endMs - 1L, zoneId) ?: return null
		val dayCount = endDay - startDay + 1L
		if (dayCount <= 0L || dayCount > MAX_REPAIR_DAYS) {
			return null
		}
		val totalDuration = endMs - startMs
		val total = BigInteger.valueOf(totalDuration)
		val weighted = (0L until dayCount).map { offset ->
			val allocationDay = startDay + offset
			val dayEndMs = startOfDayMs(allocationDay + 1L, zoneId) ?: return null
			val dayStartMs = startOfDayMs(allocationDay, zoneId) ?: return null
			val duration = minOf(endMs, dayEndMs) - maxOf(startMs, dayStartMs)
			val numerator = BigInteger.valueOf(count).multiply(BigInteger.valueOf(duration))
			val division = numerator.divideAndRemainder(total)
			WeightedDay(allocationDay, division[0].toLong(), division[1])
		}.toMutableList()
		val assigned = weighted.sumOf(WeightedDay::base)
		val remainder = count - assigned
		if (remainder < 0L || remainder >= dayCount) {
			return null
		}
		weighted.sortWith(compareByDescending<WeightedDay> { it.remainder }.thenBy { it.epochDay })
		repeat(remainder.toInt()) { index ->
			weighted[index] = weighted[index].copy(base = weighted[index].base + 1L)
		}
		return weighted.associate { day -> day.epochDay to day.base }
	}

	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun manifestAuthorities(
		logicalTrackingId: String,
		serviceRunId: String,
		manifests: List<SessionManifestVersionEntity>,
		sourcesByManifest: Map<ManifestKey, List<SessionManifestSourceEntity>>,
	): Map<Long, ManifestAuthority>? {
		if (manifests.isEmpty() || manifests.map { it.manifestRevision }.distinct().size != manifests.size) {
			return null
		}
		val authorities = linkedMapOf<Long, ManifestAuthority>()
		for (manifest in manifests) {
			if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId) {
				return null
			}
			val sources = sourcesByManifest[ManifestKey(logicalTrackingId, manifest.manifestRevision)].orEmpty()
			if (!SessionManifestIntegrity.verify(manifest, sources) ||
				sources.any { source ->
					source.purpose !in SessionManifestPurposeCode.ALL || source.sourceKind !in KNOWN_SOURCE_KINDS
				}
			) {
				return null
			}
			val captureSources = sources.filter { source ->
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}
			if (captureSources.isEmpty()) {
				return null
			}
			val stepsSources = captureSources.filter { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && source.persistenceEligible
			}
			if (stepsSources.size > 1) {
				return null
			}
			val zoneId = try {
				ZoneId.of(manifest.zoneId)
			} catch (_: DateTimeException) {
				return null
			}
			authorities[manifest.manifestRevision] = ManifestAuthority(
				manifest = manifest,
				stepsBinding = stepsSources.singleOrNull(),
				capturedSourceKinds = captureSources.mapTo(linkedSetOf(), SessionManifestSourceEntity::sourceKind),
				zoneId = zoneId,
			)
		}
		return authorities
	}

	private fun writerBinding(source: SessionManifestSourceEntity) = WriterBinding(
		owner = source.writerOwner ?: "",
		ownerGeneration = source.writerOwnerGeneration ?: -1L,
		projectionId = source.writerProjectionId,
		projectionVersion = source.writerProjectionVersion,
		bindingGeneration = source.writerBindingGeneration,
	)

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun validHistoricalLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.captureModeMask <= 0L || lane.productStage !in EXECUTABLE_PRODUCT_STAGES ||
			lane.activatedRolloutRevision <= 0L || lane.activationOrdinal <= 0L ||
			lane.installedAtMs < 0L || lane.updatedAtMs < lane.installedAtMs
		) {
			return false
		}
		val minimumCursor = lane.activationOrdinal - 1L
		val cutoff = lane.captureAdmissionCutoffOrdinal
		if (lane.contiguousAdmissionOrdinal < minimumCursor ||
			(cutoff != null && (cutoff < minimumCursor || lane.contiguousAdmissionOrdinal > cutoff))
		) {
			return false
		}
		return when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE -> lane.retentionRequired &&
				lane.terminalDisposition == null && lane.terminalAtMs == null
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> !lane.retentionRequired &&
				cutoff != null && lane.contiguousAdmissionOrdinal == cutoff
			else -> false
		}
	}

	private fun allZoneQueryBounds(sortedDays: List<Long>): QueryBounds? {
		if (sortedDays.isEmpty() || sortedDays.size > MAX_REPAIR_DAYS ||
			sortedDays.last() - sortedDays.first() + 1L > MAX_REPAIR_DAYS
		) {
			return null
		}
		return try {
			val utcStart = LocalDate.ofEpochDay(sortedDays.first())
				.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
			val utcEnd = LocalDate.ofEpochDay(Math.addExact(sortedDays.last(), 1L))
				.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
			QueryBounds(
				fromMs = Math.subtractExact(utcStart, MAX_ZONE_OFFSET_MS),
				toMs = Math.addExact(utcEnd, MAX_ZONE_OFFSET_MS),
			)
		} catch (_: ArithmeticException) {
			null
		} catch (_: DateTimeException) {
			null
		}
	}

	private fun epochDay(instantMs: Long, zoneId: ZoneId): Long? = try {
		Instant.ofEpochMilli(instantMs).atZone(zoneId).toLocalDate().toEpochDay()
	} catch (_: DateTimeException) {
		null
	}

	private fun startOfDayMs(epochDay: Long, zoneId: ZoneId): Long? = try {
		LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()
	} catch (_: DateTimeException) {
		null
	} catch (_: ArithmeticException) {
		null
	}

	private fun unverifiable() = StepsDayRepairPreflight.Unsupported(
		StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
	)

	private data class ManifestKey(val logicalTrackingId: String, val manifestRevision: Long)
	private data class LaneKey(val generation: Long, val projectionId: String, val version: Int)
	private data class QueryBounds(val fromMs: Long, val toMs: Long)
	private data class ManifestAuthority(
		val manifest: SessionManifestVersionEntity,
		val stepsBinding: SessionManifestSourceEntity?,
		val capturedSourceKinds: Set<Int>,
		val zoneId: ZoneId,
	)
	private data class WriterBinding(
		val owner: String,
		val ownerGeneration: Long,
		val projectionId: String?,
		val projectionVersion: Int?,
		val bindingGeneration: Long?,
	)
	private data class SegmentContribution(
		val logicalTrackingId: String,
		val logicalStartedAtMs: Long,
		val zoneId: ZoneId,
		val segment: SessionSegment,
		val stepContributions: List<StepContribution>,
	)
	private data class StepContribution(
		val startMs: Long,
		val endMs: Long,
		val count: Long,
	)
	private data class WeightedDay(
		val epochDay: Long,
		val base: Long,
		val remainder: BigInteger,
	)
	private data class RepairSnapshot(
		val logicalSessions: Map<String, LogicalTrackingSessionEntity>,
		val serviceRuns: Map<String, SourceServiceRunEntity>,
		val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		val sourcesByManifest: Map<ManifestKey, List<SessionManifestSourceEntity>>,
		val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
		val factStatesByRun: Map<String, List<ScopedStepFactState>>,
		val lanes: Map<LaneKey, SourceProductProjectionLaneEntity>,
		val deletionFenceDigests: Set<String>,
		val failures: List<SourceProjectionFailureEntity>,
		val evidenceState: SourceEvidenceState?,
	)
	private sealed interface SegmentQualification {
		data class Ready(val contribution: SegmentContribution) : SegmentQualification
		data object Materializing : SegmentQualification
		data object Unverifiable : SegmentQualification
	}
	private sealed interface DayComposition {
		data class Ready(val totals: DailySummaryTotals?) : DayComposition
		data object Unverifiable : DayComposition
	}

	private companion object {
		const val MAX_REPAIR_DAYS = 370
		const val MAX_PHYSICAL_SEGMENTS = 256
		const val MAX_MANIFESTS = 2_048
		const val MAX_MANIFEST_SOURCES = 24_576
		const val MAX_STEP_FACTS = 2_048
		const val MAX_COMPLETENESS_ROWS = 1_024
		const val MAX_TERMINAL_FAILURES = 2_048
		const val MAX_ZONE_OFFSET_MS = 18L * 60L * 60_000L
		const val COMPLETE_STOP_STATUS = "COMPLETE"
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
		val KNOWN_SOURCE_KINDS = SourceKind.values().mapTo(hashSetOf(), SourceKind::stableCode)
		val UNSAFE_COVERAGE_KINDS = setOf(
			StepFactRevisionEntity.COVERAGE_PARTIAL,
			StepFactRevisionEntity.COVERAGE_RESET_GAP,
		)
		val TERMINAL_SERVICE_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val EXECUTABLE_PRODUCT_STAGES = setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
	}
}

internal sealed interface StepsDayRepairPreflight {
	data class Ready(val plans: List<StepsDayRepairPlan>) : StepsDayRepairPreflight
	data class Unsupported(
		val reason: StepsSessionDeletionUnsupportedReason,
	) : StepsDayRepairPreflight
	data object Materializing : StepsDayRepairPreflight
}

internal data class StepsDayRepairPlan(
	val epochDay: Long,
	val zoneId: ZoneId,
	val totals: DailySummaryTotals?,
)
