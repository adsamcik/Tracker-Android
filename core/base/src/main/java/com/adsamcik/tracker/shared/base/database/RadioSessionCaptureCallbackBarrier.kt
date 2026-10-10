package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

/**
 * Exact current radio no-session boundary in the existing acknowledgement field. Call inside the
 * owner's Room transaction after its policy, consent, process and finite callback-drain checks.
 */
suspend fun AppDatabase.hasExactRadioSessionCaptureCallbackBarrier(
	registration: ProviderRegistrationGenerationEntity,
	currentAuthorization: SourceAuthorizationSnapshot,
	minimumAcknowledgedRevision: Long,
): Boolean {
	if (registration.status !in setOf(ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			ProviderRegistrationGenerationEntity.STATUS_RETIRING) ||
		currentAuthorization.authorizedMembers.any {
			it.persistenceEligible && it.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}) return false
	val current = boundedRadioAuthorization(registration) ?: return false
	if (current.members.sortedBy { it.memberId } != currentAuthorization.members.sortedBy { it.memberId }) return false
	val lastCapture = sourceBrokerDao().maximumRadioSessionCaptureAuthorizationRevision(
		registration.sourceKind, registration.registrationGeneration)
	if (registration.captureCallbackBarrierAuthorizationRevision <= lastCapture) return false
	return hasExactAcknowledgedRadioSessionEnvelope(registration, current, minimumAcknowledgedRevision)
}

/**
 * Retained facts use the original acknowledged envelope covering their own authorization. Later
 * CAPTURE may restart on that provider; it does not rewrite earlier immutable recording authority.
 * This read-only check also accepts a terminal original physical registration.
 */
suspend fun AppDatabase.hasExactRetainedRadioSessionCaptureCallbackBarrier(
	registration: ProviderRegistrationGenerationEntity,
	minimumAcknowledgedRevision: Long,
): Boolean {
	val current = boundedRadioAuthorization(registration) ?: return false
	return hasExactAcknowledgedRadioSessionEnvelope(registration, current, minimumAcknowledgedRevision)
}

private suspend fun AppDatabase.boundedRadioAuthorization(
	registration: ProviderRegistrationGenerationEntity,
): SourceAuthorizationSnapshot? {
	if (registration.sourceKind !in setOf(SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.SOURCE_CELL)) return null
	val rows = sourceBrokerDao().latestRadioAuthorizationBounded(registration.sourceKind,
		registration.registrationGeneration, MAX_RADIO_SESSION_BARRIER_MEMBERS + 1)
	if (rows.size > MAX_RADIO_SESSION_BARRIER_MEMBERS) return null
	return try { rows.toAuthorizationSnapshotOrNull() }
	catch (@Suppress("SwallowedException") _: IllegalArgumentException) { null }
}

private suspend fun AppDatabase.hasExactAcknowledgedRadioSessionEnvelope(
	registration: ProviderRegistrationGenerationEntity,
	current: SourceAuthorizationSnapshot,
	minimumAcknowledgedRevision: Long,
): Boolean {
	if (minimumAcknowledgedRevision < 0L || registration.status !in setOf(
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE, ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED) ||
		sourceEvidenceStateDao().get()?.collectedDataEpoch != registration.collectedDataEpoch ||
		current.effectiveBootId != registration.clockDomainId) return false
	val ack = registration.captureCallbackBarrierAuthorizationRevision
	if (ack <= minimumAcknowledgedRevision || ack > current.authorizationRevision) return false
	val rows = sourceBrokerDao().authorizationRevisionBounded(registration.sourceKind,
		registration.registrationGeneration, ack, MAX_RADIO_SESSION_BARRIER_MEMBERS + 1)
	if (rows.size > MAX_RADIO_SESSION_BARRIER_MEMBERS) return false
	val acknowledged = try { rows.toAuthorizationSnapshotOrNull() }
	catch (@Suppress("SwallowedException") _: IllegalArgumentException) { null } ?: return false
	val acceptedAt = registration.acceptedElapsedRealtimeNanos ?: return false
	if (acknowledged.effectiveBootId != registration.clockDomainId ||
		registration.reservedElapsedRealtimeNanos > acceptedAt ||
		acknowledged.effectiveElapsedRealtimeNanos < registration.reservedElapsedRealtimeNanos ||
		acknowledged.effectiveElapsedRealtimeNanos > current.effectiveElapsedRealtimeNanos ||
		registration.retiredElapsedRealtimeNanos?.let { acknowledged.effectiveElapsedRealtimeNanos > it } == true ||
		acknowledged.authorizedMembers.any {
			it.persistenceEligible && it.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}) return false
	val ids = acknowledged.authorizedMembers.mapNotNull { it.demandId }
	if (ids.size != acknowledged.authorizedMembers.size || ids.distinct().size != ids.size) return false
	val demands = sourceBrokerDao().demandsByIds(ids)
	if (demands.size != ids.size || demands.any {
			it.sourceKind != registration.sourceKind || it.requestedBootId != registration.clockDomainId ||
			it.requestedElapsedRealtimeNanos > acknowledged.effectiveElapsedRealtimeNanos
		}) return false
	val reconstructed = try {
		SourceBrokerAuthorization.rows(registration.sourceKind, registration.registrationGeneration, ack,
			demands, acknowledged.effectiveBootId, acknowledged.effectiveElapsedRealtimeNanos, rows.first().effectiveWallTimeMs)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) { null }
	return reconstructed?.sortedBy { it.memberId } == rows.sortedBy { it.memberId }
}

private const val MAX_RADIO_SESSION_BARRIER_MEMBERS = 64
