package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity

internal suspend fun AppDatabase.authenticateCellRegistration(
	registration: ProviderRegistrationGenerationEntity,
	expectedSourceInstanceId: String,
	expectedCollectedDataEpoch: Long,
	expectedClockDomainId: String,
	expectedPhysicalFingerprint: String,
	expectedAuthorizationRevision: Long,
): LongRange {
	val acceptedAtMs = registration.acceptedAtMs
	val acceptedElapsedNanos = registration.acceptedElapsedRealtimeNanos
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val retiredAtMs = registration.retiredAtMs
	val retiredElapsedNanos = registration.retiredElapsedRealtimeNanos
	val maximumCaptureAuthorizationRevision = sourceBrokerDao().maximumCaptureAuthorizationRevision(
		CELL_SOURCE,
		registration.registrationGeneration,
	)
	// The barrier is monotonic provider-owner evidence. Historical facts need only reject an
	// impossible acknowledgement without its exact original no-CAP envelope. Consent deletion
	// separately checks the current purpose and finite callback barrier; later capture cannot revoke old facts.
	val acceptedShape = registration.hasAcceptedCapturedCellStatus()
	if (!acceptedShape || !registration.hasCapturedCellRegistrationIdentity(
			expectedSourceInstanceId, expectedCollectedDataEpoch, expectedClockDomainId, expectedPhysicalFingerprint,
		)
	) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!registration.hasOrderedCapturedCellRegistrationTimes(
			acceptedAtMs, acceptedElapsedNanos, retiredAtMs, retiredElapsedNanos,
		)
	) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (expectedAuthorizationRevision <= 0L || expectedAuthorizationRevision > maximumCaptureAuthorizationRevision) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (registration.captureCallbackBarrierAuthorizationRevision > maximumCaptureAuthorizationRevision &&
		!hasExactRetainedRadioSessionCaptureCallbackBarrier(registration, expectedAuthorizationRevision)
	) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return acceptedElapsedNanos..(retiredElapsedNanos ?: Long.MAX_VALUE)
}

private fun ProviderRegistrationGenerationEntity.hasAcceptedCapturedCellStatus(): Boolean = when (status) {
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
		retiredAtMs == null && retiredElapsedRealtimeNanos == null && failureCode == null
	ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
		retiredAtMs != null && retiredElapsedRealtimeNanos != null && !failureCode.isNullOrBlank()
	ProviderRegistrationGenerationEntity.STATUS_RETIRED -> retiredAtMs != null && retiredElapsedRealtimeNanos != null
	else -> false
}

private fun ProviderRegistrationGenerationEntity.hasCapturedCellRegistrationIdentity(
	expectedSourceInstanceId: String, expectedCollectedDataEpoch: Long,
	expectedClockDomainId: String, expectedPhysicalFingerprint: String,
): Boolean = sourceKind == CELL_SOURCE && sourceInstanceId == expectedSourceInstanceId &&
	ownerScope == "source-broker:$CELL_SOURCE" &&
	providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
	!providerProcessIncarnationId.isNullOrBlank() && physicalConfigurationFingerprint == expectedPhysicalFingerprint &&
	collectedDataEpoch == expectedCollectedDataEpoch && clockDomainId == expectedClockDomainId

private fun ProviderRegistrationGenerationEntity.hasOrderedCapturedCellRegistrationTimes(
	acceptedWall: Long?, acceptedElapsed: Long, retiredWall: Long?, retiredElapsed: Long?,
): Boolean = acceptedWall != null && reservedAtMs <= acceptedWall && reservedElapsedRealtimeNanos <= acceptedElapsed &&
	(retiredWall == null || acceptedWall <= retiredWall) && (retiredElapsed == null || acceptedElapsed <= retiredElapsed)

private fun block(reason: CellCapturedRetentionBlockedReason): Nothing =
	throw CellCapturedRetentionBlockedException(reason)
