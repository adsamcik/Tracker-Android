package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.StepsFactCandidateState
import com.adsamcik.tracker.shared.base.database.dao.loadStepsFactCandidateStatesByRun
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
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity

/** Loads one bounded page's Steps history dependencies with a fixed query count. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun loadStepsHistoryBatchSnapshot(
	database: AppDatabase,
	segments: List<SessionSegment>,
): StepsHistoryBatchSnapshot {
	require(segments.isNotEmpty())
	val readDao = database.trackingHistoryReadDao()
	val serviceRunIds = segments.mapNotNull(SessionSegment::serviceRunId)
		.filter(String::isNotBlank)
		.distinct()
	val serviceRuns = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.serviceRuns(serviceRunIds)
	}
	val manifests = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.manifests(serviceRunIds)
	}
	val manifestSources = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.manifestSources(serviceRunIds)
	}
	val stepPolicies = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.policiesForServiceRuns(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			serviceRunIds = serviceRunIds,
		)
	}
	val requestedConsentEpochs = manifestSources.asSequence().filter { source ->
		source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible
	}.map(SessionManifestSourceEntity::consentEpoch).distinct().toList()
	val stepCaptureConsents = requestedConsentEpochs.chunked(QUERY_ID_BATCH_SIZE).flatMap { epochs ->
		database.sourcePolicyDao().consentEpochs(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			epochs = epochs,
		)
	}
	val completeness = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.completeness(serviceRunIds)
	}
	val factStatesByRun = readDao.loadStepsFactCandidateStatesByRun(serviceRuns)
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
	val productLanes = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		readDao.productLanesForServiceRuns(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			serviceRunIds = serviceRunIds,
		)
	}
	val afterOrdinal = productLanes.mapNotNull { lane ->
		lane.activationOrdinal.takeIf { it > 0L }?.minus(1L)
	}.minOrNull()
	val throughOrdinal = completeness.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull()
	val terminalFailures = if (
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
	val dependencyOverflow = when {
		terminalFailures.size > MAX_TERMINAL_FAILURES -> StepsHistoryBatchDependencyOverflow(
			dependency = StepsHistoryBatchDependency.TERMINAL_PROJECTION_FAILURES,
			affectedServiceRunIds = serviceRunIds.toSet(),
		)
		else -> null
	}
	return StepsHistoryBatchSnapshot(
		serviceRuns = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId),
		manifestsByRun = manifests.groupBy { it.serviceRunId },
		sourcesByManifest = manifestSources.groupBy {
			ManifestKey(it.logicalTrackingId, it.manifestRevision)
		},
		stepPolicies = stepPolicies.associateBy(SourcePolicyEntity::policyRevision),
		stepCaptureConsents = stepCaptureConsents.associateBy(SourceConsentEpochEntity::epoch),
		deletionFenceDigests = deletionFences.mapTo(hashSetOf()) { it.scopeIdentityDigest },
		factStatesByRun = factStatesByRun,
		completenessByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId),
		productLanes = productLanes.associateBy {
			HistoricalLaneKey(
				bindingGeneration = it.bindingGeneration,
				projectionId = it.projectionId,
				projectionVersion = it.projectionVersion,
			)
		},
		terminalFailures = terminalFailures.takeIf { dependencyOverflow == null }.orEmpty(),
		dependencyOverflow = dependencyOverflow,
		evidenceState = database.sourceEvidenceStateDao().get(),
	)
}

private const val MAX_TERMINAL_FAILURES = 2_048
private const val QUERY_ID_BATCH_SIZE = 400

internal enum class StepsHistoryBatchDependency {
	TERMINAL_PROJECTION_FAILURES,
}

internal data class StepsHistoryBatchDependencyOverflow(
	val dependency: StepsHistoryBatchDependency,
	val affectedServiceRunIds: Set<String>,
) {
	init {
		require(affectedServiceRunIds.isNotEmpty())
	}
}

internal data class ManifestKey(
	val logicalTrackingId: String,
	val manifestRevision: Long,
)

internal data class HistoricalLaneKey(
	val bindingGeneration: Long,
	val projectionId: String,
	val projectionVersion: Int,
)

internal data class StepsHistoryBatchSnapshot(
	val serviceRuns: Map<String, SourceServiceRunEntity>,
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByManifest: Map<ManifestKey, List<SessionManifestSourceEntity>>,
	val stepPolicies: Map<Long, SourcePolicyEntity>,
	val stepCaptureConsents: Map<Long, SourceConsentEpochEntity>,
	val deletionFenceDigests: Set<String>,
	val factStatesByRun: Map<String, List<StepsFactCandidateState>>,
	val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
	val productLanes: Map<HistoricalLaneKey, SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val dependencyOverflow: StepsHistoryBatchDependencyOverflow?,
	val evidenceState: SourceEvidenceState?,
)
