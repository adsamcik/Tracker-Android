package com.adsamcik.tracker.shared.base.database.data

internal val SOURCE_WAL_SHA_256 = Regex("[0-9a-f]{64}")
internal const val SOURCE_WAL_CAPTURED_REGISTRATION_PLAN = 0
internal const val SOURCE_WAL_KNOWN_QUALITY_FLAGS_MASK = (1L shl 9) - 1L

internal fun SourceEventWalEntity.hasExactQualifiedIntegrity(
	maximumPayloadBytes: Int,
): Boolean =
	payload.isNotEmpty() &&
		payload.size <= maximumPayloadBytes &&
		SOURCE_WAL_SHA_256.matches(payloadChecksum) &&
		payloadChecksum == calculatedPayloadChecksum() &&
		SOURCE_WAL_SHA_256.matches(integrityIdentity) &&
		integrityIdentity == calculatedIntegrityIdentity()

internal fun SourceEventWalEntity.hasCompleteSessionCaptureBinding(): Boolean =
	logicalTrackingId?.isNotBlank() == true &&
		serviceRunId?.isNotBlank() == true &&
		sourceInstanceId.isNotBlank() &&
		registrationGeneration > 0L &&
		configRevision?.let { it > 0L } == true &&
		physicalConfigurationFingerprint?.isNotBlank() == true &&
		authorizationRevision?.let { it > 0L } == true &&
		authorizationFingerprint?.let(SOURCE_WAL_SHA_256::matches) == true &&
		authorizationPurposeEligibilityMask and SourceBrokerPurpose.ALL_MASK ==
		authorizationPurposeEligibilityMask &&
		authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L &&
		sourcePolicyRevision?.let { it > 0L } == true &&
		captureConsentEpoch?.let { it >= 0L } == true &&
		sessionManifestRevision?.let { it > 0L } == true &&
		lifecycleLeaseGeneration?.let { it > 0L } == true &&
		capturedCollectedDataEpoch >= 0L &&
		clockDomainId.isNotBlank() &&
		planAttribution == SOURCE_WAL_CAPTURED_REGISTRATION_PLAN

internal fun SourceEventWalEntity.hasCanonicalQuality(): Boolean =
	qualityFlags >= 0L &&
		qualityFlags and SOURCE_WAL_KNOWN_QUALITY_FLAGS_MASK == qualityFlags &&
		qualityConfidence?.let { it.isFinite() && it in 0f..1f } != false

internal fun sameFloat(first: Float?, second: Float?): Boolean = when {
	first == null || second == null -> first == null && second == null
	else -> first.toRawBits() == second.toRawBits()
}

internal fun sameDouble(first: Double?, second: Double?): Boolean = when {
	first == null || second == null -> first == null && second == null
	else -> first.toRawBits() == second.toRawBits()
}
