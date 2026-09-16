package com.adsamcik.tracker.tracker.api

/** Common source identity without replacing the existing public session-capture enum. */
typealias TrackingSource = TrackingCaptureSource

enum class TrackingPurpose(val stableName: String) {
	SESSION_CAPTURE("SESSION_CAPTURE"),
	CONTROL("CONTROL"),
	AMBIENT_PRODUCT("AMBIENT_PRODUCT"),
}

data class TrackingSourcePurposeIdentity(
	val source: TrackingSource,
	val purpose: TrackingPurpose,
) {
	init {
		require(source.supportsPurpose(purpose)) {
			"$source does not support ${purpose.stableName}"
		}
	}
}

fun TrackingSource.supportsPurpose(purpose: TrackingPurpose): Boolean = when (purpose) {
	TrackingPurpose.SESSION_CAPTURE -> true
	TrackingPurpose.CONTROL -> this == TrackingSource.ACTIVITY
	TrackingPurpose.AMBIENT_PRODUCT -> when (this) {
		TrackingSource.LOCATION,
		TrackingSource.WIFI,
		TrackingSource.CELL,
		TrackingSource.STEPS,
		-> true
		TrackingSource.ACTIVITY,
		TrackingSource.PRESSURE,
		-> false
	}
}

fun TrackingSource.forPurpose(purpose: TrackingPurpose): TrackingSourcePurposeIdentity =
	TrackingSourcePurposeIdentity(this, purpose)

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
}
