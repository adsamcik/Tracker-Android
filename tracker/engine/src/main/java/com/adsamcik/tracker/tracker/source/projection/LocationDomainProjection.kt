package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionObservationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionPointEntity
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Replayable location-domain projection for route, speed, altitude and policy evidence.
 *
 * State is ordered by provider observation time, not WAL admission order. A late fix recomputes
 * affected point revisions and emits append-only corrections with stable point identities. The
 * normal ordered path reconstructs its accumulator from indexed latest rows and advances once.
 */
class LocationDomainProjection @Inject constructor(
	private val database: AppDatabase,
) : Projection {
	override val id: String = ID
	override val version: Int = VERSION

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		val payload = event.evidence.payload as? LocationFixPayload ?: return
		val logicalTrackingId = event.evidence.logicalTrackingId?.value ?: return
		val dao = database.locationProjectionDao()
		val observationEntity = LocationProjectionObservationEntity(
			eventId = event.eventId.value,
			logicalTrackingId = logicalTrackingId,
			admissionOrdinal = event.admissionOrdinal,
			elapsedRealtimeNanos = event.evidence.observedElapsedRealtimeNanos,
			wallTimeMs = event.evidence.wallTimeMs ?: event.evidence.acquiredAtMs,
			latitudeDegrees = payload.latitudeDegrees,
			longitudeDegrees = payload.longitudeDegrees,
			horizontalAccuracyMeters = payload.horizontalAccuracyMeters,
			altitudeMeters = payload.altitudeMeters,
			verticalAccuracyMeters = payload.verticalAccuracyMeters,
			speedMetersPerSecond = payload.speedMetersPerSecond,
		)
		val observation = observationEntity.toObservation()
		val previousLatest = dao.latestObservation(logicalTrackingId)?.toObservation()
		val incrementalState = when {
			previousLatest == null -> LocationAccumulatorState()
			compareLocationOrder(previousLatest, observation) >= 0 -> null
			dao.point(observation.eventId) != null -> null
			else -> loadAccumulatorState(logicalTrackingId, previousLatest)
		}
		dao.upsertObservation(observationEntity)
		if (incrementalState != null) {
			val derived = advanceLocationTrack(incrementalState, observation).point.copy(revision = 1)
			recordEffects(context, logicalTrackingId, listOf(derived))
			dao.upsertPoints(listOf(derived.toEntity(logicalTrackingId)))
			return
		}

		val recalculated = deriveLocationTrack(
			dao.observations(logicalTrackingId).map(LocationProjectionObservationEntity::toObservation),
		)
		val previous = dao.points(logicalTrackingId)
			.map(LocationProjectionPointEntity::toDerivedPoint)
			.associateBy(LocationDerivedPoint::eventId)
		val next = recalculated.map { point ->
			val old = previous[point.eventId]
			if (old?.semanticEquals(point) == true) old else point.copy(revision = (old?.revision ?: 0) + 1)
		}
		val changed = next.filter { point -> previous[point.eventId]?.semanticEquals(point) != true }
		recordEffects(context, logicalTrackingId, changed)
		if (changed.isNotEmpty()) {
			dao.upsertPoints(changed.map { it.toEntity(logicalTrackingId) })
		}
	}

	private suspend fun loadAccumulatorState(
		logicalTrackingId: String,
		latestObservation: LocationObservation,
	): LocationAccumulatorState? {
		val dao = database.locationProjectionDao()
		val latestPoint = dao.point(latestObservation.eventId) ?: return null
		val accepted = dao.latestAcceptedObservation(logicalTrackingId)?.toObservation()
		if (accepted == null && latestPoint.rejection != LocationRejection.LOW_ACCURACY.name) return null
		val acceptedPoint = accepted?.let { dao.point(it.eventId) ?: return null }
		val pending = dao.latestUnconfirmedTeleportObservation(logicalTrackingId)
			?.toObservation()
			?.takeIf { candidate ->
				accepted != null && compareLocationOrder(accepted, candidate) < 0
			}
		return LocationAccumulatorState(
			accepted = accepted,
			pending = pending,
			cumulativeDistanceMeters = latestPoint.cumulativeDistanceMeters,
			smoothedSpeedMetersPerSecond = acceptedPoint?.estimatedSpeedMetersPerSecond ?: 0.0,
		)
	}

	private suspend fun recordEffects(
		context: ProjectionContext,
		logicalTrackingId: String,
		changed: List<LocationDerivedPoint>,
	) {
		changed.forEach { point ->
			LOCATION_EFFECT_KINDS.forEach { kind ->
				context.recordOutbox(
					ProjectionOutboxEffect(
						stableId = "$ID:$kind:${point.eventId}:r${point.revision}",
						kind = kind,
						payloadVersion = LocationProjectionCodec.VERSION,
						payload = LocationProjectionCodec.encodeEffect(logicalTrackingId, point),
						requiresDelivery = false,
					),
				)
			}
		}
	}

	companion object {
		const val ID = "location-domain"
		const val VERSION = 1
		const val ROUTE_EFFECT_KIND = "location-route-point-v1"
		const val SPEED_EFFECT_KIND = "location-speed-v1"
		const val ALTITUDE_EFFECT_KIND = "location-altitude-v1"
		const val POLICY_EFFECT_KIND = "location-policy-evidence-v1"
		internal val LOCATION_EFFECT_KINDS = listOf(
			ROUTE_EFFECT_KIND,
			SPEED_EFFECT_KIND,
			ALTITUDE_EFFECT_KIND,
			POLICY_EFFECT_KIND,
		)
	}
}

