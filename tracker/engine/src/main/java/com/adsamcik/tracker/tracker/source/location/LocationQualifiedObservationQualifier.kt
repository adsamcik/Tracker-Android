package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag

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

/** Source-boundary input. A fix carries only immutable evidence recovered from the WAL. */
internal data class LocationObservationInput(
	val origin: LocationObservationOrigin,
	val outcome: LocationProviderOutcome,
	val attemptedAuthority: LocationCaptureAuthority,
	val durableEvidence: LocationDurableObservationEvidence?,
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
	DELETION_AUTHORITY_MISMATCH,
	INCOMPLETE_DURABLE_EVIDENCE,
	WAL_INTEGRITY_UNVERIFIABLE,
	UNSUPPORTED_PAYLOAD_VERSION,
	NON_POSITIVE_PROVIDER_TIME,
	FUTURE_PROVIDER_TIME,
	AFTER_AUTHORITY,
	CLOCK_UNVERIFIABLE,
	DELIVERY_IDENTITY_UNVERIFIABLE,
	INVALID_DELIVERY_POSITION,
	INVALID_COORDINATE,
	INVALID_ACCURACY,
	INSUFFICIENT_ACCURACY,
	INVALID_ALTITUDE,
	INVALID_VERTICAL_ACCURACY,
	INVALID_SPEED,
	INVALID_BEARING,
	INVALID_PROVIDER,
	CACHED_EVIDENCE,
	QUALITY_AUTHORITY_MISMATCH,
	INVALID_QUALIFIER_VERSION,
	INVALID_CORRECTION_BASE,
	RAW_EVIDENCE_CHANGED,
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
		currentDeletionAuthority: LocationDeletionAuthority,
		qualifierVersion: Int = 1,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		priorDeliveryFact: LocationCapturedFactCommand? = null,
		correctionBase: LocationCapturedFactCommand? = null,
	): LocationObservationQualification {
		if (input.origin != LocationObservationOrigin.PROVIDER_CALLBACK) {
			return rejected(LocationFactRejection.NOT_PROVIDER_OBSERVATION)
		}
		if (input.attemptedAuthority != expectedAuthority) {
			return rejected(LocationFactRejection.AUTHORITY_MISMATCH)
		}
		if (input.outcome != LocationProviderOutcome.FIX) {
			if (input.durableEvidence != null) {
				return rejected(LocationFactRejection.INCONSISTENT_PROVIDER_RESULT)
			}
			return input.outcome.toUnavailable()
		}
		val evidence = input.durableEvidence
			?: return rejected(LocationFactRejection.INCONSISTENT_PROVIDER_RESULT)
		if (evidence.capturedAuthority != expectedAuthority) {
			return rejected(LocationFactRejection.AUTHORITY_MISMATCH)
		}
		if (currentDeletionAuthority.currentCollectedDataEpoch !=
			expectedAuthority.capturedCollectedDataEpoch
		) {
			return rejected(LocationFactRejection.DELETION_AUTHORITY_MISMATCH)
		}
		if (qualifierVersion <= 0) {
			return rejected(LocationFactRejection.INVALID_QUALIFIER_VERSION)
		}
		validateDurableEvidence(evidence, expectedAuthority, currentDeletionAuthority)?.let {
			return it
		}
		val deliveryIdentity = evidence.sourceDeliveryIdentity
			?: return rejected(LocationFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)

		val identity = LocationCapturedFactIdentity(
			sourceEventId = evidence.sourceEventId,
			sourceAdmissionOrdinal = evidence.sourceAdmissionOrdinal,
			walIntegrityIdentity = evidence.walIntegrityIdentity,
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = evidence.deliveryUnitIndex,
			logicalTrackingId = expectedAuthority.logicalTrackingId,
			serviceRunId = expectedAuthority.serviceRunId,
			sessionSegmentId = expectedAuthority.sessionSegmentId,
			sessionManifestRevision = expectedAuthority.sessionManifestRevision,
			capturedCollectedDataEpoch = expectedAuthority.capturedCollectedDataEpoch,
		)
		val mutation = runCatching {
			LocationCapturedFactMutation(identity, semanticRevision, supersedesSemanticRevision)
		}.getOrElse {
			return rejected(LocationFactRejection.INVALID_CORRECTION_BASE)
		}
		if (!hasValidCorrectionBase(mutation, expectedAuthority, correctionBase)) {
			return rejected(LocationFactRejection.INVALID_CORRECTION_BASE)
		}

		val clock = evidence.clockAuthority
		val uncertaintyInterval = uncertaintyInterval(clock)
			?: return rejected(LocationFactRejection.CLOCK_UNVERIFIABLE)
		val effect = LocationCapturedProductEffect(
			durableEvidence = evidence,
			derivedQualification = LocationDerivedQualification(
				qualifierVersion = qualifierVersion,
				deliveryAgeNanos = clock.receivedElapsedRealtimeNanos -
					clock.observedElapsedRealtimeNanos,
				maximumObservationAgeNanos =
					expectedAuthority.acquisitionConfiguration.maximumObservationAgeNanos,
				maximumHorizontalAccuracyMeters =
					expectedAuthority.acquisitionConfiguration.maximumHorizontalAccuracyMeters,
				earliestPossibleWallTimeMs = uncertaintyInterval.first,
				latestPossibleWallTimeMs = uncertaintyInterval.last,
			),
		)

		if (semanticRevision == 1L && priorDeliveryFact != null) {
			return if (
				priorDeliveryFact.mutation.identity == identity &&
				priorDeliveryFact.authority == expectedAuthority &&
				priorDeliveryFact.productEffect == effect
			) {
				LocationObservationQualification.Duplicate(priorDeliveryFact)
			} else {
				rejected(LocationFactRejection.DELIVERY_IDENTITY_COLLISION)
			}
		}
		if (correctionBase != null && correctionBase.productEffect.durableEvidence != evidence) {
			return rejected(LocationFactRejection.RAW_EVIDENCE_CHANGED)
		}
		if (correctionBase?.productEffect == effect) {
			return LocationObservationQualification.Duplicate(correctionBase)
		}

		return LocationObservationQualification.Qualified(
			LocationCapturedFactCommand(mutation, expectedAuthority, effect),
		)
	}

	private fun validateDurableEvidence(
		evidence: LocationDurableObservationEvidence,
		authority: LocationCaptureAuthority,
		deletionAuthority: LocationDeletionAuthority,
	): LocationObservationQualification? {
		if (evidence.sourceAdmissionOrdinal <= 0L) {
			return rejected(LocationFactRejection.INCOMPLETE_DURABLE_EVIDENCE)
		}
		if (!LOWERCASE_SHA_256.matches(evidence.walIntegrityIdentity)) {
			return rejected(LocationFactRejection.WAL_INTEGRITY_UNVERIFIABLE)
		}
		if (evidence.payloadVersion != LOCATION_PAYLOAD_VERSION) {
			return rejected(LocationFactRejection.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (evidence.deliveryUnitCount <= 0 ||
			evidence.deliveryUnitIndex !in 0 until evidence.deliveryUnitCount
		) {
			return rejected(LocationFactRejection.INVALID_DELIVERY_POSITION)
		}
		if (evidence.sourceDeliveryIdentity == null) {
			return rejected(LocationFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)
		}
		val clock = evidence.clockAuthority
		if (clock.clockDomainId != authority.clockDomainId) {
			return rejected(LocationFactRejection.CLOCK_UNVERIFIABLE)
		}
		if (clock.observedElapsedRealtimeNanos <= 0L || clock.receivedElapsedRealtimeNanos <= 0L) {
			return rejected(LocationFactRejection.NON_POSITIVE_PROVIDER_TIME)
		}
		if (clock.observedElapsedRealtimeNanos > clock.receivedElapsedRealtimeNanos) {
			return rejected(LocationFactRejection.FUTURE_PROVIDER_TIME)
		}
		val temporal = authority.temporalAuthority
		if (clock.observedElapsedRealtimeNanos < temporal.startInclusiveNanos) {
			return LocationObservationQualification.Stale(LocationStaleReason.PRE_EFFECTIVE)
		}
		if (clock.observedElapsedRealtimeNanos >= temporal.endExclusiveNanos) {
			return rejected(LocationFactRejection.AFTER_AUTHORITY)
		}
		val ageNanos = clock.receivedElapsedRealtimeNanos - clock.observedElapsedRealtimeNanos
		if (ageNanos > authority.acquisitionConfiguration.maximumObservationAgeNanos) {
			return LocationObservationQualification.Stale(LocationStaleReason.TOO_OLD)
		}
		val wallInterval = uncertaintyInterval(clock)
			?: return rejected(LocationFactRejection.CLOCK_UNVERIFIABLE)
		val retainedFromMs = deletionAuthority.retainedFromWallTimeMs
		if (retainedFromMs != null && wallInterval.first < retainedFromMs) {
			return LocationObservationQualification.Stale(LocationStaleReason.BEFORE_DELETION_FLOOR)
		}
		if (SourceQualityFlag.CACHED in evidence.quality.flags) {
			return rejected(LocationFactRejection.CACHED_EVIDENCE)
		}
		val approximate = SourceQualityFlag.APPROXIMATE in evidence.quality.flags
		if (approximate != (authority.permissionPrecision == LocationPermissionPrecision.APPROXIMATE)) {
			return rejected(LocationFactRejection.QUALITY_AUTHORITY_MISMATCH)
		}
		evidence.payload.rejection(authority)?.let { reason -> return rejected(reason) }
		return null
	}

	private fun uncertaintyInterval(clock: LocationDurableClockAuthority): LongRange? {
		if (clock.observedWallTimeMs < 0L || clock.wallTimeUncertaintyMs < 0L) return null
		return runCatching {
			val start = Math.subtractExact(clock.observedWallTimeMs, clock.wallTimeUncertaintyMs)
			val end = Math.addExact(clock.observedWallTimeMs, clock.wallTimeUncertaintyMs)
			if (start < 0L) null else start..end
		}.getOrNull()
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
		altitudeMeters?.isFinite() == false -> LocationFactRejection.INVALID_ALTITUDE
		verticalAccuracyMeters?.let { !it.isFinite() || it < 0f } == true ->
			LocationFactRejection.INVALID_VERTICAL_ACCURACY
		speedMetersPerSecond?.let { !it.isFinite() || it < 0f } == true ->
			LocationFactRejection.INVALID_SPEED
		bearingDegrees?.let { !it.isFinite() || it < 0f || it >= FULL_CIRCLE_DEGREES } == true ->
			LocationFactRejection.INVALID_BEARING
		provider.isBlank() -> LocationFactRejection.INVALID_PROVIDER
		else -> null
	}

	private fun rejected(reason: LocationFactRejection) =
		LocationObservationQualification.Rejected(reason)

	private const val LOCATION_PAYLOAD_VERSION = 1
	private const val MIN_LATITUDE = -90.0
	private const val MAX_LATITUDE = 90.0
	private const val MIN_LONGITUDE = -180.0
	private const val MAX_LONGITUDE = 180.0
	private const val FULL_CIRCLE_DEGREES = 360f
	private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
}
