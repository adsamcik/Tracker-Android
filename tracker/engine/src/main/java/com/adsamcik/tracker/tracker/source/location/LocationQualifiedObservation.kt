package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
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

/**
 * Exact observed-time authority inherited from the provider registration and capture records.
 *
 * The intersection is evaluated against provider time. Receipt time must never make an otherwise
 * pre-effective fix eligible for product history.
 */
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

/** The source-local deletion fence. Epoch rotation makes every older command ineligible. */
internal data class LocationDeletionAuthority(
	val collectedDataEpoch: Long,
	val retainedFromWallTimeMs: Long?,
) {
	init {
		require(collectedDataEpoch >= 0L)
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

/**
 * Immutable source, session, privacy, clock, and deletion authority for one Location fact command.
 *
 * A Location-only session is represented by `capturedSources == setOf(LOCATION)`. No sample-count
 * or another source is involved in the qualification contract.
 */
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
	val deletion: LocationDeletionAuthority,
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
		require(clockDomainId.isNotBlank())
		require(zoneId.isNotBlank() && runCatching { ZoneId.of(zoneId) }.isSuccess) {
			"Location capture authority requires a valid stored zone"
		}
		require(permissionPrecision != LocationPermissionPrecision.UNKNOWN) {
			"Captured Location authority requires exact permission precision"
		}
	}
}

/** Stable destination identity for a provider delivery unit and its exact session ownership. */
internal data class LocationCapturedFactIdentity(
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val deliveryUnitIndex: Int,
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sessionSegmentId: Long,
	val sessionManifestRevision: Long,
	val collectedDataEpoch: Long,
) {
	init {
		require(deliveryUnitIndex >= 0)
		require(sessionSegmentId > 0L)
		require(sessionManifestRevision > 0L)
		require(collectedDataEpoch >= 0L)
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

/** Complete product effect used for exact replay and semantic no-op checks. */
internal data class LocationCapturedProductEffect(
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val deliveryUnitCount: Int,
	val payload: LocationFixPayload,
) {
	init {
		require(observedElapsedRealtimeNanos > 0L)
		require(receivedElapsedRealtimeNanos >= observedElapsedRealtimeNanos)
		require(observedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
		require(deliveryUnitCount > 0)
	}

	val deliveryAgeNanos: Long
		get() = receivedElapsedRealtimeNanos - observedElapsedRealtimeNanos
}

/** Dormant source-local write command. It does not activate or replace the canonical writer. */
internal data class LocationCapturedFactCommand(
	val mutation: LocationCapturedFactMutation,
	val authority: LocationCaptureAuthority,
	val productEffect: LocationCapturedProductEffect,
) {
	init {
		require(mutation.identity.deliveryUnitIndex < productEffect.deliveryUnitCount)
		require(mutation.identity.logicalTrackingId == authority.logicalTrackingId)
		require(mutation.identity.serviceRunId == authority.serviceRunId)
		require(mutation.identity.sessionSegmentId == authority.sessionSegmentId)
		require(mutation.identity.sessionManifestRevision == authority.sessionManifestRevision)
		require(mutation.identity.collectedDataEpoch == authority.deletion.collectedDataEpoch)
	}
}
