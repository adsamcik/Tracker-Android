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

/** Loads all bounded-page dependencies for one Pressure history snapshot without per-row queries. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun loadPressureHistoryBatchSnapshot(
	database: AppDatabase,
	segments: List<SessionSegment>,
): PressureHistoryBatchSnapshot {
	require(segments.isNotEmpty())
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
	val pressureFacts = loadPressureHistoryFactRevisions(database, serviceRunIds)
	val hasCrossScopeFactRevisions = serviceRunIds.isNotEmpty() &&
		database.pressureFactRevisionDao().hasCrossScopeRevisionsForRuns(serviceRunIds)
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
		hasCrossScopeFactRevisions = hasCrossScopeFactRevisions,
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
): List<PressureFactRevisionEntity> {
	if (serviceRunIds.isEmpty()) return emptyList()
	val facts = mutableListOf<PressureFactRevisionEntity>()
	var cursor: PressureFactCursor? = null
	while (true) {
		val page = database.pressureFactRevisionDao().historyRevisionPage(
			serviceRunIds = serviceRunIds,
			limit = PRESSURE_FACT_PAGE_SIZE,
			afterServiceRunId = cursor?.serviceRunId,
			afterWriterProjectionId = cursor?.writerProjectionId,
			afterWriterProjectionVersion = cursor?.writerProjectionVersion,
			afterLogicalFactId = cursor?.logicalFactId,
			afterSemanticRevision = cursor?.semanticRevision,
		)
		if (page.isEmpty()) break
		val nextCursor = PressureFactCursor(page.last())
		check(cursor == null || nextCursor > cursor) { "Pressure history fact cursor did not advance" }
		facts += page
		cursor = nextCursor
		if (page.size < PRESSURE_FACT_PAGE_SIZE) break
	}
	return facts
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
	val hasCrossScopeFactRevisions: Boolean,
	val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
	val productLanes: Map<PressureLaneKey, SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val terminalFailureOverflow: Boolean,
	val evidenceState: SourceEvidenceState?,
)

private const val PRESSURE_FACT_PAGE_SIZE = 400
private const val PRESSURE_QUERY_ID_BATCH_SIZE = 400
private const val MAX_PRESSURE_TERMINAL_FAILURES = 2_048
