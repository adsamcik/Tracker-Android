package com.adsamcik.tracker.shared.base.database.data

/**
 * Exact payload-free capture scope retained after its source payload and WAL may both be absent.
 */
data class RetainedCaptureScopeIdentity(
	val sourceKind: Int,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val collectedDataEpoch: Long,
	val scopeDeletionGeneration: Long,
	val logicalRunScopeDigest: String,
) {
	fun isCanonicalFor(expectedSourceKind: Int, expectedCollectedDataEpoch: Long): Boolean =
		sourceKind == expectedSourceKind &&
			logicalTrackingId.isNotBlank() &&
			serviceRunId.isNotBlank() &&
			collectedDataEpoch == expectedCollectedDataEpoch &&
			scopeDeletionGeneration == 0L &&
			logicalRunScopeDigest == SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = sourceKind,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			)
}

data class RetainedCaptureEventIdentity(
	val eventId: String,
	val sourceAdmissionOrdinal: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val walIntegrityIdentity: String,
	val scope: RetainedCaptureScopeIdentity,
)
