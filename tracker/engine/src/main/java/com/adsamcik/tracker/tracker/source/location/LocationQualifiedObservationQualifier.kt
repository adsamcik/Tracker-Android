package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity

internal enum class LocationObservationOrigin {
	PROVIDER_CALLBACK,
	CACHE_READ,
	TIMER,
	REQUEST_ATTEMPT,
}

internal enum class LocationProviderOutcome {
	FIX,
	NO_FIX,
	PROVIDER_UNAVAILABLE,
	PERMISSION_UNAVAILABLE,
	FAILED,
}

/** Source-boundary input. Provider time and exact capture authority are never inferred at receipt. */
internal data class LocationObservationInput(
	val origin: LocationObservationOrigin,
	val outcome: LocationProviderOutcome,
	val capturedAuthority: LocationCaptureAuthority,
	val sourceDeliveryIdentity: SourceDeliveryIdentity?,
	val deliveryUnitIndex: Int?,
	val deliveryUnitCount: Int?,
	val observedElapsedRealtimeNanos: Long?,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long?,
	val wallTimeUncertaintyMs: Long?,
	val payload: LocationFixPayload?,
)

internal enum class LocationStaleReason {
	PRE_EFFECTIVE,
	TOO_OLD,
	BEFORE_DELETION_FLOOR,
}

internal enum class LocationUnavailableReason {
	NO_FIX,
	PROVIDER_UNAVAILABLE,
	PERMISSION_UNAVAILABLE,
	PROVIDER_FAILURE,
}

internal enum class LocationFactRejection {
	NOT_PROVIDER_OBSERVATION,
	INCONSISTENT_PROVIDER_RESULT,
	AUTHORITY_MISMATCH,
	NON_POSITIVE_PROVIDER_TIME,
	FUTURE_PROVIDER_TIME,
	AFTER_AUTHORITY,
	CLOCK_UNVERIFIABLE,
	DELIVERY_IDENTITY_UNVERIFIABLE,
	INVALID_DELIVERY_POSITION,
	INVALID_COORDINATE,
	INVALID_ACCURACY,
	INSUFFICIENT_ACCURACY,
	INVALID_PROVIDER,
	INVALID_CORRECTION_BASE,
	DELIVERY_IDENTITY_COLLISION,
}

internal sealed interface LocationObservationQualification {
	data class Qualified(val command: LocationCapturedFactCommand) : LocationObservationQualification
	data class Duplicate(val existing: LocationCapturedFactCommand) : LocationObservationQualification
	data class Stale(val reason: LocationStaleReason) : LocationObservationQualification
	data class Unavailable(val reason: LocationUnavailableReason) : LocationObservationQualification
	data class Rejected(val reason: LocationFactRejection) : LocationObservationQualification
}

/**
 * Pure qualification boundary for a future Location fact writer.
 *
 * This object neither registers a provider nor writes storage. The established Location writer
 * remains canonical until the separate shadow/cutover decision is evidenced.
 */
