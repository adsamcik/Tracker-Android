package com.adsamcik.tracker.shared.base.database.data

/** Mirrors the source-policy model's exact eligible Ambient consent reference semantics. */
fun SourcePolicyEntity.hasExactEligibleAmbientConsentReference(
	consent: SourceConsentEpochEntity?,
): Boolean = ambientConsentEpoch != null &&
		ambientPersistenceEligible &&
		consent != null &&
		consent.sourceKind == sourceKind &&
		consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
		consent.epoch == ambientConsentEpoch &&
		consent.eligible &&
		consent.persistenceEligible == ambientPersistenceEligible

/**
 * Compares the effective boundary using elapsed nanoseconds on the same boot, with UTC
 * milliseconds across boots.
 */
fun SourcePolicyEntity.isEffectiveAtOrBefore(
	bootId: String,
	elapsedRealtimeNanos: Long,
	wallTimeMs: Long,
): Boolean = effectiveTimeIsAtOrBefore(
	effectiveBootId,
	effectiveElapsedRealtimeNanos,
	effectiveWallTimeMs,
	bootId,
	elapsedRealtimeNanos,
	wallTimeMs,
)

/**
 * Compares the effective boundary using elapsed nanoseconds on the same boot, with UTC
 * milliseconds across boots.
 */
fun SourceConsentEpochEntity.isEffectiveAtOrBefore(
	bootId: String,
	elapsedRealtimeNanos: Long,
	wallTimeMs: Long,
): Boolean = effectiveTimeIsAtOrBefore(
	effectiveBootId,
	effectiveElapsedRealtimeNanos,
	effectiveWallTimeMs,
	bootId,
	elapsedRealtimeNanos,
	wallTimeMs,
)

/**
 * Compares the effective boundary using elapsed nanoseconds on the same boot, with UTC
 * milliseconds across boots.
 */
fun AmbientStepsRetentionAuthorityEntity.isEffectiveAtOrBefore(
	bootId: String,
	elapsedRealtimeNanos: Long,
	wallTimeMs: Long,
): Boolean = effectiveTimeIsAtOrBefore(
	effectiveBootId,
	effectiveElapsedRealtimeNanos,
	effectiveWallTimeMs,
	bootId,
	elapsedRealtimeNanos,
	wallTimeMs,
)

private fun effectiveTimeIsAtOrBefore(
	referenceBootId: String,
	referenceElapsedRealtimeNanos: Long,
	referenceWallTimeMs: Long,
	observedBootId: String,
	observedElapsedRealtimeNanos: Long,
	observedWallTimeMs: Long,
): Boolean = referenceWallTimeMs <= observedWallTimeMs &&
	(referenceBootId != observedBootId ||
		referenceElapsedRealtimeNanos <= observedElapsedRealtimeNanos)
