package com.adsamcik.tracker.tracker.pipeline

import androidx.core.location.LocationCompat
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants
import com.adsamcik.tracker.shared.base.data.LocationProviderObservation
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.LocationObservationSignal
import com.adsamcik.tracker.stats.api.signal.PolicySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.tracker.source.location.LocationCapturedFactCommand
import com.adsamcik.tracker.tracker.source.location.LocationWalAcquisitionMetadata
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalSignalIdentity

/** Maps a trigger fix to the raw-observation-only signal consumed by persistence. */
internal fun LocationProviderObservation.toLocationObservationSignal(
	policyTier: PolicyTier,
	policyName: String?,
): TrackingSignal {
	val rawLocation = location
	val fixMetadata = metadata
	val hasValidCoordinate = rawLocation.latitude.isFinite() && rawLocation.longitude.isFinite() &&
		rawLocation.latitude in CoordinateConstants.MIN_LATITUDE..CoordinateConstants.MAX_LATITUDE &&
		rawLocation.longitude in CoordinateConstants.MIN_LONGITUDE..CoordinateConstants.MAX_LONGITUDE
	val observation = LocationObservationSignal(
		rawFixTimeMs = rawLocation.time,
		coordinate = if (hasValidCoordinate) {
			CoordinateE7(
				lat = LatE7.fromDegrees(rawLocation.latitude),
				lon = LonE7.fromDegrees(rawLocation.longitude),
			)
		} else {
			null
		},
		horizontalAccuracyM = rawLocation.accuracy.takeIf { rawLocation.hasAccuracy() },
		altitudeM = rawLocation.altitude.toFloat().takeIf { rawLocation.hasAltitude() },
		verticalAccuracyM = rawLocation.verticalAccuracyMeters.takeIf {
			rawLocation.hasVerticalAccuracy()
		},
		speedMps = rawLocation.speed.takeIf { rawLocation.hasSpeed() },
		speedAccuracyMps = rawLocation.speedAccuracyMetersPerSecond.takeIf {
			rawLocation.hasSpeedAccuracy()
		},
		bearingDeg = rawLocation.bearing.takeIf { rawLocation.hasBearing() },
		bearingAccuracyDeg = rawLocation.bearingAccuracyDegrees.takeIf {
			rawLocation.hasBearingAccuracy()
		},
		provider = rawLocation.provider ?: "unknown",
		receivedAtMs = fixMetadata.receivedAtMs,
		receivedElapsedRealtimeNanos = fixMetadata.receivedElapsedRealtimeNanos,
		acquisitionMode = fixMetadata.acquisitionMode.name,
		requestPriority = fixMetadata.requestPriority.name,
		permissionPrecision = fixMetadata.permissionPrecision.name,
		batchIndex = fixMetadata.batchIndex,
		batchSize = fixMetadata.batchSize,
		isMock = LocationCompat.isMock(rawLocation),
		ingressDisposition = ingressDisposition.name,
		callbackId = fixMetadata.callbackId,
		sourceEventId = fixMetadata.sourceEventId,
	)
	return TrackingSignal(
		// EpochMs deliberately rejects negative values. Preserve the provider value above and use
		// this envelope-safe fallback only for dispatch ordering; the ingress disposition keeps an
		// invalid provider timestamp out of canonical evidence.
		timestampMs = EpochMs(rawLocation.time.coerceAtLeast(0L)),
		elapsedRealtimeNanos = rawLocation.elapsedRealtimeNanos,
		clockDomainId = fixMetadata.clockDomainId,
		bootClockDomainId = fixMetadata.bootClockDomainId,
		locationObservation = observation,
		policy = PolicySignal(policyTier, policyName),
		persistenceSignalId = fixMetadata.sourceEventId
			?.takeIf(String::isNotBlank)
			?.let { sourceEventId -> "location-observation:$sourceEventId" },
	)
}

/** Reconstitutes exact qualified WAL evidence without accepting caller-supplied provider fields. */
internal fun LocationCapturedFactCommand.toProtectedLocationObservationSignal(
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	policyTier: PolicyTier,
	policyName: String?,
): TrackingSignal {
	require(acquisitionMetadata.isQualified)
	val evidence = productEffect.durableEvidence
	val clock = evidence.clockAuthority
	val receivedWallTimeMs = requireNotNull(clock.receivedWallTimeMs) {
		"Protected Location evidence requires callback receipt wall time"
	}
	val payload = evidence.payload
	val eventId = evidence.sourceEventId.value
	return TrackingSignal(
		timestampMs = EpochMs(clock.observedWallTimeMs),
		elapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		clockDomainId = clock.clockDomainId,
		bootClockDomainId = clock.clockDomainId,
		locationObservation = LocationObservationSignal(
			rawFixTimeMs = clock.observedWallTimeMs,
			coordinate = CoordinateE7(
				lat = LatE7.fromDegrees(payload.latitudeDegrees),
				lon = LonE7.fromDegrees(payload.longitudeDegrees),
			),
			horizontalAccuracyM = payload.horizontalAccuracyMeters,
			altitudeM = payload.altitudeMeters?.toFloat(),
			verticalAccuracyM = payload.verticalAccuracyMeters,
			speedMps = payload.speedMetersPerSecond,
			speedAccuracyMps = null,
			bearingDeg = payload.bearingDegrees,
			bearingAccuracyDeg = null,
			provider = payload.provider,
			receivedAtMs = receivedWallTimeMs,
			receivedElapsedRealtimeNanos = clock.receivedElapsedRealtimeNanos,
			acquisitionMode = acquisitionMetadata.acquisitionMode.name,
			requestPriority = acquisitionMetadata.requestPriority.name,
			permissionPrecision = authority.permissionPrecision.name,
			batchIndex = evidence.deliveryUnitIndex,
			batchSize = evidence.deliveryUnitCount,
			isMock = evidence.isMock,
			ingressDisposition = "DELIVERED_VALID",
			callbackId = evidence.sourceDeliveryIdentity?.value,
			sourceEventId = eventId,
		),
		policy = PolicySignal(policyTier, policyName),
		persistenceSignalId = ProtectedLocationCanonicalSignalIdentity.rawObservation(eventId),
	)
}