internal data class LocationObservation(
	val eventId: String,
	val admissionOrdinal: Long,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
	val payload: LocationFixPayload,
)

internal data class LocationDerivedPoint(
	val eventId: String,
	val revision: Int = 0,
	val accepted: Boolean,
	val rejection: LocationRejection?,
	val latitudeDegrees: Double,
	val longitudeDegrees: Double,
	val segmentDistanceMeters: Double,
	val cumulativeDistanceMeters: Double,
	val estimatedSpeedMetersPerSecond: Double?,
	val rawWgs84AltitudeMeters: Double?,
	val verticalAccuracyMeters: Float?,
	val elapsedRealtimeNanos: Long,
)

internal enum class LocationRejection { LOW_ACCURACY, NON_MONOTONIC_OR_DUPLICATE, TELEPORT_UNCONFIRMED }

internal data class LocationAccumulatorState(
	val accepted: LocationObservation? = null,
	val pending: LocationObservation? = null,
	val cumulativeDistanceMeters: Double = 0.0,
	val smoothedSpeedMetersPerSecond: Double = 0.0,
)

internal data class LocationDerivation(
	val point: LocationDerivedPoint,
	val state: LocationAccumulatorState,
)

private fun LocationProjectionObservationEntity.toObservation() = LocationObservation(
	eventId = eventId,
	admissionOrdinal = admissionOrdinal,
	elapsedRealtimeNanos = elapsedRealtimeNanos,
	wallTimeMs = wallTimeMs,
	payload = LocationFixPayload(
		latitudeDegrees = latitudeDegrees,
		longitudeDegrees = longitudeDegrees,
		horizontalAccuracyMeters = horizontalAccuracyMeters,
		altitudeMeters = altitudeMeters,
		verticalAccuracyMeters = verticalAccuracyMeters,
		speedMetersPerSecond = speedMetersPerSecond,
		bearingDegrees = null,
		provider = "normalized-projection",
	),
)

private fun LocationProjectionPointEntity.toDerivedPoint() = LocationDerivedPoint(
	eventId = eventId,
	revision = revision,
	accepted = accepted,
	rejection = rejection?.let(LocationRejection::valueOf),
	latitudeDegrees = latitudeDegrees,
	longitudeDegrees = longitudeDegrees,
	segmentDistanceMeters = segmentDistanceMeters,
	cumulativeDistanceMeters = cumulativeDistanceMeters,
	estimatedSpeedMetersPerSecond = estimatedSpeedMetersPerSecond,
	rawWgs84AltitudeMeters = rawWgs84AltitudeMeters,
	verticalAccuracyMeters = verticalAccuracyMeters,
	elapsedRealtimeNanos = elapsedRealtimeNanos,
)

private fun LocationDerivedPoint.toEntity(logicalTrackingId: String) = LocationProjectionPointEntity(
	eventId = eventId,
	logicalTrackingId = logicalTrackingId,
	revision = revision,
	accepted = accepted,
	rejection = rejection?.name,
	latitudeDegrees = latitudeDegrees,
	longitudeDegrees = longitudeDegrees,
	segmentDistanceMeters = segmentDistanceMeters,
	cumulativeDistanceMeters = cumulativeDistanceMeters,
	estimatedSpeedMetersPerSecond = estimatedSpeedMetersPerSecond,
	rawWgs84AltitudeMeters = rawWgs84AltitudeMeters,
	verticalAccuracyMeters = verticalAccuracyMeters,
	elapsedRealtimeNanos = elapsedRealtimeNanos,
)

