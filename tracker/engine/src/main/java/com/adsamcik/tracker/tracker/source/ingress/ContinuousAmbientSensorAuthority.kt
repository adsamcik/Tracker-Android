package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceKind

/**
 * Retains the original callback header while checking its all-day members across a real window.
 * Session changes and quality replacements preserve unchanged all-day consent; an interruption does not.
 * Call inside the same database snapshot as the original registration and authorization read.
 */
internal suspend fun SourceBrokerDao.continuousAmbientSensorMembers(
	claimed: SourceAuthorizationSnapshot,
	startElapsedNanos: Long,
	endElapsedNanos: Long,
): List<SourceAuthorizationEntity> {
	if (!claimed.supportsSensorInterval(startElapsedNanos, endElapsedNanos)) {
		return emptyList()
	}
	val original = authorizationAtBounded(claimed.members.first().sourceKind,
		claimed.members.first().registrationGeneration, claimed.effectiveBootId,
		startElapsedNanos, MAX_SENSOR_AUTHORIZATION_MEMBERS + 1).boundedSensorSnapshot() ?: return emptyList()
	return continuousSensorMembersUntil(claimed, original, endElapsedNanos)
}

private suspend fun SourceBrokerDao.continuousSensorMembersUntil(
	claimed: SourceAuthorizationSnapshot,
	original: SourceAuthorizationSnapshot,
	endElapsedNanos: Long,
): List<SourceAuthorizationEntity> {
	var candidates = claimed.authorizedMembers.filter(SourceAuthorizationEntity::isSessionlessAmbientSensor)
	var observed = original
	repeat(MAX_SENSOR_AUTHORIZATION_REVISIONS) {
		// Quality changes replace demands before the physical listener flushes. Keep the original
		// observation while this same source consent remains continuously selected.
		candidates = candidates.filter { member -> observed.authorizedMembers.any(member::hasSameAmbientSensorConsent) }
		if (candidates.isEmpty()) {
			return emptyList()
		}
		val nextRows = nextAuthorizationRevisionBounded(claimed.members.first().sourceKind,
			claimed.members.first().registrationGeneration, observed.authorizationRevision,
			MAX_SENSOR_AUTHORIZATION_MEMBERS + 1)
		val next = nextRows.boundedSensorSnapshot()
		if (nextRows.isNotEmpty() &&
			(next == null || !next.isOrderedSensorSuccessor(observed, claimed.effectiveBootId))) {
			return emptyList()
		}
		if (next == null || next.effectiveElapsedRealtimeNanos > endElapsedNanos) {
			return candidates
		}
		observed = next
	}
	return emptyList()
}

private fun SourceAuthorizationSnapshot.supportsSensorInterval(start: Long, end: Long): Boolean =
	members.first().sourceKind in setOf(SourceKind.ACTIVITY.stableCode, SourceKind.PRESSURE.stableCode,
		SourceKind.LOCATION.stableCode) &&
		start >= 0L && end >= start

private fun SourceAuthorizationSnapshot.isOrderedSensorSuccessor(
	previous: SourceAuthorizationSnapshot,
	bootId: String,
): Boolean = authorizationRevision > previous.authorizationRevision && effectiveBootId == bootId &&
	effectiveElapsedRealtimeNanos >= previous.effectiveElapsedRealtimeNanos

private fun SourceAuthorizationEntity.isSessionlessAmbientSensor(): Boolean =
	purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && persistenceEligible &&
		logicalTrackingId == null && serviceRunId == null

private fun SourceAuthorizationEntity.hasSameAmbientSensorConsent(other: SourceAuthorizationEntity): Boolean {
	if (!other.isSessionlessAmbientSensor()) {
		return false
	}
	val sameSource = sourceKind == other.sourceKind && registrationGeneration == other.registrationGeneration &&
		consumerId == other.consumerId
	return sameSource && purpose == other.purpose && consentEpoch == other.consentEpoch
}

private fun List<SourceAuthorizationEntity>.boundedSensorSnapshot(): SourceAuthorizationSnapshot? = try {
	takeIf { size <= MAX_SENSOR_AUTHORIZATION_MEMBERS }?.toAuthorizationSnapshotOrNull()
} catch (_: IllegalArgumentException) {
	null
}

private const val MAX_SENSOR_AUTHORIZATION_MEMBERS = 64
private const val MAX_SENSOR_AUTHORIZATION_REVISIONS = 256
