package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope

@Suppress("ComplexCondition")
internal fun ProviderRegistrationGenerationEntity.authenticatesOwnerlessStepsWal(
	wal: SourceEventWalEntity,
): Boolean {
	val acceptedAt = acceptedAtMs ?: return false
	val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
	val capturedWallTime = wal.wallTimeMs ?: return false
	return matchesOwnerlessStepsRegistration(wal) &&
		hasValidOwnerlessCaptureTimes(wal, acceptedAt, acceptedElapsed, capturedWallTime)
}

private fun ProviderRegistrationGenerationEntity.matchesOwnerlessStepsRegistration(
	wal: SourceEventWalEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
	registrationGeneration == wal.registrationGeneration && sourceInstanceId == wal.sourceInstanceId &&
	SourceProviderPurposeScope.supportsPurpose(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		ownerScope,
		SourceBrokerPurpose.SESSION_CAPTURE,
	) &&
	clockDomainId == wal.clockDomainId &&
	physicalConfigurationFingerprint == wal.physicalConfigurationFingerprint &&
	collectedDataEpoch == wal.capturedCollectedDataEpoch &&
	wal.planAttribution == OWNERLESS_STEPS_CAPTURED_REGISTRATION_PLAN_ATTRIBUTION

private fun ProviderRegistrationGenerationEntity.hasValidOwnerlessCaptureTimes(
	wal: SourceEventWalEntity,
	acceptedAt: Long,
	acceptedElapsed: Long,
	capturedWallTime: Long,
): Boolean = hasValidOwnerlessRetirementTimes(wal, acceptedAt, acceptedElapsed, capturedWallTime) &&
	reservedAtMs in 0L..acceptedAt && reservedElapsedRealtimeNanos in 0L..acceptedElapsed &&
	acceptedAt <= capturedWallTime && acceptedElapsed <= wal.observedElapsedNanos &&
	(retiredAtMs == null) == (retiredElapsedRealtimeNanos == null)

private fun ProviderRegistrationGenerationEntity.hasValidOwnerlessRetirementTimes(
	wal: SourceEventWalEntity,
	acceptedAt: Long,
	acceptedElapsed: Long,
	capturedWallTime: Long,
): Boolean = when (status) {
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE -> retiredAtMs == null && retiredElapsedRealtimeNanos == null
	ProviderRegistrationGenerationEntity.STATUS_RETIRING,
	ProviderRegistrationGenerationEntity.STATUS_RETIRED,
	-> capturedBeforeOwnerlessRetirement(wal, acceptedAt, acceptedElapsed, capturedWallTime)
	else -> false
}

private fun ProviderRegistrationGenerationEntity.capturedBeforeOwnerlessRetirement(
	wal: SourceEventWalEntity,
	acceptedAt: Long,
	acceptedElapsed: Long,
	capturedWallTime: Long,
): Boolean {
	val retiredAt = retiredAtMs ?: return false
	val retiredElapsed = retiredElapsedRealtimeNanos ?: return false
	return retiredAt >= acceptedAt && retiredElapsed >= acceptedElapsed &&
		capturedWallTime < retiredAt && wal.observedElapsedNanos < retiredElapsed
}

@Suppress("ComplexCondition")
private fun SessionManifestSourceEntity.matchesStepsCaptureMembership(wal: SourceEventWalEntity): Boolean =
	logicalTrackingId == wal.logicalTrackingId && manifestRevision == wal.sessionManifestRevision &&
		sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		persistenceEligible && consentEpoch == wal.captureConsentEpoch &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS

internal fun SessionManifestSourceEntity.isExactLegacyStepsWriterBinding(
	wal: SourceEventWalEntity,
): Boolean =
	matchesStepsCaptureMembership(wal) &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL &&
		writerOwnerGeneration?.let { it > 0L } == true &&
		writerProjectionId == null &&
		writerProjectionVersion == null &&
		writerBindingGeneration == null

@Suppress("ComplexCondition")
internal fun SessionManifestSourceEntity.isExactCanonicalStepsWriterBinding(
	wal: SourceEventWalEntity,
): Boolean =
	matchesStepsCaptureMembership(wal) &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS &&
		writerOwnerGeneration?.let {
			it >= SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		} == true &&
		writerProjectionId == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION &&
		writerBindingGeneration in setOf(
			SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION,
		)

internal fun String.stepsCaptureModeMaskOrNull(): Long? = when (this) {
	"MANUAL" -> STEPS_MANUAL_CAPTURE_MODE_MASK
	"AUTOMATIC" -> STEPS_AUTOMATIC_CAPTURE_MODE_MASK
	else -> null
}

@Suppress("ComplexCondition")
internal fun SourceProductProjectionLaneEntity.hasAuthenticStepsWalPruneShape(): Boolean {
	val expectedCaptureModeMask = when (bindingGeneration) {
		SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION -> STEPS_MANUAL_CAPTURE_MODE_MASK
		SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION ->
			STEPS_MANUAL_CAPTURE_MODE_MASK or STEPS_AUTOMATIC_CAPTURE_MODE_MASK
		else -> return false
	}
	return hasStepsWalPruneProjectionIdentity(expectedCaptureModeMask) &&
		hasStepsWalPruneOrdinalCoverage() && hasStepsWalPruneLifecycleShape()
}

private fun SourceProductProjectionLaneEntity.hasStepsWalPruneProjectionIdentity(
	expectedCaptureModeMask: Long,
): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		projectionId == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID &&
		projectionVersion == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION &&
		captureModeMask == expectedCaptureModeMask &&
		productStage in setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)

private fun SourceProductProjectionLaneEntity.hasStepsWalPruneOrdinalCoverage(): Boolean =
	activatedRolloutRevision > 0L && activationOrdinal > 0L &&
		contiguousAdmissionOrdinal >= activationOrdinal - 1L &&
		captureAdmissionCutoffOrdinal?.let { cutoff ->
			cutoff >= activationOrdinal - 1L && contiguousAdmissionOrdinal <= cutoff
		} != false

private fun SourceProductProjectionLaneEntity.hasStepsWalPruneLifecycleShape(): Boolean =
	status in setOf(SourceProductProjectionLaneEntity.STATUS_ACTIVE, SourceProductProjectionLaneEntity.STATUS_RETIRED) &&
		(status == SourceProductProjectionLaneEntity.STATUS_ACTIVE) == retentionRequired &&
		hasStepsWalPruneTerminalShape() && installedAtMs >= 0L && updatedAtMs >= installedAtMs

private fun SourceProductProjectionLaneEntity.hasStepsWalPruneTerminalShape(): Boolean =
	if (status == SourceProductProjectionLaneEntity.STATUS_ACTIVE) {
		terminalDisposition == null && terminalAtMs == null
	} else {
		(terminalDisposition == null) == (terminalAtMs == null) &&
			terminalDisposition?.isNotBlank() != false && terminalAtMs?.let { it >= 0L } != false
	}


private const val STEPS_MANUAL_CAPTURE_MODE_MASK = 1L
private const val STEPS_AUTOMATIC_CAPTURE_MODE_MASK = 1L shl 1
internal const val OWNERLESS_STEPS_CAPTURED_REGISTRATION_PLAN_ATTRIBUTION = 0
