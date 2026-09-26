package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload

/** Authenticated ambient association, deliberately without any session or capture identity. */
internal data class LocationAmbientAuthority(
	val demandId: String,
	val sourcePolicyRevision: Long,
	val consentEpoch: Long,
	val retentionPolicyId: String,
	val retentionApprovalRevision: Long,
	val collectedDataEpoch: Long,
	val retainedFromMs: Long?,
	val maximumObservationAgeNanos: Long,
	val maximumHorizontalAccuracyMeters: Float,
) {
	init {
		require(demandId.isNotBlank() && retentionPolicyId.isNotBlank())
		require(sourcePolicyRevision > 0 && consentEpoch >= 0 && retentionApprovalRevision > 0)
		require(collectedDataEpoch >= 0 && (retainedFromMs == null || retainedFromMs >= 0))
		require(maximumObservationAgeNanos >= 0)
		require(maximumHorizontalAccuracyMeters.isFinite() && maximumHorizontalAccuracyMeters >= 0)
	}
}

/** Created only from authenticated stored delivery, policy, consent and retention evidence. */
internal data class LocationAmbientFactCommand(
	val wal: SourceEventWalEntity,
	val payload: LocationFixPayload,
	val authority: LocationAmbientAuthority,
	val acquisitionMetadata: LocationWalAcquisitionMetadata,
) {
	/** ByteArray is intentionally compared by the authenticated WAL identity, not reference equality. */
	fun hasSameAuthorityAndEvidence(other: LocationAmbientFactCommand): Boolean =
		wal.eventId == other.wal.eventId && wal.admissionOrdinal == other.wal.admissionOrdinal &&
			wal.integrityIdentity == other.wal.integrityIdentity && authority == other.authority &&
			payload == other.payload && acquisitionMetadata == other.acquisitionMetadata
}

internal sealed interface LocationAmbientQualification {
	data class Qualified(val command: LocationAmbientFactCommand) : LocationAmbientQualification
	data object NotEligible : LocationAmbientQualification
	data class Rejected(val reason: LocationWalAdapterRejection) : LocationAmbientQualification
}