internal object LocationQualifiedObservationQualifier {
	fun qualify(
		input: LocationObservationInput,
		expectedAuthority: LocationCaptureAuthority,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		priorDeliveryFact: LocationCapturedFactCommand? = null,
		correctionBase: LocationCapturedFactCommand? = null,
	): LocationObservationQualification {
		if (input.origin != LocationObservationOrigin.PROVIDER_CALLBACK) {
			return LocationObservationQualification.Rejected(
				LocationFactRejection.NOT_PROVIDER_OBSERVATION,
			)
		}
		if (input.capturedAuthority != expectedAuthority) {
			return LocationObservationQualification.Rejected(
				LocationFactRejection.AUTHORITY_MISMATCH,
			)
		}
		if (input.outcome != LocationProviderOutcome.FIX) {
			if (input.payload != null) {
				return LocationObservationQualification.Rejected(
					LocationFactRejection.INCONSISTENT_PROVIDER_RESULT,
				)
			}
			return input.outcome.toUnavailable()
		}
		val payload = input.payload
			?: return LocationObservationQualification.Rejected(
				LocationFactRejection.INCONSISTENT_PROVIDER_RESULT,
			)
		val observedNanos = input.observedElapsedRealtimeNanos
		if (observedNanos == null || observedNanos <= 0L || input.receivedElapsedRealtimeNanos <= 0L) {
			return LocationObservationQualification.Rejected(
				LocationFactRejection.NON_POSITIVE_PROVIDER_TIME,
			)
		}
		if (observedNanos > input.receivedElapsedRealtimeNanos) {
			return LocationObservationQualification.Rejected(LocationFactRejection.FUTURE_PROVIDER_TIME)
		}
		val temporal = expectedAuthority.temporalAuthority
		if (observedNanos < temporal.startInclusiveNanos) {
			return LocationObservationQualification.Stale(LocationStaleReason.PRE_EFFECTIVE)
		}
		if (observedNanos >= temporal.endExclusiveNanos) {
			return LocationObservationQualification.Rejected(LocationFactRejection.AFTER_AUTHORITY)
		}
		val ageNanos = input.receivedElapsedRealtimeNanos - observedNanos
		if (ageNanos > expectedAuthority.acquisitionConfiguration.maximumObservationAgeNanos) {
			return LocationObservationQualification.Stale(LocationStaleReason.TOO_OLD)
		}

		val observedWallTimeMs = input.observedWallTimeMs
		val wallTimeUncertaintyMs = input.wallTimeUncertaintyMs
		if (
			observedWallTimeMs == null || observedWallTimeMs < 0L ||
			wallTimeUncertaintyMs == null || wallTimeUncertaintyMs < 0L
		) {
			return LocationObservationQualification.Rejected(LocationFactRejection.CLOCK_UNVERIFIABLE)
		}
		val retainedFromMs = expectedAuthority.deletion.retainedFromWallTimeMs
		if (retainedFromMs != null && observedWallTimeMs < retainedFromMs) {
			return LocationObservationQualification.Stale(LocationStaleReason.BEFORE_DELETION_FLOOR)
		}

		val deliveryIdentity = input.sourceDeliveryIdentity
			?: return LocationObservationQualification.Rejected(
				LocationFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE,
			)
		val unitIndex = input.deliveryUnitIndex
		val unitCount = input.deliveryUnitCount
		if (unitIndex == null || unitCount == null || unitCount <= 0 || unitIndex !in 0 until unitCount) {
			return LocationObservationQualification.Rejected(
				LocationFactRejection.INVALID_DELIVERY_POSITION,
			)
		}
		payload.rejection(expectedAuthority)?.let { reason ->
			return LocationObservationQualification.Rejected(reason)
		}

		val identity = LocationCapturedFactIdentity(
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = unitIndex,
			logicalTrackingId = expectedAuthority.logicalTrackingId,
			serviceRunId = expectedAuthority.serviceRunId,
			sessionSegmentId = expectedAuthority.sessionSegmentId,
			sessionManifestRevision = expectedAuthority.sessionManifestRevision,
			collectedDataEpoch = expectedAuthority.deletion.collectedDataEpoch,
		)
		val mutation = runCatching {
			LocationCapturedFactMutation(identity, semanticRevision, supersedesSemanticRevision)
		}.getOrElse {
			return LocationObservationQualification.Rejected(
				LocationFactRejection.INVALID_CORRECTION_BASE,
			)
		}
		if (!hasValidCorrectionBase(mutation, expectedAuthority, correctionBase)) {
			return LocationObservationQualification.Rejected(
				LocationFactRejection.INVALID_CORRECTION_BASE,
			)
		}
		val effect = LocationCapturedProductEffect(
			observedElapsedRealtimeNanos = observedNanos,
			receivedElapsedRealtimeNanos = input.receivedElapsedRealtimeNanos,
			observedWallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			deliveryUnitCount = unitCount,
			payload = payload,
		)

		if (semanticRevision == 1L && priorDeliveryFact != null) {
			return if (
				priorDeliveryFact.mutation.identity == identity &&
				priorDeliveryFact.authority == expectedAuthority &&
				priorDeliveryFact.productEffect == effect
			) {
				LocationObservationQualification.Duplicate(priorDeliveryFact)
			} else {
				LocationObservationQualification.Rejected(
					LocationFactRejection.DELIVERY_IDENTITY_COLLISION,
				)
			}
		}
		if (correctionBase?.productEffect == effect) {
			return LocationObservationQualification.Duplicate(correctionBase)
		}

		return LocationObservationQualification.Qualified(
			LocationCapturedFactCommand(mutation, expectedAuthority, effect),
		)
	}

	private fun hasValidCorrectionBase(
		mutation: LocationCapturedFactMutation,
		authority: LocationCaptureAuthority,
		correctionBase: LocationCapturedFactCommand?,
	): Boolean = if (mutation.semanticRevision == 1L) {
		correctionBase == null
	} else {
		correctionBase != null &&
			correctionBase.mutation.identity == mutation.identity &&
			correctionBase.mutation.semanticRevision == mutation.supersedesSemanticRevision &&
			correctionBase.authority == authority
	}

	private fun LocationProviderOutcome.toUnavailable(): LocationObservationQualification =
		LocationObservationQualification.Unavailable(
			when (this) {
				LocationProviderOutcome.NO_FIX -> LocationUnavailableReason.NO_FIX
				LocationProviderOutcome.PROVIDER_UNAVAILABLE ->
					LocationUnavailableReason.PROVIDER_UNAVAILABLE
				LocationProviderOutcome.PERMISSION_UNAVAILABLE ->
					LocationUnavailableReason.PERMISSION_UNAVAILABLE
				LocationProviderOutcome.FAILED -> LocationUnavailableReason.PROVIDER_FAILURE
				LocationProviderOutcome.FIX -> error("A provider fix is not an unavailable outcome")
			},
		)

	private fun LocationFixPayload.rejection(
		authority: LocationCaptureAuthority,
	): LocationFactRejection? = when {
		!latitudeDegrees.isFinite() || latitudeDegrees !in MIN_LATITUDE..MAX_LATITUDE ||
			!longitudeDegrees.isFinite() || longitudeDegrees !in MIN_LONGITUDE..MAX_LONGITUDE ->
			LocationFactRejection.INVALID_COORDINATE
		!horizontalAccuracyMeters.isFinite() || horizontalAccuracyMeters < 0f ->
			LocationFactRejection.INVALID_ACCURACY
		horizontalAccuracyMeters >
			authority.acquisitionConfiguration.maximumHorizontalAccuracyMeters ->
			LocationFactRejection.INSUFFICIENT_ACCURACY
		provider.isBlank() -> LocationFactRejection.INVALID_PROVIDER
		else -> null
	}

	private const val MIN_LATITUDE = -90.0
	private const val MAX_LATITUDE = 90.0
	private const val MIN_LONGITUDE = -180.0
	private const val MAX_LONGITUDE = 180.0
}
