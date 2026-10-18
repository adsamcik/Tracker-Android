package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

/** Retained callbacks keep their identity when only the session's manifest/lease was refreshed. */
internal suspend fun AppDatabase.compatibleLocationCaptureAuthorizations(
	captured: SourceAuthorizationSnapshot,
	observed: SourceAuthorizationSnapshot?,
): List<SourceAuthorizationSnapshot>? = when {
	observed == captured -> listOf(captured)
	!observed.isLaterLocationAuthorization(captured) -> null
	else -> captured.locationCaptureMember()?.let { grant ->
		extendLocationCaptureAuthorizations(captured, requireNotNull(observed), grant)
	}
}

private fun SourceAuthorizationSnapshot?.isLaterLocationAuthorization(
	captured: SourceAuthorizationSnapshot,
): Boolean = this != null && authorizationRevision > captured.authorizationRevision &&
	effectiveBootId == captured.effectiveBootId

private suspend fun AppDatabase.extendLocationCaptureAuthorizations(
	captured: SourceAuthorizationSnapshot,
	observed: SourceAuthorizationSnapshot,
	grant: SourceAuthorizationEntity,
): List<SourceAuthorizationSnapshot>? {
	val result = mutableListOf(captured)
	repeat(MAX_LOCATION_CAPTURE_CONTINUITY_REVISIONS) {
		val previous = result.last()
		val next = sourceBrokerDao().nextAuthorizationRevision(LOCATION_SOURCE, grant.registrationGeneration,
			previous.authorizationRevision).toAuthorizationSnapshotOrNull() ?: return null
		if (!hasCompatibleLocationCaptureAuthorization(captured, previous, next, observed, grant)) {
			return null
		}
		result += next
		if (next.authorizationRevision == observed.authorizationRevision) {
			return result.takeIf { next == observed }
		}
	}
	return null
}

private suspend fun AppDatabase.hasCompatibleLocationCaptureAuthorization(
	captured: SourceAuthorizationSnapshot,
	previous: SourceAuthorizationSnapshot,
	next: SourceAuthorizationSnapshot,
	observed: SourceAuthorizationSnapshot,
	grant: SourceAuthorizationEntity,
): Boolean = next.hasLocationAuthorizationTimelineBounds(captured, previous, observed) &&
	next.locationCaptureMember()?.hasSameLocationCaptureGrant(grant) == true &&
	hasExactLocationAuthorizationRows(next)

private fun SourceAuthorizationSnapshot.hasLocationAuthorizationTimelineBounds(
	captured: SourceAuthorizationSnapshot,
	previous: SourceAuthorizationSnapshot,
	observed: SourceAuthorizationSnapshot,
): Boolean = effectiveBootId == captured.effectiveBootId &&
	effectiveElapsedRealtimeNanos >= previous.effectiveElapsedRealtimeNanos &&
	authorizationRevision <= observed.authorizationRevision

private suspend fun AppDatabase.hasExactLocationAuthorizationRows(authorization: SourceAuthorizationSnapshot): Boolean {
	val ids = authorization.authorizedMembers.mapNotNull { it.demandId }
	val demands = sourceBrokerDao().demandsByIds(ids)
	if (ids.distinct().size != authorization.authorizedMembers.size || demands.size != ids.size) {
		return false
	}
	val expected = runCatching {
		SourceBrokerAuthorization.rows(LOCATION_SOURCE, authorization.members.first().registrationGeneration,
			authorization.authorizationRevision, demands, authorization.effectiveBootId,
			authorization.effectiveElapsedRealtimeNanos, authorization.members.first().effectiveWallTimeMs)
	}.getOrNull()
	return expected?.sortedBy { it.memberId } == authorization.members.sortedBy { it.memberId }
}

internal fun SourceAuthorizationSnapshot.locationCaptureMember(): SourceAuthorizationEntity? =
	authorizedMembers.singleOrNull { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE && it.persistenceEligible }

private fun SourceAuthorizationEntity.hasSameLocationCaptureGrant(original: SourceAuthorizationEntity): Boolean =
	sourceKind == original.sourceKind && registrationGeneration == original.registrationGeneration &&
		consumerId == original.consumerId && logicalTrackingId == original.logicalTrackingId &&
		serviceRunId == original.serviceRunId && sourcePolicyRevision == original.sourcePolicyRevision &&
		consentEpoch == original.consentEpoch && manifestRevision != null && lifecycleLeaseGeneration != null

/** Every intervening manifest must keep this source's grant and destination unchanged. */
internal fun compatibleLocationCaptureManifestIndex(
	manifests: List<SessionManifestVersionEntity>,
	sources: List<SessionManifestSourceEntity>,
	capturedIndex: Int,
	authorizations: List<SourceAuthorizationSnapshot>,
	capturedSource: SessionManifestSourceEntity,
): Int? {
	val lastIndex = locationCaptureManifestEndIndex(manifests, authorizations, capturedIndex) ?: return null
	return if ((capturedIndex..lastIndex).all { index ->
		hasCompatibleLocationCaptureManifest(manifests[capturedIndex], manifests[index], sources, capturedSource)
	}) {
		lastIndex
	} else {
		null
	}
}

private fun locationCaptureManifestEndIndex(
	manifests: List<SessionManifestVersionEntity>,
	authorizations: List<SourceAuthorizationSnapshot>,
	capturedIndex: Int,
): Int? {
	var lastIndex = capturedIndex
	for (authorization in authorizations) {
		val member = authorization.locationCaptureMember() ?: return null
		val index = manifests.indexOfFirst { it.manifestRevision == member.manifestRevision }
		if (index < lastIndex) {
			return null
		}
		lastIndex = index
	}
	return lastIndex
}

private fun hasCompatibleLocationCaptureManifest(
	captured: SessionManifestVersionEntity,
	manifest: SessionManifestVersionEntity,
	sources: List<SessionManifestSourceEntity>,
	capturedSource: SessionManifestSourceEntity,
): Boolean {
	val source = sources.singleOrNull { it.logicalTrackingId == captured.logicalTrackingId &&
		it.manifestRevision == manifest.manifestRevision && it.sourceKind == LOCATION_SOURCE &&
		it.purpose == SourceBrokerPurpose.SESSION_CAPTURE } ?: return false
	return manifest.hasSameLocationCaptureContext(captured) &&
		source.copy(manifestRevision = capturedSource.manifestRevision) == capturedSource
}

private fun SessionManifestVersionEntity.hasSameLocationCaptureContext(
	captured: SessionManifestVersionEntity,
): Boolean =
	serviceRunId == captured.serviceRunId && effectiveBootId == captured.effectiveBootId &&
		sessionMode == captured.sessionMode && sourcePolicyRevision == captured.sourcePolicyRevision

private const val LOCATION_SOURCE = 1
private const val MAX_LOCATION_CAPTURE_CONTINUITY_REVISIONS = 256
