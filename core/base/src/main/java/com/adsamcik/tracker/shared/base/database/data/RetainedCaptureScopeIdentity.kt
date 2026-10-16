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
	/**
	 * Checks the expected source/data epoch, undeleted session/run ownership and canonical scope
	 * digest.
	 */
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

/**
 * Original admitted event, ordinal, policy/consent and WAL digest paired with its recorded
 * session/run scope.
 */
data class RetainedCaptureEventIdentity(
	val eventId: String,
	val sourceAdmissionOrdinal: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val walIntegrityIdentity: String,
	val scope: RetainedCaptureScopeIdentity,
)
