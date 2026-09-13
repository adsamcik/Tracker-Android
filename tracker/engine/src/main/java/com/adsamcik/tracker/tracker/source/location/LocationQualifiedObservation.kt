package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import java.time.ZoneId

internal data class LocationProviderTimeInterval(
	val startInclusiveNanos: Long,
	val endExclusiveNanos: Long,
) {
	init {
		require(startInclusiveNanos >= 0L)
		require(endExclusiveNanos > startInclusiveNanos)
	}
}

/** Exact provider-time authority inherited from durable registration and capture records. */
internal data class LocationCaptureTemporalAuthority(
	val providerRegistration: LocationProviderTimeInterval,
	val authorization: LocationProviderTimeInterval,
	val sourcePolicy: LocationProviderTimeInterval,
	val captureConsent: LocationProviderTimeInterval,
	val sessionManifest: LocationProviderTimeInterval,
	val lifecycleLease: LocationProviderTimeInterval,
) {
	val startInclusiveNanos: Long = maxOf(
		providerRegistration.startInclusiveNanos,
		authorization.startInclusiveNanos,
		sourcePolicy.startInclusiveNanos,
		captureConsent.startInclusiveNanos,
		sessionManifest.startInclusiveNanos,
		lifecycleLease.startInclusiveNanos,
	)

	val endExclusiveNanos: Long = minOf(
		providerRegistration.endExclusiveNanos,
		authorization.endExclusiveNanos,
		sourcePolicy.endExclusiveNanos,
		captureConsent.endExclusiveNanos,
		sessionManifest.endExclusiveNanos,
		lifecycleLease.endExclusiveNanos,
	)

	init {
		require(endExclusiveNanos > startInclusiveNanos) {
			"Location capture authority must have a non-empty provider-time intersection"
		}
	}
}

/** Current source-local deletion fence, intentionally separate from captured fact authority. */
internal data class LocationDeletionAuthority(
	val currentCollectedDataEpoch: Long,
	val retainedFromWallTimeMs: Long?,
) {
	init {
		require(currentCollectedDataEpoch >= 0L)
		require(retainedFromWallTimeMs == null || retainedFromWallTimeMs >= 0L)
	}
}

/** Exact acquisition limits that qualified the captured fix, not a renamed quality tier. */
internal data class LocationHistoricalAcquisitionConfiguration(
	val maximumObservationAgeNanos: Long,
	val maximumHorizontalAccuracyMeters: Float,
) {
	init {
		require(maximumObservationAgeNanos >= 0L)
		require(maximumHorizontalAccuracyMeters.isFinite())
		require(maximumHorizontalAccuracyMeters >= 0f)
	}
}

/** Immutable source, session, privacy, clock, and capture authority for one Location fact. */
internal data class LocationCaptureAuthority(
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sessionSegmentId: Long,
	val capturedSources: Set<SourceKind>,
	val controlSources: Set<SourceKind>,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val sessionManifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val capturedCollectedDataEpoch: Long,
	val clockDomainId: String,
	val zoneId: String,
	val permissionPrecision: LocationPermissionPrecision,
	val temporalAuthority: LocationCaptureTemporalAuthority,
	val acquisitionConfiguration: LocationHistoricalAcquisitionConfiguration,
) {
	init {
		require(sessionSegmentId > 0L)
		require(SourceKind.LOCATION in capturedSources) {
			"Captured Location authority requires Location in the immutable capture set"
		}
		require(capturedSources.intersect(controlSources).isEmpty()) {
			"A manifest source cannot be both captured and control-only"
		}
		require(registrationGeneration > 0L)
		require(configurationRevision >= 0L)
		require(physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L)
		require(authorizationFingerprint.isNotBlank())
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) {
			"Captured Location authority requires SESSION_CAPTURE eligibility"
		}
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(sessionManifestRevision > 0L)
		require(lifecycleLeaseGeneration > 0L)
		require(capturedCollectedDataEpoch >= 0L)
		require(clockDomainId.isNotBlank())
		require(zoneId.isNotBlank() && runCatching { ZoneId.of(zoneId) }.isSuccess) {
			"Location capture authority requires a valid stored zone"
		}
		require(permissionPrecision != LocationPermissionPrecision.UNKNOWN) {
			"Captured Location authority requires exact permission precision"
		}
	}
}

/** Durable clock conversion evidence captured before later clock or zone state can rotate. */
internal data class LocationDurableClockAuthority(
	val clockDomainId: String,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
)

/**
 * Immutable evidence decoded from one admitted WAL unit.
 *
 * [walIntegrityIdentity] binds the exact durable record bytes. Raw provider evidence can therefore
 * never be replaced under an existing delivery identity by a later caller's current authority.
 */
internal data class LocationDurableObservationEvidence(
	val sourceEventId: SourceEventId,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val sourceDeliveryIdentity: SourceDeliveryIdentity?,
	val deliveryUnitIndex: Int,
	val deliveryUnitCount: Int,
	val capturedAuthority: LocationCaptureAuthority,
	val clockAuthority: LocationDurableClockAuthority,
	val payloadVersion: Int,
	val payload: LocationFixPayload,
	val quality: SourceQuality,
	val isMock: Boolean,
)

