package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Canonical content and the minimal retained binding written by already-qualified Location writers. */
object LocationAcceptedReceiptIntegrity {
	/** Written only by the already-qualified canonical writer, in its existing transaction. */
	fun seal(receipt: LocationAcceptedReceiptEntity): LocationAcceptedReceiptEntity = receipt.copy(
		retainedProofVersion = 1, retainedProofChecksum = receiptChecksum(receipt),
	)

	/**
	 * Copies the supplied record with its retained checksum derived from its persisted values.
	 */
	fun seal(purpose: LocationSamplePurposeAuthorityEntity,
		receipt: LocationAcceptedReceiptEntity): LocationSamplePurposeAuthorityEntity = purpose.copy(
		retainedProofChecksum = purposeChecksum(purpose, receipt),
	)

	/**
	 * Checks the retained Location receipt or purpose checksum and required saved metadata.
	 */
	fun hasRetainedIntegrity(receipt: LocationAcceptedReceiptEntity): Boolean =
		receipt.retainedProofVersion == 1 && receipt.retainedProofChecksum == receiptChecksum(receipt)

	/**
	 * Checks the retained Location receipt or purpose checksum and required saved metadata.
	 */
	fun hasRetainedIntegrity(purpose: LocationSamplePurposeAuthorityEntity,
		receipt: LocationAcceptedReceiptEntity): Boolean = hasRetainedIntegrity(receipt) &&
		purpose.sampleId == receipt.sampleId &&
		purpose.retainedProofChecksum == purposeChecksum(purpose, receipt)

	/** Replay preserves old blank markers; equality never upgrades or manufactures their proof. */
	fun compatibleStoredReceipt(stored: LocationAcceptedReceiptEntity?,
		candidate: LocationAcceptedReceiptEntity): Boolean = stored == candidate ||
		(stored?.retainedProofVersion == 0 && stored.retainedProofChecksum.isEmpty() &&
			stored == candidate.copy(retainedProofVersion = 0, retainedProofChecksum = ""))

	/**
	 * Checks that stored Location purpose rows and the receipt agree on their original scope, epochs
	 * and capture binding.
	 */
	fun compatibleStoredPurpose(stored: LocationSamplePurposeAuthorityEntity?,
		candidate: LocationSamplePurposeAuthorityEntity): Boolean = stored == candidate ||
		(stored?.retainedProofChecksum?.isEmpty() == true &&
			stored == candidate.copy(retainedProofChecksum = ""))

	private fun receiptChecksum(r: LocationAcceptedReceiptEntity): String =
		AmbientWifiAuthorityIntegrity.digest("location-retained-receipt-v1", r.sampleId,
			r.sourceEventId, r.sourceSignalId, r.sourceAdmissionOrdinal, r.walIntegrityIdentity,
			r.canonicalContentChecksum, r.sourceInstanceId, r.registrationGeneration, r.sourceSequence,
			r.deliveryIdentity, r.deliveryUnitIndex, r.deliveryUnitCount, r.writerId, r.writerVersion,
			r.configurationRevision, r.physicalConfigurationFingerprint, r.authorizationRevision,
			r.authorizationFingerprint, r.clockDomainId, r.bootId, r.observedElapsedRealtimeNanos,
			r.receivedElapsedRealtimeNanos, r.observedWallTimeMs, r.receivedWallTimeMs,
			r.wallTimeUncertaintyMs, r.collectedDataEpoch)

	private fun purposeChecksum(p: LocationSamplePurposeAuthorityEntity,
		r: LocationAcceptedReceiptEntity): String = AmbientWifiAuthorityIntegrity.digest(
		"location-retained-purpose-v1", receiptChecksum(r), p.sampleId, p.purpose, p.authorityId,
		p.sourcePolicyRevision, p.consentEpoch, p.effectiveFromElapsedNanos, p.effectiveUntilElapsedNanos,
		p.logicalTrackingId, p.serviceRunId, p.sessionSegmentId, p.sessionManifestRevision,
		p.lifecycleLeaseGeneration, p.ambientIntentId, p.retentionPolicyId,
		p.retentionApprovalRevision, p.scopeDeletionGeneration, p.zoneId)

	/** Exactly the encoding already used by both qualified Location canonical writers. */
	fun canonicalChecksum(sample: LocationSample): String = MessageDigest.getInstance("SHA-256")
		.digest(sample.protectedLocationCanonicalReceiptBytes())
		.joinToString("") { "%02x".format(it) }

	/**
	 * Compares the stored Location receipt with the sample's measured values and canonical identity.
	 */
	// Compares one saved receipt/sample tuple and its deterministic canonical checksum.
	@Suppress("CyclomaticComplexMethod")
	fun matchesCanonicalSample(receipt: LocationAcceptedReceiptEntity, sample: LocationSample): Boolean =
		sample.id == receipt.sampleId &&
			sample.sourceEventId == receipt.sourceEventId &&
			sample.sourceSignalId == receipt.sourceSignalId &&
			receipt.sourceSignalId == protectedLocationCanonicalProductIdentity(receipt.sourceEventId) &&
			sample.clockDomainId == receipt.clockDomainId &&
			(sample.bootClockDomainId == null || sample.bootClockDomainId == receipt.bootId) &&
			sample.timeMs == receipt.observedWallTimeMs &&
			sample.elapsedRealtimeNanos == receipt.observedElapsedRealtimeNanos &&
			sample.receivedElapsedRealtimeNanos == receipt.receivedElapsedRealtimeNanos &&
			receipt.canonicalContentChecksum == canonicalChecksum(sample)
}