internal fun deriveLocationTrack(input: List<LocationObservation>): List<LocationDerivedPoint> {
	val observations = input.sortedWith(
		LOCATION_OBSERVATION_ORDER,
	)
	var state = LocationAccumulatorState()
	return observations.map { observation ->
		advanceLocationTrack(state, observation).also { state = it.state }.point
	}
}

internal fun advanceLocationTrack(
	state: LocationAccumulatorState,
	observation: LocationObservation,
): LocationDerivation {
	val cumulative = state.cumulativeDistanceMeters
	val payload = observation.payload
	if (!payload.horizontalAccuracyMeters.isFinite() || payload.horizontalAccuracyMeters > MAX_ACCURACY_METERS) {
		return LocationDerivation(observation.rejected(LocationRejection.LOW_ACCURACY, cumulative), state)
	}
	val anchor = state.accepted
	if (anchor != null && !isStrictlyNewer(anchor, observation)) {
		return LocationDerivation(
			observation.rejected(LocationRejection.NON_MONOTONIC_OR_DUPLICATE, cumulative),
			state,
		)
	}
	if (anchor != null && isTeleport(anchor, observation)) {
		val candidate = state.pending
		return if (candidate != null && confirmsReacquisition(candidate, observation)) {
			val speed = payload.speedMetersPerSecond?.toDouble()
			LocationDerivation(
				observation.accepted(0.0, cumulative, speed),
				state.copy(
					accepted = observation,
					pending = null,
					smoothedSpeedMetersPerSecond = speed ?: 0.0,
				),
			)
		} else {
			LocationDerivation(
				observation.rejected(LocationRejection.TELEPORT_UNCONFIRMED, cumulative),
				state.copy(pending = observation),
			)
		}
	}

	val distance = anchor?.let { distanceMeters(it, observation) } ?: 0.0
	val nextCumulative = cumulative + distance
	val speed = if (anchor == null) {
		payload.speedMetersPerSecond?.toDouble()
	} else {
		val seconds = elapsedSeconds(anchor, observation)
		val calculated = seconds?.takeIf { it > 0.0 }?.let { distance / it }
		val recorded = payload.speedMetersPerSecond?.toDouble()?.takeIf { it > 0.0 }
		val raw = when {
			calculated == null -> recorded
			recorded == null -> calculated
			abs(calculated - recorded) / recorded >= MAX_ALLOWED_SPEED_DIFFERENCE -> calculated
			else -> recorded
		}
		raw?.let { value ->
			if (state.smoothedSpeedMetersPerSecond <= 0.0) value else SPEED_SMOOTHING_ALPHA * value +
				(1.0 - SPEED_SMOOTHING_ALPHA) * state.smoothedSpeedMetersPerSecond
		}
	}
	return LocationDerivation(
		observation.accepted(distance, nextCumulative, speed),
		LocationAccumulatorState(
			accepted = observation,
			pending = null,
			cumulativeDistanceMeters = nextCumulative,
			smoothedSpeedMetersPerSecond = speed ?: 0.0,
		),
	)
}

private val LOCATION_OBSERVATION_ORDER = compareBy<LocationObservation>(LocationObservation::elapsedRealtimeNanos)
	.thenBy(LocationObservation::wallTimeMs)
	.thenBy(LocationObservation::eventId)

private fun compareLocationOrder(first: LocationObservation, second: LocationObservation): Int =
	LOCATION_OBSERVATION_ORDER.compare(first, second)

private fun LocationObservation.accepted(distance: Double, cumulative: Double, speed: Double?) = LocationDerivedPoint(
	eventId, accepted = true, rejection = null,
	latitudeDegrees = payload.latitudeDegrees,
	longitudeDegrees = payload.longitudeDegrees,
	segmentDistanceMeters = distance,
	cumulativeDistanceMeters = cumulative,
	estimatedSpeedMetersPerSecond = speed,
	rawWgs84AltitudeMeters = payload.altitudeMeters,
	verticalAccuracyMeters = payload.verticalAccuracyMeters,
	elapsedRealtimeNanos = elapsedRealtimeNanos,
)

private fun LocationObservation.rejected(reason: LocationRejection, cumulative: Double) = LocationDerivedPoint(
	eventId, accepted = false, rejection = reason,
	latitudeDegrees = payload.latitudeDegrees,
	longitudeDegrees = payload.longitudeDegrees,
	segmentDistanceMeters = 0.0,
	cumulativeDistanceMeters = cumulative,
	estimatedSpeedMetersPerSecond = null,
	rawWgs84AltitudeMeters = payload.altitudeMeters,
	verticalAccuracyMeters = payload.verticalAccuracyMeters,
	elapsedRealtimeNanos = elapsedRealtimeNanos,
)

