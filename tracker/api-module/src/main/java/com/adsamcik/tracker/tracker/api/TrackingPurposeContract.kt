package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.model.tracking.TrackingSource as CanonicalTrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity as CanonicalIdentity

/** Common source identity without replacing the existing public session-capture enum. */
typealias TrackingSource = TrackingCaptureSource

typealias TrackingPurpose =
	com.adsamcik.tracker.shared.model.tracking.TrackingPurpose

data class TrackingSourcePurposeIdentity(
	val source: TrackingSource,
	val purpose: TrackingPurpose,
) {
	val canonicalIdentity: CanonicalIdentity =
		CanonicalIdentity(source.toCanonicalTrackingSource(), purpose)

	companion object {
		fun from(identity: CanonicalIdentity): TrackingSourcePurposeIdentity =
			TrackingSourcePurposeIdentity(
				source = identity.source.toApiTrackingSource(),
				purpose = identity.purpose,
			)
	}
}

fun TrackingSource.supportsPurpose(purpose: TrackingPurpose): Boolean =
	toCanonicalTrackingSource().supports(purpose)

fun TrackingSource.forPurpose(purpose: TrackingPurpose): TrackingSourcePurposeIdentity =
	TrackingSourcePurposeIdentity(this, purpose)

fun TrackingSource.toCanonicalTrackingSource(): CanonicalTrackingSource = when (this) {
	TrackingSource.LOCATION -> CanonicalTrackingSource.LOCATION
	TrackingSource.WIFI -> CanonicalTrackingSource.WIFI
	TrackingSource.CELL -> CanonicalTrackingSource.CELL
	TrackingSource.ACTIVITY -> CanonicalTrackingSource.ACTIVITY
	TrackingSource.STEPS -> CanonicalTrackingSource.STEPS
	TrackingSource.PRESSURE -> CanonicalTrackingSource.PRESSURE
}

fun CanonicalTrackingSource.toApiTrackingSource(): TrackingSource = when (this) {
	CanonicalTrackingSource.LOCATION -> TrackingSource.LOCATION
	CanonicalTrackingSource.ACTIVITY -> TrackingSource.ACTIVITY
	CanonicalTrackingSource.STEPS -> TrackingSource.STEPS
	CanonicalTrackingSource.PRESSURE -> TrackingSource.PRESSURE
	CanonicalTrackingSource.WIFI -> TrackingSource.WIFI
	CanonicalTrackingSource.CELL -> TrackingSource.CELL
}

data class TrackingPurposeLeaseIdentity(
	val sourcePurpose: TrackingSourcePurposeIdentity,
	val policyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
	/** Zero explicitly means no writer execution has been bound to this reconciliation lease. */
	val executionRevision: Long,
	val ownerCasToken: String,
) {
	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(executionRevision >= 0L)
		require(ownerCasToken.isNotBlank())
	}

	val source: TrackingSource
		get() = sourcePurpose.source

	val purpose: TrackingPurpose
		get() = sourcePurpose.purpose
}

/**
 * Explicitly contained product decisions. These are status/explanation codes, never provider,
 * writer, policy, consent, rollout, or manual-session authority.
 */
enum class TrackingDecisionContainmentReason(
	val stableCode: String,
	val isOptionalProductDecision: Boolean,
) {
	AUTO_005_CONTROL_EVIDENCE_UNRESOLVED(
		"AUTO_005_CONTROL_EVIDENCE_UNRESOLVED",
		false,
	),
	RETENTION_AUTHORITY_UNAVAILABLE(
		"RETENTION_AUTHORITY_UNAVAILABLE",
		false,
	),
	DEVELOPMENT_V28_HANDLING_UNAVAILABLE(
		"DEVELOPMENT_V28_HANDLING_UNAVAILABLE",
		false,
	),
	CROSS_MIDNIGHT_POLICY_UNAVAILABLE(
		"CROSS_MIDNIGHT_POLICY_UNAVAILABLE",
		true,
	),
	EXPANDED_AMBIENT_LOCATION_UNAVAILABLE(
		"EXPANDED_AMBIENT_LOCATION_UNAVAILABLE",
		true,
	),
	RADIO_IDENTITY_UNAVAILABLE(
		"RADIO_IDENTITY_UNAVAILABLE",
		true,
	),
	PRESSURE_ELEVATION_UNAVAILABLE(
		"PRESSURE_ELEVATION_UNAVAILABLE",
		true,
	),
	;

	val grantsAuthority: Boolean
		get() = false
}
