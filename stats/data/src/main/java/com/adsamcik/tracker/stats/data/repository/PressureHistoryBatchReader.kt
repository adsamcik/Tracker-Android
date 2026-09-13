package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Loads all bounded-page dependencies for one Pressure history snapshot without per-row queries. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun loadPressureHistoryBatchSnapshot(
	database: AppDatabase,
	segments: List<SessionSegment>,
	logicalMembershipFailures: Map<String, PressureHistoryReason> = emptyMap(),
	factRevisionBudget: Int = MAX_PRESSURE_FACT_REVISIONS,
): PressureHistoryBatchSnapshot {
	require(segments.isNotEmpty())
	require(factRevisionBudget > 0)
	val readDao = database.trackingHistoryReadDao()
	val serviceRunIds = segments.mapNotNull(SessionSegment::serviceRunId)
		.filter(String::isNotBlank)
		.distinct()
	val serviceRuns = if (serviceRunIds.isEmpty()) emptyList() else readDao.serviceRuns(serviceRunIds)
	val manifests = if (serviceRunIds.isEmpty()) emptyList() else readDao.manifests(serviceRunIds)
	val manifestSources = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.manifestSources(serviceRunIds)
	}
	val pressurePolicies = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.policiesForServiceRuns(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			serviceRunIds = serviceRunIds,
		)
	}
	val requestedConsentEpochs = manifestSources.asSequence().filter { source ->
		source.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible
	}.map(SessionManifestSourceEntity::consentEpoch).distinct().toList()
	val pressureCaptureConsents = requestedConsentEpochs.chunked(PRESSURE_QUERY_ID_BATCH_SIZE)
		.flatMap { epochs ->
			database.sourcePolicyDao().consentEpochs(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				epochs = epochs,
			)
		}
	val completeness = if (serviceRunIds.isEmpty()) emptyList() else readDao.completeness(serviceRunIds)
	val logicalTrackingIds = segments.mapNotNull(SessionSegment::logicalTrackingId)
		.filter(String::isNotBlank)
		.distinct()
	val pressureFactLoad = loadPressureHistoryFactRevisions(
		database = database,
		serviceRunIds = serviceRunIds,
		logicalTrackingIds = logicalTrackingIds,
		factRevisionBudget = factRevisionBudget,
	)
	val pressureFacts = pressureFactLoad.revisions
	val invalidFactScopeLogicalIds = invalidFactScopeLogicalIds(segments, pressureFacts)
	val scopeDigests = segments.mapNotNull { segment ->
		val logicalTrackingId = segment.logicalTrackingId?.takeIf(String::isNotBlank)
		val serviceRunId = segment.serviceRunId?.takeIf(String::isNotBlank)
		if (logicalTrackingId == null || serviceRunId == null) {
			null
		} else {
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			)
		}
	}.distinct()
	val deletionFences = if (scopeDigests.isEmpty()) {
		emptyList()
	} else {
		readDao.deletionFences(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigests = scopeDigests,
		)
	}
	val productLanes = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.productLanesForServiceRuns(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			serviceRunIds = serviceRunIds,
		)
	}
	val afterOrdinal = productLanes.mapNotNull { lane ->
		lane.activationOrdinal.takeIf { it > 0L }?.minus(1L)
	}.minOrNull()
	val throughOrdinal = completeness.asSequence()
		.filter { it.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE }
		.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal)
		.maxOrNull()
	val terminalFailures = if (
		serviceRunIds.isEmpty() || afterOrdinal == null || throughOrdinal == null ||
		afterOrdinal >= throughOrdinal
	) {
		emptyList()
	} else {
		readDao.terminalFailuresForServiceRuns(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			serviceRunIds = serviceRunIds,
			afterOrdinal = afterOrdinal,
			throughOrdinal = throughOrdinal,
			limit = MAX_PRESSURE_TERMINAL_FAILURES + 1,
		)
	}
	val terminalFailureOverflow = terminalFailures.size > MAX_PRESSURE_TERMINAL_FAILURES
	return PressureHistoryBatchSnapshot(
		serviceRuns = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId),
		manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId),
		sourcesByManifest = manifestSources.groupBy { source ->
			PressureManifestKey(source.logicalTrackingId, source.manifestRevision)
		},
		pressurePolicies = pressurePolicies.associateBy(SourcePolicyEntity::policyRevision),
		pressureCaptureConsents = pressureCaptureConsents.associateBy(SourceConsentEpochEntity::epoch),
		deletionFenceDigests = deletionFences.mapTo(hashSetOf()) { it.scopeIdentityDigest },
		factRevisionsByRun = pressureFacts.groupBy(PressureFactRevisionEntity::serviceRunId),
		invalidFactScopeLogicalIds = invalidFactScopeLogicalIds,
		logicalMembershipFailures = logicalMembershipFailures,
		factRevisionOverflow = pressureFactLoad.overflow,
		completenessByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId),
		productLanes = productLanes.associateBy { lane ->
			PressureLaneKey(lane.bindingGeneration, lane.projectionId, lane.projectionVersion)
		},
		terminalFailures = terminalFailures.takeIf { !terminalFailureOverflow }.orEmpty(),
		terminalFailureOverflow = terminalFailureOverflow,
		evidenceState = database.sourceEvidenceStateDao().get(),
	)
}

