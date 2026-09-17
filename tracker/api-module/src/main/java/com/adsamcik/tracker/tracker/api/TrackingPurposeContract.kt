package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.model.tracking.TrackingSource as CanonicalTrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity as CanonicalIdentity

/** Legacy session-capture source alias retained only at the existing API compatibility boundary. */
typealias TrackingSource = TrackingCaptureSource

typealias TrackingPurpose =
	com.adsamcik.tracker.shared.model.tracking.TrackingPurpose

internal fun TrackingSource.supportsPurpose(purpose: TrackingPurpose): Boolean =
	toCanonicalTrackingSource().supports(purpose)

internal fun TrackingSource.forPurpose(purpose: TrackingPurpose): CanonicalIdentity =
	toCanonicalTrackingSource().forPurpose(purpose)

internal fun TrackingSource.toCanonicalTrackingSource(): CanonicalTrackingSource = when (this) {
	TrackingSource.LOCATION -> CanonicalTrackingSource.LOCATION
	TrackingSource.WIFI -> CanonicalTrackingSource.WIFI
	TrackingSource.CELL -> CanonicalTrackingSource.CELL
	TrackingSource.ACTIVITY -> CanonicalTrackingSource.ACTIVITY
	TrackingSource.STEPS -> CanonicalTrackingSource.STEPS
	TrackingSource.PRESSURE -> CanonicalTrackingSource.PRESSURE
}

internal fun CanonicalTrackingSource.toApiTrackingSource(): TrackingSource = when (this) {
	CanonicalTrackingSource.LOCATION -> TrackingSource.LOCATION
	CanonicalTrackingSource.ACTIVITY -> TrackingSource.ACTIVITY
	CanonicalTrackingSource.STEPS -> TrackingSource.STEPS
	CanonicalTrackingSource.PRESSURE -> TrackingSource.PRESSURE
	CanonicalTrackingSource.WIFI -> TrackingSource.WIFI
	CanonicalTrackingSource.CELL -> TrackingSource.CELL
}

data class TrackingPurposeLeaseIdentity(
	val sourcePurpose: CanonicalIdentity,
	val policyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
	/** Zero explicitly means no writer execution has been bound to this reconciliation lease. */
	val executionRevision: Long,
	val ownerCasToken: String,
) {
	constructor(
		source: TrackingSource,
		purpose: TrackingPurpose,
		policyRevision: Long,
		consentEpoch: Long,
		collectedDataEpoch: Long,
		rolloutRevision: Long,
		executionRevision: Long,
		ownerCasToken: String,
	) : this(
		sourcePurpose = source.forPurpose(purpose),
		policyRevision = policyRevision,
		consentEpoch = consentEpoch,
		collectedDataEpoch = collectedDataEpoch,
		rolloutRevision = rolloutRevision,
		executionRevision = executionRevision,
		ownerCasToken = ownerCasToken,
	)

	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(executionRevision >= 0L)
		require(ownerCasToken.isNotBlank())
	}

	val source: CanonicalTrackingSource
		get() = sourcePurpose.source

	val purpose: TrackingPurpose
		get() = sourcePurpose.purpose

	companion object {
		@JvmStatic
		fun create(
			source: CanonicalTrackingSource,
			purpose: TrackingPurpose,
			policyRevision: Long,
			consentEpoch: Long,
			collectedDataEpoch: Long,
			rolloutRevision: Long,
			executionRevision: Long,
			ownerCasToken: String,
		): TrackingPurposeLeaseIdentity = TrackingPurposeLeaseIdentity(
			sourcePurpose = source.forPurpose(purpose),
			policyRevision = policyRevision,
			consentEpoch = consentEpoch,
			collectedDataEpoch = collectedDataEpoch,
			rolloutRevision = rolloutRevision,
			executionRevision = executionRevision,
			ownerCasToken = ownerCasToken,
		)

		@JvmStatic
		fun createLegacy(
			source: TrackingSource,
			purpose: TrackingPurpose,
			policyRevision: Long,
			consentEpoch: Long,
			collectedDataEpoch: Long,
			rolloutRevision: Long,
			executionRevision: Long,
			ownerCasToken: String,
		): TrackingPurposeLeaseIdentity = TrackingPurposeLeaseIdentity(
			source,
			purpose,
			policyRevision,
			consentEpoch,
			collectedDataEpoch,
			rolloutRevision,
			executionRevision,
			ownerCasToken,
		)
	}
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
