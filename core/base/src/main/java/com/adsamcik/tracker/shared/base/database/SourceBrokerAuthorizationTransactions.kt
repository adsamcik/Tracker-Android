package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

/**
 * Rotates the callback authorization of every same-boot physical registration at one observed-time
 * boundary. The caller must already own the Room transaction that changes the corresponding
 * policy, consent, manifest, or demand authority.
 */
suspend fun AppDatabase.rotateCurrentSourceAuthorizationInTransaction(
	sourceKind: Int,
	bootId: String,
	elapsedRealtimeNanos: Long,
	wallTimeMs: Long,
) {
	require(sourceKind > 0)
	require(bootId.isNotBlank())
	require(elapsedRealtimeNanos >= 0L)
	require(wallTimeMs >= 0L)
	val dao = sourceBrokerDao()
	val sourceDemands = dao.authorizationDemands(sourceKind)
	dao.currentPhysicalRegistrations(sourceKind)
		.filter { registration -> registration.clockDomainId == bootId }
		.forEach { registration ->
			val demands = SourceProviderPurposeScope.selectDemands(
				sourceKind,
				registration.ownerScope,
				sourceDemands,
			)
			val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
			val current = dao.latestAuthorization(sourceKind, registration.registrationGeneration)
				.toAuthorizationSnapshotOrNull()
			if (current != null &&
				current.authorizationFingerprint == fingerprint &&
				current.effectiveBootId == bootId
			) return@forEach
			val revision = dao.maximumAuthorizationRevision(sourceKind) + 1L
			check(revision > 0L) { "Source authorization revision exhausted for $sourceKind" }
			val boundary = sourceAuthorizationPublicationBoundary(demands, current, bootId,
				elapsedRealtimeNanos, wallTimeMs)
			dao.insertAuthorizations(
				SourceBrokerAuthorization.rows(
					sourceKind = sourceKind,
					registrationGeneration = registration.registrationGeneration,
					authorizationRevision = revision,
					demands = demands,
					effectiveBootId = bootId,
					effectiveElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
					effectiveWallTimeMs = boundary.wallTimeMs,
				),
			)
		}
}

/** Effective publication time; grant preparation must never move a newer membership into the past. */
data class SourceAuthorizationPublicationBoundary(val elapsedRealtimeNanos: Long, val wallTimeMs: Long)

/** Withdrawals retain their original cutoff, including a Stop that leaves a sibling purpose active. */
fun sourceAuthorizationPublicationBoundary(
	demands: Collection<SourceDemandEntity>,
	previous: SourceAuthorizationSnapshot?,
	bootId: String,
	elapsedRealtimeNanos: Long,
	wallTimeMs: Long,
): SourceAuthorizationPublicationBoundary {
	val previousIds = previous?.authorizedMembers?.mapNotNull { it.demandId }.orEmpty().toSet()
	val withdrawsGrant = previous?.authorizedMembers?.any { member ->
		demands.none { demand -> member.hasSameRecordingGrant(demand) }
	} == true
	if (withdrawsGrant || demands.none { it.demandId !in previousIds }) {
		return SourceAuthorizationPublicationBoundary(elapsedRealtimeNanos, wallTimeMs)
	}
	val prior = previous?.takeIf { it.effectiveBootId == bootId }
	val latestDemand = demands.filter { it.requestedBootId == bootId }.maxByOrNull { it.requestedElapsedRealtimeNanos }
	val effectiveElapsed = maxOf(elapsedRealtimeNanos, prior?.effectiveElapsedRealtimeNanos ?: 0L,
		latestDemand?.requestedElapsedRealtimeNanos ?: 0L)
	val effectiveWall = if (effectiveElapsed == elapsedRealtimeNanos) {
		wallTimeMs
	} else {
		maxOf(wallTimeMs, prior?.members?.first()?.effectiveWallTimeMs ?: 0L, latestDemand?.requestedAtMs ?: 0L)
	}
	return SourceAuthorizationPublicationBoundary(effectiveElapsed, effectiveWall)
}

private fun SourceAuthorizationEntity.hasSameRecordingGrant(demand: SourceDemandEntity): Boolean {
	val sameSource = sourceKind == demand.sourceKind && consumerId == demand.consumerId && purpose == demand.purpose
	val sameConsent = sourcePolicyRevision == demand.sourcePolicyRevision && consentEpoch == demand.consentEpoch &&
		persistenceEligible == demand.persistenceEligible
	val sameSession = logicalTrackingId == demand.logicalTrackingId && serviceRunId == demand.serviceRunId
	val sameRetention = liveAmbientRetentionPolicyId == demand.liveAmbientRetentionPolicyId &&
		liveAmbientRetentionApprovalRevision == demand.liveAmbientRetentionApprovalRevision
	return sameSource && sameConsent && sameSession && sameRetention
}

/**
 * Immediately closes the selected policy purposes without waiting for Android provider teardown.
 * ACTIVE demands enter RETIRING so their callback barrier can drain. BLOCKED prepared demands are
 * retired immediately and therefore can never be activated after this boundary.
 */
suspend fun AppDatabase.fenceSourcePurposesInTransaction(
	sourceKind: Int,
	purposes: Collection<String>,
	bootId: String,
	elapsedRealtimeNanos: Long,
	wallTimeMs: Long,
): Int {
	if (purposes.isEmpty()) return 0
	val updated = sourceBrokerDao().markSourcePurposesRetiring(
		sourceKind = sourceKind,
		purposes = purposes,
		bootId = bootId,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = wallTimeMs,
	)
	rotateCurrentSourceAuthorizationInTransaction(
		sourceKind = sourceKind,
		bootId = bootId,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = wallTimeMs,
	)
	return updated
}