private suspend fun loadPressureHistoryFactRevisions(
	database: AppDatabase,
	serviceRunIds: List<String>,
	logicalTrackingIds: List<String>,
	factRevisionBudget: Int,
): PressureFactLoad {
	if (serviceRunIds.isEmpty() && logicalTrackingIds.isEmpty()) return PressureFactLoad.EMPTY
	val facts = mutableListOf<PressureFactRevisionEntity>()
	var cursor: PressureFactCursor? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = factRevisionBudget - facts.size
		val pageLimit = minOf(PRESSURE_FACT_PAGE_SIZE, remaining + 1)
		val page = database.pressureFactRevisionDao().historyRevisionPage(
			serviceRunIds = serviceRunIds,
			logicalTrackingIds = logicalTrackingIds,
			limit = pageLimit,
			afterServiceRunId = cursor?.serviceRunId,
			afterWriterProjectionId = cursor?.writerProjectionId,
			afterWriterProjectionVersion = cursor?.writerProjectionVersion,
			afterLogicalFactId = cursor?.logicalFactId,
			afterSemanticRevision = cursor?.semanticRevision,
		)
		if (page.isEmpty()) break
		if (page.size > remaining) return PressureFactLoad(facts, overflow = true)
		val nextCursor = PressureFactCursor(page.last())
		check(cursor == null || nextCursor > cursor) { "Pressure history fact cursor did not advance" }
		facts += page
		cursor = nextCursor
		if (page.size < pageLimit) break
	}
	return PressureFactLoad(facts, overflow = false)
}

private fun invalidFactScopeLogicalIds(
	segments: List<SessionSegment>,
	revisions: List<PressureFactRevisionEntity>,
): Set<String> {
	val expectedLogicalByRun = segments.mapNotNull { segment ->
		val logicalTrackingId = segment.logicalTrackingId?.takeIf(String::isNotBlank)
		val serviceRunId = segment.serviceRunId?.takeIf(String::isNotBlank)
		if (logicalTrackingId == null || serviceRunId == null) null else serviceRunId to logicalTrackingId
	}.toMap()
	val selectedLogicalIds = expectedLogicalByRun.values.toHashSet()
	return buildSet {
		revisions.forEach { fact ->
			val expectedLogicalId = expectedLogicalByRun[fact.serviceRunId]
			if (expectedLogicalId == null || expectedLogicalId != fact.logicalTrackingId) {
				expectedLogicalId?.let(::add)
				fact.logicalTrackingId.takeIf(selectedLogicalIds::contains)?.let(::add)
			}
		}
		revisions.groupBy { fact ->
			PressureFactLineageKey(
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.logicalFactId,
			)
		}.values.forEach { lineage ->
			if (lineage.map { it.logicalTrackingId to it.serviceRunId }.distinct().size > 1) {
				lineage.forEach { fact ->
					expectedLogicalByRun[fact.serviceRunId]?.let(::add)
					fact.logicalTrackingId.takeIf(selectedLogicalIds::contains)?.let(::add)
				}
			}
		}
	}
}

/** Complete explicit replacement-run expansion before logical Pressure composition. */
internal suspend fun expandPressureLogicalMembership(
	database: AppDatabase,
	seedSegments: List<SessionSegment>,
	memberBudget: Int = MAX_PRESSURE_LOGICAL_MEMBERS,
): PressureLogicalMembershipExpansion {
	require(memberBudget > 0)
	val logicalTrackingIds = seedSegments.mapNotNull(SessionSegment::logicalTrackingId)
		.filter(String::isNotBlank)
		.distinct()
	if (logicalTrackingIds.isEmpty()) return PressureLogicalMembershipExpansion(seedSegments, emptyMap())

	val runs = mutableListOf<SourceServiceRunEntity>()
	var cursor: PressureServiceRunCursor? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = memberBudget - runs.size
		val pageLimit = minOf(PRESSURE_MEMBERSHIP_PAGE_SIZE, remaining + 1)
		val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
			logicalTrackingIds = logicalTrackingIds,
			limit = pageLimit,
			afterLogicalTrackingId = cursor?.logicalTrackingId,
			afterStartedAtMs = cursor?.startedAtMs,
			afterServiceRunId = cursor?.serviceRunId,
		)
		if (page.isEmpty()) break
		if (page.size > remaining) {
			return PressureLogicalMembershipExpansion(
				segments = seedSegments,
				failures = logicalTrackingIds.associateWith {
					PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW
				},
			)
		}
		val nextCursor = PressureServiceRunCursor(page.last())
		check(cursor == null || nextCursor > cursor) {
			"Pressure logical-membership cursor did not advance"
		}
		runs += page
		cursor = nextCursor
		if (page.size < pageLimit) break
	}
	currentCoroutineContext().ensureActive()

	val failures = linkedMapOf<String, PressureHistoryReason>()
	val runsByLogicalId = runs.groupBy(SourceServiceRunEntity::logicalTrackingId)
	logicalTrackingIds.forEach { logicalTrackingId ->
		if (runsByLogicalId[logicalTrackingId].isNullOrEmpty()) {
			failures[logicalTrackingId] = PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE
		}
	}
	val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
	val loadedSegments = if (segmentIds.isEmpty()) {
		emptyList()
	} else {
		database.trackingHistoryReadDao().segments(segmentIds)
	}
	val segmentsById = loadedSegments.associateBy(SessionSegment::id)
	runs.forEach { run ->
		val segmentId = run.sessionSegmentId
		val segment = segmentId?.let(segmentsById::get)
		if (segment == null || segment.logicalTrackingId != run.logicalTrackingId ||
			segment.serviceRunId != run.serviceRunId
		) {
			failures[run.logicalTrackingId] = PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE
		}
	}
	seedSegments.forEach { seed ->
		val logicalTrackingId = seed.logicalTrackingId ?: return@forEach
		val run = runs.firstOrNull { it.serviceRunId == seed.serviceRunId }
		if (run == null || run.logicalTrackingId != logicalTrackingId || run.sessionSegmentId != seed.id) {
			failures[logicalTrackingId] = PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE
		}
	}
	return PressureLogicalMembershipExpansion(
		segments = (loadedSegments + seedSegments).distinctBy(SessionSegment::id),
		failures = failures,
	)
}