private fun LocationDerivedPoint.semanticEquals(other: LocationDerivedPoint): Boolean =
	copy(revision = 0) == other.copy(revision = 0)

private fun isStrictlyNewer(first: LocationObservation, second: LocationObservation): Boolean =
	if (first.elapsedRealtimeNanos > 0L && second.elapsedRealtimeNanos > 0L) {
		second.elapsedRealtimeNanos > first.elapsedRealtimeNanos
	} else second.wallTimeMs > first.wallTimeMs

private fun isTeleport(first: LocationObservation, second: LocationObservation): Boolean {
	val distance = distanceMeters(first, second)
	if (distance > MAX_JUMP_DISTANCE_METERS) return true
	val seconds = elapsedSeconds(first, second) ?: return distance > UNKNOWN_TIME_JUMP_DISTANCE_METERS
	return seconds <= 0.0 || distance / seconds > MAX_ABSOLUTE_SPEED_METERS_PER_SECOND
}

private fun confirmsReacquisition(first: LocationObservation, second: LocationObservation): Boolean {
	val seconds = elapsedSeconds(first, second) ?: return false
	return seconds <= MAX_REACQUISITION_GAP_SECONDS &&
		distanceMeters(first, second) <= MAX_REACQUISITION_DISTANCE_METERS &&
		!isTeleport(first, second)
}

private fun elapsedSeconds(first: LocationObservation, second: LocationObservation): Double? {
	val elapsed = second.elapsedRealtimeNanos - first.elapsedRealtimeNanos
	if (first.elapsedRealtimeNanos > 0L && second.elapsedRealtimeNanos > 0L && elapsed > 0L) {
		return elapsed / 1_000_000_000.0
	}
	val wall = second.wallTimeMs - first.wallTimeMs
	return wall.takeIf { it > 0L }?.div(1_000.0)
}

private fun distanceMeters(first: LocationObservation, second: LocationObservation): Double {
	val lat1 = Math.toRadians(first.payload.latitudeDegrees)
	val lat2 = Math.toRadians(second.payload.latitudeDegrees)
	val deltaLat = lat2 - lat1
	val deltaLon = Math.toRadians(second.payload.longitudeDegrees - first.payload.longitudeDegrees)
	val a = sin(deltaLat / 2).let { it * it } + cos(lat1) * cos(lat2) *
		sin(deltaLon / 2).let { it * it }
	return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
}

internal object LocationProjectionCodec {
	const val VERSION = 1

	fun encodeEffect(logicalTrackingId: String, point: LocationDerivedPoint): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(VERSION)
				output.writeUTF(logicalTrackingId)
				output.writePoint(point)
			}
			bytes.toByteArray()
		}

	private fun DataOutputStream.writePoint(value: LocationDerivedPoint) {
		writeUTF(value.eventId); writeInt(value.revision); writeBoolean(value.accepted)
		writeInt(value.rejection?.ordinal ?: -1); writeDouble(value.latitudeDegrees); writeDouble(value.longitudeDegrees)
		writeDouble(value.segmentDistanceMeters); writeDouble(value.cumulativeDistanceMeters)
		writeNullableDouble(value.estimatedSpeedMetersPerSecond); writeNullableDouble(value.rawWgs84AltitudeMeters)
		writeNullableFloat(value.verticalAccuracyMeters); writeLong(value.elapsedRealtimeNanos)
	}

	private fun DataOutputStream.writeNullableDouble(value: Double?) { writeBoolean(value != null); if (value != null) writeDouble(value) }
	private fun DataOutputStream.writeNullableFloat(value: Float?) { writeBoolean(value != null); if (value != null) writeFloat(value) }
}

private const val MAX_ACCURACY_METERS = 50f
private const val MAX_ALLOWED_SPEED_DIFFERENCE = 0.2
private const val SPEED_SMOOTHING_ALPHA = 0.4
private const val MAX_ABSOLUTE_SPEED_METERS_PER_SECOND = 500.0 / 3.6
private const val MAX_JUMP_DISTANCE_METERS = 10_000.0
private const val UNKNOWN_TIME_JUMP_DISTANCE_METERS = 1_000.0
private const val MAX_REACQUISITION_DISTANCE_METERS = 1_000.0
private const val MAX_REACQUISITION_GAP_SECONDS = 120.0
private const val EARTH_RADIUS_METERS = 6_371_000.0
