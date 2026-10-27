package com.adsamcik.tracker.shared.base.database.data

/** Recording consent survives changes to a sibling purpose or a session's technical manifest. */
fun SourceAuthorizationEntity.hasSameSensorRecordingConsent(other: SourceAuthorizationEntity): Boolean =
	hasSameSensorRecordingScope(other) && hasSameSensorRecordingOwner(other)

private fun SourceAuthorizationEntity.hasSameSensorRecordingScope(other: SourceAuthorizationEntity): Boolean =
	sourceKind == other.sourceKind && registrationGeneration == other.registrationGeneration &&
		consumerId == other.consumerId && purpose == other.purpose && consentEpoch == other.consentEpoch &&
		persistenceEligible && other.persistenceEligible

private fun SourceAuthorizationEntity.hasSameSensorRecordingOwner(other: SourceAuthorizationEntity): Boolean =
	when (purpose) {
		SourceBrokerPurpose.AMBIENT_PRODUCT -> logicalTrackingId == null && serviceRunId == null &&
			other.logicalTrackingId == null && other.serviceRunId == null
		SourceBrokerPurpose.SESSION_CAPTURE -> logicalTrackingId != null && serviceRunId != null &&
			logicalTrackingId == other.logicalTrackingId && serviceRunId == other.serviceRunId
		else -> false
	}