private data class PressureFactCursor(
	val serviceRunId: String,
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
	val logicalFactId: String,
	val semanticRevision: Long,
) : Comparable<PressureFactCursor> {
	constructor(fact: PressureFactRevisionEntity) : this(
		serviceRunId = fact.serviceRunId,
		writerProjectionId = fact.writerProjectionId,
		writerProjectionVersion = fact.writerProjectionVersion,
		logicalFactId = fact.logicalFactId,
		semanticRevision = fact.semanticRevision,
	)

	override fun compareTo(other: PressureFactCursor): Int = compareValuesBy(
		this,
		other,
		PressureFactCursor::serviceRunId,
		PressureFactCursor::writerProjectionId,
		PressureFactCursor::writerProjectionVersion,
		PressureFactCursor::logicalFactId,
		PressureFactCursor::semanticRevision,
	)
}

private data class PressureServiceRunCursor(
	val logicalTrackingId: String,
	val startedAtMs: Long,
	val serviceRunId: String,
) : Comparable<PressureServiceRunCursor> {
	constructor(run: SourceServiceRunEntity) : this(
		logicalTrackingId = run.logicalTrackingId,
		startedAtMs = run.startedAtMs,
		serviceRunId = run.serviceRunId,
	)

	override fun compareTo(other: PressureServiceRunCursor): Int = compareValuesBy(
		this,
		other,
		PressureServiceRunCursor::logicalTrackingId,
		PressureServiceRunCursor::startedAtMs,
		PressureServiceRunCursor::serviceRunId,
	)
}

private data class PressureFactLineageKey(
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
	val logicalFactId: String,
)

private data class PressureFactLoad(
	val revisions: List<PressureFactRevisionEntity>,
	val overflow: Boolean,
) {
	companion object {
		val EMPTY = PressureFactLoad(emptyList(), overflow = false)
	}
}

internal data class PressureLogicalMembershipExpansion(
	val segments: List<SessionSegment>,
	val failures: Map<String, PressureHistoryReason>,
)

internal data class PressureManifestKey(
	val logicalTrackingId: String,
	val manifestRevision: Long,
)

internal data class PressureLaneKey(
	val bindingGeneration: Long,
	val projectionId: String,
	val projectionVersion: Int,
)

@Suppress("LongParameterList")
internal data class PressureHistoryBatchSnapshot(
	val serviceRuns: Map<String, SourceServiceRunEntity>,
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByManifest: Map<PressureManifestKey, List<SessionManifestSourceEntity>>,
	val pressurePolicies: Map<Long, SourcePolicyEntity>,
	val pressureCaptureConsents: Map<Long, SourceConsentEpochEntity>,
	val deletionFenceDigests: Set<String>,
	val factRevisionsByRun: Map<String, List<PressureFactRevisionEntity>>,
	val invalidFactScopeLogicalIds: Set<String>,
	val logicalMembershipFailures: Map<String, PressureHistoryReason>,
	val factRevisionOverflow: Boolean,
	val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
	val productLanes: Map<PressureLaneKey, SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val terminalFailureOverflow: Boolean,
	val evidenceState: SourceEvidenceState?,
)

private const val PRESSURE_FACT_PAGE_SIZE = 400
private const val PRESSURE_QUERY_ID_BATCH_SIZE = 400
private const val MAX_PRESSURE_TERMINAL_FAILURES = 2_048
private const val MAX_PRESSURE_FACT_REVISIONS = 8_192
private const val MAX_PRESSURE_LOGICAL_MEMBERS = 64
private const val PRESSURE_MEMBERSHIP_PAGE_SIZE = 32
