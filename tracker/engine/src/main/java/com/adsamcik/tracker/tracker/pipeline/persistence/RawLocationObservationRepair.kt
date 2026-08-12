package com.adsamcik.tracker.tracker.pipeline.persistence

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationIngressDisposition
import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import dev.tracebox.Tracebox
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backfills canonical provider observations for source events admitted by event-frame builds that
 * omitted [com.adsamcik.tracker.tracker.data.collection.TrackingCycle.locationObservations].
 *
 * Repair runs before pending-signal recovery. That ordering lets a retained curated decision find
 * its immutable raw source and makes the operation idempotent through `source_event_id`.
 */
@Singleton
class RawLocationObservationRepair @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: DefaultSourcePayloadCodec,
) {
	suspend fun repairMissingCanonicalObservations(): Int {
		var afterOrdinal = 0L
		var repaired = 0
		while (true) {
			val page = database.sourceEventWalDao().locationEventsMissingCanonicalObservation(
				sourceKind = SourceKind.LOCATION.stableCode,
				afterOrdinal = afterOrdinal,
				limit = REPAIR_BATCH_SIZE,
			)
			if (page.isEmpty()) break
			afterOrdinal = page.last().admissionOrdinal
			val observations = page.mapNotNull { row -> row.toCanonicalObservationOrNull() }
			if (observations.isEmpty()) continue
			database.withTransaction {
				val stateDao = database.sourceEvidenceStateDao()
				stateDao.ensure()
				check(stateDao.incrementRevision(Time.nowMillis) == 1) {
					"Unable to advance source-evidence revision during raw-location repair"
				}
				val revision = requireNotNull(stateDao.get()).revision
				val inserted = database.locationObservationDao().insert(
					observations.map { observation -> observation.copy(sourceRevision = revision) },
				)
				repaired += inserted.count { rowId -> rowId != -1L }
			}
		}
		if (repaired > 0) {
			Tracebox.log.info("Repaired $repaired canonical raw location observations from source WAL")
		}
		return repaired
	}

	private fun SourceEventWalEntity.toCanonicalObservationOrNull(): LocationObservation? {
		val payload = try {
			payloadCodec.decode(SourceKind.LOCATION, payloadVersion, payload) as LocationFixPayload
		} catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
			Tracebox.log.error(error, "Unable to decode location source event $eventId during raw repair")
			return null
		}
		val hasValidCoordinate = payload.latitudeDegrees.isFinite() && payload.longitudeDegrees.isFinite() &&
			payload.latitudeDegrees in CoordinateConstants.MIN_LATITUDE..CoordinateConstants.MAX_LATITUDE &&
			payload.longitudeDegrees in CoordinateConstants.MIN_LONGITUDE..CoordinateConstants.MAX_LONGITUDE
		val receivedAt = acquiredAtMs
		return LocationObservation(
			fixTimeMs = wallTimeMs ?: acquiredAtMs,
			fixElapsedRealtimeNanos = observedElapsedNanos,
			receivedAtMs = receivedAt,
			receivedElapsedRealtimeNanos = receivedElapsedNanos,
			deliveryAgeMs = deliveryAgeMs(observedElapsedNanos, receivedElapsedNanos),
			latE7 = payload.latitudeDegrees.takeIf { hasValidCoordinate }?.let { LatE7.fromDegrees(it).raw },
			lonE7 = payload.longitudeDegrees.takeIf { hasValidCoordinate }?.let { LonE7.fromDegrees(it).raw },
			rawAltitudeM = payload.altitudeMeters?.toFloat(),
			hAccM = payload.horizontalAccuracyMeters,
			vAccM = payload.verticalAccuracyMeters,
			speedMps = payload.speedMetersPerSecond,
			speedAccuracyMps = null,
			bearingDeg = payload.bearingDegrees,
			bearingAccuracyDeg = null,
			provider = payload.provider,
			acquisitionMode = LocationAcquisitionMode.UNKNOWN.name,
			requestPriority = LocationRequestPriority.UNKNOWN.name,
			permissionPrecision = LocationPermissionPrecision.UNKNOWN.name,
			batchIndex = 0,
			batchSize = 1,
			isMock = false,
			ingressDisposition = LocationIngressDisposition.DELIVERED_VALID.name,
			estimatorVersion = PersistenceProcessor.CURRENT_ESTIMATOR_VERSION,
			calibrationVersion = PersistenceProcessor.CURRENT_CALIBRATION_VERSION,
			createdAt = wallTimeMs ?: acquiredAtMs,
			sourceSignalId = "location-observation:$eventId",
			sourceEventId = eventId,
			callbackId = providerDedupKey,
			clockDomainId = clockDomainId,
			bootClockDomainId = null,
		)
	}

	private fun deliveryAgeMs(fixElapsedNanos: Long, receivedElapsedNanos: Long): Long? {
		if (fixElapsedNanos <= 0L || receivedElapsedNanos <= 0L) return null
		return (receivedElapsedNanos - fixElapsedNanos).coerceAtLeast(0L) /
			Time.MILLISECONDS_IN_NANOSECONDS
	}

	private companion object {
		const val REPAIR_BATCH_SIZE = 256
	}
}
