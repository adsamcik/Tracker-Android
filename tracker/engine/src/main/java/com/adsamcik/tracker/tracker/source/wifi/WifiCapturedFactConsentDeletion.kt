package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.dao.WifiCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.RetainedCaptureScopeIdentity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.DELETE_BATCH_SIZE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.FIRST_DELETION_GENERATION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.INSERT_IGNORED
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Authenticate the revoked policy and quiesce capture before inspecting retained data. */
internal suspend fun WifiCapturedFactMaintenance.prepareCaptureConsentDeletion(
	expectedCollectedDataEpoch: Long,
	expectedCurrentPolicyRevision: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
	limits: WifiCapturedMaintenanceLimits,
): WifiCapturedFactDao {
	val policyAuthority = database.sourcePolicyDao().authority()?.takeIf { authority ->
		authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
			authority.currentPolicyRevision == expectedCurrentPolicyRevision
	} ?: block(WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
	val policy = database.sourcePolicyDao().policyAtRevision(
		expectedCurrentPolicyRevision,
		WIFI_SOURCE,
	)
	val revokedConsent = database.sourcePolicyDao().latestConsentEpoch(
		WIFI_SOURCE,
		CAPTURE_PURPOSE,
	)
	if (policy == null || revokedConsent == null ||
		revokedConsent.epoch != expectedRevokedConsentEpoch
	) {
		block(WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
	}
	requireRevokedWifiCaptureConsent(policy, revokedConsent)
	// CONTROL/AMBIENT ingress advances the generic evidence clock; exact epoch/high-water
	// and the capture-specific clocks below remain the deletion authority.
	if (deletedAtMs < maxOf(
		policyAuthority.updatedAtMs,
		policy.effectiveWallTimeMs,
		revokedConsent.effectiveWallTimeMs,
	)) {
		block(WifiCapturedSourceDeletionBlockedReason.STALE_REQUEST)
	}

	val dao = database.wifiCapturedFactDao()
	quiesceCaptureForConsentDeletion(
		dao, expectedCollectedDataEpoch, policy, revokedConsent, limits,
	)
	return dao
}

/** Reconcile every retained scope before deciding whether deletion has already completed. */
internal suspend fun WifiCapturedFactMaintenance.reconcileCaptureDeletionScopes(
	scopeAuthorities: Collection<RetainedCaptureScopeIdentity>,
	generationByScope: Map<WifiCapturedRunScope, WifiCaptureDeletionGenerationEntity>,
	expectedCollectedDataEpoch: Long,
): List<RetainedCaptureScopeIdentity> {
	val scopesNeedingAuthority = mutableListOf<RetainedCaptureScopeIdentity>()
	for (identity in scopeAuthorities) {
		currentCoroutineContext().ensureActive()
		val scope = WifiCapturedRunScope(
			identity.logicalTrackingId,
			identity.serviceRunId,
		)
		val generation = generationByScope[scope]
		val fence = database.sourceDeletionFenceDao().get(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			identity.logicalRunScopeDigest,
		)
		if (generation == null && fence == null) {
			scopesNeedingAuthority += identity
			continue
		}
		authenticateWifiDeletionFence(identity, generation, fence, expectedCollectedDataEpoch)
	}
	return scopesNeedingAuthority
}

/** Install and read back both replay fences before any capture payload can be removed. */
internal suspend fun WifiCapturedFactMaintenance.installCaptureDeletionFences(
	dao: WifiCapturedFactDao,
	scopesNeedingAuthority: List<RetainedCaptureScopeIdentity>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
) {
	for (identity in scopesNeedingAuthority) {
		currentCoroutineContext().ensureActive()
		val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			identity.logicalTrackingId,
			identity.serviceRunId,
			FIRST_DELETION_GENERATION,
			expectedCollectedDataEpoch,
			deletedAtMs,
		)
		if (database.sourceDeletionFenceDao().insertIfAbsent(fence) == INSERT_IGNORED &&
			database.sourceDeletionFenceDao().get(
				fence.sourceKind,
				fence.purpose,
				fence.scopeKind,
				fence.scopeIdentityDigest,
			) != fence
		) {
			block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
		}
		val generation = WifiCaptureDeletionGenerationEntity(
			identity.logicalTrackingId,
			identity.serviceRunId,
			expectedCollectedDataEpoch,
			FIRST_DELETION_GENERATION,
			deletedAtMs,
		)
		dao.insertDeletionGeneration(generation)
		if (dao.deletionGeneration(
				identity.logicalTrackingId,
				identity.serviceRunId,
			) != generation ||
			database.sourceDeletionFenceDao().get(
				fence.sourceKind,
				fence.purpose,
				fence.scopeKind,
				fence.scopeIdentityDigest,
			) != fence
		) {
			block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
		}
	}
}

/** Remove the audited capture payload and its exact retention receipts, preserving other purposes. */
internal suspend fun WifiCapturedFactMaintenance.removeCapturePayloadAndRetentionProofs(
	dao: WifiCapturedFactDao,
	lineages: List<WifiCapturedLineage>,
	retainedEventProofs: List<SourceEventRetentionProofEntity>,
	retainedScopeProofs: List<SourceEventRetentionScopeProofEntity>,
) {
	lineages.dependencySafeDeletionBatches(DELETE_BATCH_SIZE).forEach { batch ->
		deleteExactLineageBatch(dao, batch) {
			block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	if (dao.revisionCount() != 0L || dao.cursorCount() != 0L) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	database.capturedRadioFactRetentionProofDao().deleteSource(WIFI_SOURCE)
	if (database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE).isNotEmpty()) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	removeWifiCaptureEventProofs(retainedEventProofs)
	removeWifiCaptureScopeProofs(retainedScopeProofs)
}

private fun requireRevokedWifiCaptureConsent(policy: SourcePolicyEntity, revokedConsent: SourceConsentEpochEntity) {
	if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null) {
		block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
	}
	if (revokedConsent.eligible || revokedConsent.persistenceEligible ||
		revokedConsent.policyRevision > policy.policyRevision
	) {
		block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
	}
}

private fun authenticateWifiDeletionFence(
	identity: RetainedCaptureScopeIdentity,
	generation: WifiCaptureDeletionGenerationEntity?,
	fence: SourceDeletionFenceEntity?,
	expectedCollectedDataEpoch: Long,
) {
	if (generation == null || fence == null || generation.logicalTrackingId != identity.logicalTrackingId ||
		generation.serviceRunId != identity.serviceRunId
	) {
		block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
	}
	if (generation.collectedDataEpoch != expectedCollectedDataEpoch ||
		generation.generation != FIRST_DELETION_GENERATION ||
		fence != SourceDeletionFenceEntity.createLogicalServiceRun(
			WIFI_SOURCE, CAPTURE_PURPOSE, identity.logicalTrackingId, identity.serviceRunId, FIRST_DELETION_GENERATION,
			expectedCollectedDataEpoch, generation.updatedAtMs,
		)
	) {
		block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
	}
}

private suspend fun WifiCapturedFactMaintenance.removeWifiCaptureEventProofs(
	retainedEventProofs: List<SourceEventRetentionProofEntity>,
) {
	if (retainedEventProofs.isNotEmpty()) {
		val eventIds = retainedEventProofs.map(SourceEventRetentionProofEntity::eventId)
		val deletedProofs = eventIds.chunked(DELETE_BATCH_SIZE).sumOf { batch ->
			database.sourceEventRetentionProofDao().deleteExactSourceEvents(
				WIFI_SOURCE,
				batch,
			)
		}
		if (deletedProofs != eventIds.size) {
			block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	if (database.sourceEventRetentionProofDao().captureProofCount(
			WIFI_SOURCE,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		) != 0L
	) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun WifiCapturedFactMaintenance.removeWifiCaptureScopeProofs(
	retainedScopeProofs: List<SourceEventRetentionScopeProofEntity>,
) {
	if (retainedScopeProofs.isNotEmpty()) {
		val scopeDigests =
			retainedScopeProofs.map(SourceEventRetentionScopeProofEntity::logicalRunScopeDigest)
		val deletedScopes = scopeDigests.chunked(DELETE_BATCH_SIZE).sumOf { batch ->
			database.sourceEventRetentionScopeProofDao().deleteExactSourceScopes(
				WIFI_SOURCE,
				batch,
			)
		}
		if (deletedScopes != scopeDigests.size) {
			block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	if (database.sourceEventRetentionScopeProofDao().countForSource(WIFI_SOURCE) != 0L) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

internal suspend fun WifiCapturedFactMaintenance.loadCapturedRetentionProofsForSourceErase(
	limits: WifiCapturedMaintenanceLimits,
): List<CapturedRadioFactRetentionProofEntity> = loadBoundedMaintenanceRows(
	maximumRows = limits.maximumLogicalFacts,
	pageSizeLimit = limits.revisionPageSize,
	nextCursor = { it.sourceAdmissionOrdinal to it.logicalFactId },
) { after, limit ->
	database.capturedRadioFactRetentionProofDao().sourceErasePage(
		WIFI_SOURCE, after?.first, after?.second, limit,
	)
}

internal suspend fun WifiCapturedFactMaintenance.loadSourceEventRetentionProofsForSourceErase(
	limits: WifiCapturedMaintenanceLimits,
): List<SourceEventRetentionProofEntity> = loadBoundedMaintenanceRows(
	maximumRows = limits.maximumWalEvents,
	pageSizeLimit = limits.revisionPageSize,
	nextCursor = SourceEventRetentionProofEntity::sourceAdmissionOrdinal,
) { after, limit ->
	database.sourceEventRetentionProofDao().sourceCaptureErasePage(
		WIFI_SOURCE, SourceBrokerPurpose.MASK_SESSION_CAPTURE, after, limit,
	)
}

internal suspend fun WifiCapturedFactMaintenance.loadSourceEventRetentionScopeProofsForSourceErase(
	limits: WifiCapturedMaintenanceLimits,
): List<SourceEventRetentionScopeProofEntity> = loadBoundedMaintenanceRows(
	maximumRows = limits.maximumDeletionGenerations,
	pageSizeLimit = limits.revisionPageSize,
	nextCursor = SourceEventRetentionScopeProofEntity::logicalRunScopeDigest,
) { after, limit ->
	database.sourceEventRetentionScopeProofDao().sourceErasePage(WIFI_SOURCE, after, limit)
}

/** Shared keyset traversal; each loader retains its concrete query, cursor and work bounds. */
internal suspend fun <Row, Cursor> WifiCapturedFactMaintenance.loadBoundedMaintenanceRows(
	maximumRows: Int,
	pageSizeLimit: Int,
	nextCursor: (Row) -> Cursor,
	readPage: suspend (Cursor?, Int) -> List<Row>,
): List<Row> {
	val rows = mutableListOf<Row>()
	var after: Cursor? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = maximumRows - rows.size
		if (remaining <= 0) {
			if (readPage(after, 1).isNotEmpty()) {
				throw WifiCapturedMaintenanceLimitExceeded()
			}
			break
		}
		val pageSize = minOf(remaining, pageSizeLimit)
		val page = readPage(after, pageSize)
		if (page.isEmpty()) {
			break
		}
		rows += page
		after = nextCursor(page.last())
		if (page.size < pageSize) {
			break
		}
	}
	return rows
}
