package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStoredOwner
import com.adsamcik.tracker.shared.base.database.dao.StepsFactCandidateState
import com.adsamcik.tracker.shared.base.database.dao.loadStepsFactCandidateStatesByRun
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.MAX_STEPS_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity

/** Loads one bounded page's Steps history dependencies with a fixed query count. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun loadStepsHistoryBatchSnapshot(
	database: AppDatabase,
	segments: List<SessionSegment>,
	selectedFactStatesByRun: Map<String, List<StepsFactCandidateState>>? = null,
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
	val factStatesByRun = selectedFactStatesByRun ?: readDao.loadStepsFactCandidateStatesByRun(serviceRuns)
	val carrierAuthority = loadStepsCarrierAuthority(database, factStatesByRun)
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
	val retentionTruncationDigests = segments.mapNotNull { segment ->
		val logicalTrackingId = segment.logicalTrackingId?.takeIf(String::isNotBlank)
		val serviceRunId = segment.serviceRunId?.takeIf(String::isNotBlank)
		if (logicalTrackingId == null || serviceRunId == null) {
			null
		} else {
			StepFactRevisionIntegrity.retentionTruncationIdentity(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			)
		}
	}.distinct()
	val retentionTruncationFences = if (retentionTruncationDigests.isEmpty()) {
		emptyList()
	} else {
		readDao.deletionFences(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigests = retentionTruncationDigests,
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
	val terminalFailures = if (selectedFactStatesByRun != null) {
		emptyList()
	} else if (
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
		retentionTruncationFencesByDigest = retentionTruncationFences.associateBy {
			it.scopeIdentityDigest
		},
		factStatesByRun = factStatesByRun,
		walByEventId = carrierAuthority.walByEventId,
		countDomainOwners = carrierAuthority.countDomainOwners,
		carrierAuthorityState = carrierAuthority.state,
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

internal suspend fun loadStepsCarrierAuthority(
	database: AppDatabase,
	factStatesByRun: Map<String, List<StepsFactCandidateState>>,
): StepsCarrierAuthorityLoad {
	val facts = factStatesByRun.values.asSequence().flatten()
		.mapNotNull(StepsFactCandidateState::scopeCarrier)
		.distinctBy { fact ->
			listOf(
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.logicalFactId,
				fact.semanticRevision,
			)
		}.toList()
	val eventIds = facts.mapNotNull(StepFactRevisionEntity::sourceEventId).distinct()
	val walRows = mutableListOf<SourceEventWalEntity>()
	for (batch in eventIds.chunked(QUERY_ID_BATCH_SIZE)) {
		walRows += database.sourceEventWalDao().boundedPayloadsByEventIds(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			eventIds = batch,
			maximumPayloadBytes = MAX_STEPS_WAL_PAYLOAD_BYTES,
			limit = batch.size,
		)
	}
	val walByEventId = walRows.associateBy(SourceEventWalEntity::eventId)
	if (walByEventId.keys != eventIds.toSet()) {
		return StepsCarrierAuthorityLoad(
			walByEventId,
			emptyMap(),
			StepsCarrierAuthorityState.UNVERIFIABLE,
		)
	}
	return loadStepsCountDomainOwners(database, facts, walByEventId)
}

private suspend fun loadStepsCountDomainOwners(
	database: AppDatabase,
	facts: List<StepFactRevisionEntity>,
	walByEventId: Map<String, SourceEventWalEntity>,
): StepsCarrierAuthorityLoad {
	val ownerKeys = mutableListOf<StepsCountDomainOwnerLookupKey>()
	for (fact in facts) {
		val wal = walByEventId[requireNotNull(fact.sourceEventId)]
			?: return StepsCarrierAuthorityLoad(
				walByEventId,
				emptyMap(),
				StepsCarrierAuthorityState.UNVERIFIABLE,
			)
		ownerKeys += wal.stepsWalOwnerKey()
		ownerKeys += fact.stepsFactOwnerKey()
	}
	val owners = linkedMapOf<StepsCountDomainOwnerLookupKey, StepsCountDomainStoredOwner>()
	for (chunk in ownerKeys.distinct().chunked(COUNT_DOMAIN_OWNER_BATCH_SIZE)) {
		when (val read = StepsCountDomainStore(database).readOwners(chunk)) {
			is StepsCountDomainOwnerRead.Ready -> owners.putAll(read.owners)
			StepsCountDomainOwnerRead.SchemaUnavailable -> return StepsCarrierAuthorityLoad(
				walByEventId,
				emptyMap(),
				StepsCarrierAuthorityState.UNAVAILABLE,
			)
			StepsCountDomainOwnerRead.Overflow,
			StepsCountDomainOwnerRead.Unverifiable,
			-> return StepsCarrierAuthorityLoad(
				walByEventId,
				emptyMap(),
				StepsCarrierAuthorityState.UNVERIFIABLE,
			)
		}
	}
	return StepsCarrierAuthorityLoad(
		walByEventId,
		owners,
		StepsCarrierAuthorityState.READY,
	)
}

private fun SourceEventWalEntity.stepsWalOwnerKey() = StepsCountDomainOwnerLookupKey(
	ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
	ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
		admissionOrdinal,
		eventId,
	),
	ownerRevision = 1L,
)

private fun StepFactRevisionEntity.stepsFactOwnerKey() = StepsCountDomainOwnerLookupKey(
	ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
	ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
		writerProjectionId,
		writerProjectionVersion,
		logicalFactId,
	),
	ownerRevision = semanticRevision,
)

private const val MAX_TERMINAL_FAILURES = 2_048
private const val QUERY_ID_BATCH_SIZE = 400
private const val COUNT_DOMAIN_OWNER_BATCH_SIZE = 400

internal enum class StepsCarrierAuthorityState {
	READY,
	UNAVAILABLE,
	UNVERIFIABLE,
}

internal data class StepsCarrierAuthorityLoad(
	val walByEventId: Map<String, SourceEventWalEntity>,
	val countDomainOwners: Map<StepsCountDomainOwnerLookupKey, StepsCountDomainStoredOwner>,
	val state: StepsCarrierAuthorityState,
)

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
	val retentionTruncationFencesByDigest: Map<String, SourceDeletionFenceEntity>,
	val factStatesByRun: Map<String, List<StepsFactCandidateState>>,
	val walByEventId: Map<String, SourceEventWalEntity> = emptyMap(),
	val countDomainOwners: Map<StepsCountDomainOwnerLookupKey, StepsCountDomainStoredOwner> = emptyMap(),
	val carrierAuthorityState: StepsCarrierAuthorityState = StepsCarrierAuthorityState.UNAVAILABLE,
	val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
	val productLanes: Map<HistoricalLaneKey, SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val dependencyOverflow: StepsHistoryBatchDependencyOverflow?,
	val evidenceState: SourceEvidenceState?,
)