/** Stable destination identity for one exact admitted provider-delivery unit. */
internal data class LocationCapturedFactIdentity(
	val sourceEventId: SourceEventId,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val deliveryUnitIndex: Int,
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sessionSegmentId: Long,
	val sessionManifestRevision: Long,
	val capturedCollectedDataEpoch: Long,
) {
	init {
		require(sourceAdmissionOrdinal > 0L)
		require(walIntegrityIdentity.matches(Regex("[0-9a-f]{64}")))
		require(deliveryUnitIndex >= 0)
		require(sessionSegmentId > 0L)
		require(sessionManifestRevision > 0L)
		require(capturedCollectedDataEpoch >= 0L)
	}
}

/** One append-only semantic revision of a stable Location fact identity. */
internal data class LocationCapturedFactMutation(
	val identity: LocationCapturedFactIdentity,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
) {
	init {
		require(semanticRevision > 0L)
		if (semanticRevision == 1L) {
			require(supersedesSemanticRevision == null)
		} else {
			require(supersedesSemanticRevision == semanticRevision - 1L) {
				"A Location correction must supersede the immediately preceding semantic revision"
			}
		}
	}
}

/** Recomputable qualification output; only this portion may change in a correction. */
internal data class LocationDerivedQualification(
	val qualifierVersion: Int,
	val deliveryAgeNanos: Long,
	val maximumObservationAgeNanos: Long,
	val maximumHorizontalAccuracyMeters: Float,
	val earliestPossibleWallTimeMs: Long,
	val latestPossibleWallTimeMs: Long,
) {
	init {
		require(qualifierVersion > 0)
		require(deliveryAgeNanos >= 0L)
		require(maximumObservationAgeNanos >= 0L)
		require(maximumHorizontalAccuracyMeters.isFinite())
		require(maximumHorizontalAccuracyMeters >= 0f)
		require(earliestPossibleWallTimeMs >= 0L)
		require(latestPossibleWallTimeMs >= earliestPossibleWallTimeMs)
	}
}

/** Complete product effect used for exact replay and semantic no-op checks. */
internal data class LocationCapturedProductEffect(
	val durableEvidence: LocationDurableObservationEvidence,
	val derivedQualification: LocationDerivedQualification,
) {
	val payload: LocationFixPayload
		get() = durableEvidence.payload

	val quality: SourceQuality
		get() = durableEvidence.quality

	val isMock: Boolean
		get() = durableEvidence.isMock

	val deliveryAgeNanos: Long
		get() = derivedQualification.deliveryAgeNanos
}

/** Dormant source-local write command. It does not activate or replace the canonical writer. */
internal data class LocationCapturedFactCommand(
	val mutation: LocationCapturedFactMutation,
	val authority: LocationCaptureAuthority,
	val productEffect: LocationCapturedProductEffect,
) {
	init {
		val identity = mutation.identity
		val evidence = productEffect.durableEvidence
		val clock = evidence.clockAuthority
		val derived = productEffect.derivedQualification
		require(identity.sourceEventId == evidence.sourceEventId)
		require(identity.sourceAdmissionOrdinal == evidence.sourceAdmissionOrdinal)
		require(identity.walIntegrityIdentity == evidence.walIntegrityIdentity)
		require(identity.sourceDeliveryIdentity == evidence.sourceDeliveryIdentity)
		require(identity.deliveryUnitIndex == evidence.deliveryUnitIndex)
		require(identity.deliveryUnitIndex < evidence.deliveryUnitCount)
		require(evidence.capturedAuthority == authority)
		require(identity.logicalTrackingId == authority.logicalTrackingId)
		require(identity.serviceRunId == authority.serviceRunId)
		require(identity.sessionSegmentId == authority.sessionSegmentId)
		require(identity.sessionManifestRevision == authority.sessionManifestRevision)
		require(identity.capturedCollectedDataEpoch == authority.capturedCollectedDataEpoch)
		require(clock.clockDomainId == authority.clockDomainId)
		require(derived.deliveryAgeNanos ==
			clock.receivedElapsedRealtimeNanos - clock.observedElapsedRealtimeNanos
		)
		require(derived.maximumObservationAgeNanos ==
			authority.acquisitionConfiguration.maximumObservationAgeNanos
		)
		require(derived.maximumHorizontalAccuracyMeters ==
			authority.acquisitionConfiguration.maximumHorizontalAccuracyMeters
		)
		require(derived.earliestPossibleWallTimeMs ==
			Math.subtractExact(clock.observedWallTimeMs, clock.wallTimeUncertaintyMs)
		)
		require(derived.latestPossibleWallTimeMs ==
			Math.addExact(clock.observedWallTimeMs, clock.wallTimeUncertaintyMs)
		)
	}
}
