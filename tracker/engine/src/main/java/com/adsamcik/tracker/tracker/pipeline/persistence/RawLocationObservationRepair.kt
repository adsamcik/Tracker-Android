package com.adsamcik.tracker.tracker.pipeline.persistence

import androidx.room.withTransaction
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
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
import dev.tracebox.api.public
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
			repaired += repairPage(page)
		}
		logRepairResult(repaired)
		return repaired
	}

	private suspend fun repairPage(page: List<SourceEventWalEntity>): Int {
		val candidates = page.mapNotNull { row -> row.toRepairCandidateOrNull() }
		if (candidates.isEmpty()) return 0
		return database.withTransaction { insertEligibleCandidates(candidates) }
	}

	private fun SourceEventWalEntity.toRepairCandidateOrNull(): RawLocationRepairCandidate? {
		val observation = toCanonicalObservationOrNull() ?: return null
		return RawLocationRepairCandidate(
			capturedCollectedDataEpoch = capturedCollectedDataEpoch,
			acquiredAtMs = acquiredAtMs,
			observation = observation,
		)
	}

	private suspend fun insertEligibleCandidates(candidates: List<RawLocationRepairCandidate>): Int {
		val stateDao = database.sourceEvidenceStateDao()
		stateDao.ensure()
		val state = requireNotNull(stateDao.get()) {
			"Source-evidence state disappeared during raw-location repair"
		}
		val eligible = candidates.filter { candidate ->
			candidate.isEligibleFor(state.collectedDataEpoch, state.retainedFromMs)
		}
		if (eligible.isEmpty()) return 0
		check(stateDao.incrementRevision(Time.nowMillis) == 1) {
			"Unable to advance source-evidence revision during raw-location repair"
		}
		val revision = requireNotNull(stateDao.get()).revision
		val inserted = database.locationObservationDao().insert(
			eligible.map { candidate -> candidate.observation.copy(sourceRevision = revision) },
		)
		return inserted.count { rowId -> rowId != -1L }
	}

	private fun logRepairResult(repaired: Int) {
		if (repaired > 0) {
			Tracebox.log.info(
				TrackerTraceboxTemplates.RAW_LOCATION_REPAIR_COMPLETED,
				public(repaired),
			)
		}
	}

	private fun SourceEventWalEntity.toCanonicalObservationOrNull(): LocationObservation? {
		if (!hasRepairableIntegrity()) return null
		val decodedPayload = decodeLocationPayloadOrNull() ?: return null
		val coordinates = decodedPayload.toStoredCoordinatesOrNull()
		val elapsedTimeIsValid = hasValidElapsedTime()
		val repairedBatch = repairedBatchPosition()
		val receivedAt = acquiredAtMs
		return LocationObservation(
			fixTimeMs = wallTimeMs ?: acquiredAtMs,
			fixElapsedRealtimeNanos = observedElapsedNanos,
			receivedAtMs = receivedAt,
			receivedElapsedRealtimeNanos = receivedElapsedNanos,
			deliveryAgeMs = deliveryAgeMs(observedElapsedNanos, receivedElapsedNanos),
			latE7 = coordinates?.latE7,
			lonE7 = coordinates?.lonE7,
			rawAltitudeM = decodedPayload.altitudeMeters?.toFloat(),
			hAccM = decodedPayload.horizontalAccuracyMeters,
			vAccM = decodedPayload.verticalAccuracyMeters,
			speedMps = decodedPayload.speedMetersPerSecond,
			speedAccuracyMps = null,
			bearingDeg = decodedPayload.bearingDegrees,
			bearingAccuracyDeg = null,
			provider = decodedPayload.provider,
			acquisitionMode = LocationAcquisitionMode.UNKNOWN.name,
			requestPriority = LocationRequestPriority.UNKNOWN.name,
			permissionPrecision = LocationPermissionPrecision.UNKNOWN.name,
			batchIndex = repairedBatch.index,
			batchSize = repairedBatch.size,
			isMock = false,
			ingressDisposition = repairedIngressDisposition(coordinates, elapsedTimeIsValid).name,
			estimatorVersion = PersistenceProcessor.CURRENT_ESTIMATOR_VERSION,
			calibrationVersion = PersistenceProcessor.CURRENT_CALIBRATION_VERSION,
			createdAt = wallTimeMs ?: acquiredAtMs,
			sourceSignalId = "location-observation:$eventId",
			sourceEventId = eventId,
			callbackId = providerDedupKey ?: deliveryIdentity,
			clockDomainId = clockDomainId,
			bootClockDomainId = clockDomainId,
		)
	}

	private fun SourceEventWalEntity.hasRepairableIntegrity(): Boolean =
		hasQualifiedIntegrity() || hasVerifiedLegacyPayload() || hasPendingLegacyPayload()

	private fun SourceEventWalEntity.decodeLocationPayloadOrNull(): LocationFixPayload? = try {
		payloadCodec.decode(SourceKind.LOCATION, payloadVersion, payload) as LocationFixPayload
	} catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
		Tracebox.log.error(error, TrackerTraceboxTemplates.RAW_LOCATION_REPAIR_DECODE_FAILED)
		null
	}

	private fun LocationFixPayload.toStoredCoordinatesOrNull(): RawLocationRepairCoordinates? {
		if (
			!latitudeDegrees.isFinite() ||
			!longitudeDegrees.isFinite() ||
			latitudeDegrees !in CoordinateConstants.MIN_LATITUDE..CoordinateConstants.MAX_LATITUDE ||
			longitudeDegrees !in CoordinateConstants.MIN_LONGITUDE..CoordinateConstants.MAX_LONGITUDE
		) {
			return null
		}
		return RawLocationRepairCoordinates(
			latE7 = LatE7.fromDegrees(latitudeDegrees).raw,
			lonE7 = LonE7.fromDegrees(longitudeDegrees).raw,
		)
	}

	private fun SourceEventWalEntity.hasValidElapsedTime(): Boolean =
		observedElapsedNanos > 0L && receivedElapsedNanos > 0L && observedElapsedNanos <= receivedElapsedNanos

	private fun SourceEventWalEntity.repairedBatchPosition(): RawLocationRepairBatch {
		val size = deliveryUnitCount?.takeIf { count -> count > 0 } ?: 1
		val index = deliveryUnitIndex?.takeIf { unitIndex -> unitIndex in 0 until size } ?: 0
		return RawLocationRepairBatch(index, size)
	}

	private fun repairedIngressDisposition(
		coordinates: RawLocationRepairCoordinates?,
		hasValidElapsedTime: Boolean,
	): LocationIngressDisposition = when {
		coordinates == null -> LocationIngressDisposition.REJECTED_INVALID_COORDINATE
		!hasValidElapsedTime -> LocationIngressDisposition.REJECTED_INVALID_TIMESTAMP
		else -> LocationIngressDisposition.DELIVERED_VALID
	}

	private fun deliveryAgeMs(fixElapsedNanos: Long, receivedElapsedNanos: Long): Long? {
		if (fixElapsedNanos <= 0L || receivedElapsedNanos <= 0L || fixElapsedNanos > receivedElapsedNanos) {
			return null
		}
		return (receivedElapsedNanos - fixElapsedNanos).coerceAtLeast(0L) /
			Time.MILLISECONDS_IN_NANOSECONDS
	}

	private companion object {
		const val REPAIR_BATCH_SIZE = 256
	}
}

private data class RawLocationRepairCandidate(
	val capturedCollectedDataEpoch: Long,
	val acquiredAtMs: Long,
	val observation: LocationObservation,
) {
	fun isEligibleFor(collectedDataEpoch: Long, retainedFromMs: Long?): Boolean {
		if (capturedCollectedDataEpoch != collectedDataEpoch) {
			return false
		}
		return retainedFromMs == null || acquiredAtMs >= retainedFromMs
	}
}

private data class RawLocationRepairCoordinates(
	val latE7: Int,
	val lonE7: Int,
)

private data class RawLocationRepairBatch(
	val index: Int,
	val size: Int,
)
