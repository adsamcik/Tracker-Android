package com.adsamcik.tracker.tracker.source.location

import android.content.Context
import android.location.Location
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.LocationFixMetadata
import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationState
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sessionless adapter for the existing curation component and permanent destination owner. */
@Singleton
internal class ProtectedLocationAmbientCanonicalWriter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: AppDatabase,
	private val dispatchers: DispatchersProvider,
	private val persistence: PersistenceProcessor,
) {
	private val mutex = Mutex()

	suspend fun write(command: LocationAmbientFactCommand): ProtectedLocationCanonicalWriteResult = mutex.withLock {
		try {
			val existing = persistence.hasCommittedProtectedAmbientLocation(command)
			if (existing) return@withLock ProtectedLocationCanonicalWriteResult.Committed
			val before = database.withTransaction { loadLocationAmbientCurationState(command) }
			val component = LocationTrackerComponent(trackingParamsRepository = null, dispatchers = dispatchers)
			val metadata = command.acquisitionMetadata
			val curation = metadata.toCanonicalCurationContext(before)
			val data = MutableCollectionData(requireNotNull(command.wal.wallTimeMs))
			if (command.payload.isMock != true) {
				component.onEnable(context)
				try {
					component.onDataUpdated(command.toAmbientTrackingCycle(curation), data)
				} finally {
					withContext(NonCancellable) { component.onDisable(context) }
				}
			}
			val reason = if (command.payload.isMock == true) "MOCK_LOCATION_REJECTED"
				else curation.outcome.decisionReason
			val accepted = data.location?.horizontalAccuracy != null
			check(accepted || reason != null) { "Location curation has no terminal outcome" }
			persistence.commitProtectedAmbientLocation(ProtectedLocationAmbientWrite(
				command, before,
				if (command.payload.isMock == true) before else curation.outcome.stateAfter,
				command.toAmbientObservation(),
				command.toAmbientDecision(accepted, reason),
				if (accepted) command.toAmbientSample(data) else null,
			))
			ProtectedLocationCanonicalWriteResult.Committed
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			// No acknowledgement: the finite durable WAL high-water is retried by the handoff.
			ProtectedLocationCanonicalWriteResult.Failed("LOCATION_AMBIENT_CANONICAL_WRITE_FAILED", false)
		}
	}
}

internal data class ProtectedLocationAmbientWrite(
	val command: LocationAmbientFactCommand,
	val stateBefore: LocationCanonicalCurationState,
	val stateAfter: LocationCanonicalCurationState,
	val observation: LocationObservation,
	val decision: LocationObservationDecision,
	val sample: LocationSample?,
)

private fun LocationAmbientFactCommand.toAmbientTrackingCycle(
	curation: com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationContext,
): TrackingCycle {
	val location = Location(payload.provider).apply {
		time = requireNotNull(wal.wallTimeMs)
		elapsedRealtimeNanos = wal.observedElapsedNanos
		latitude = payload.latitudeDegrees
		longitude = payload.longitudeDegrees
		accuracy = payload.horizontalAccuracyMeters
		payload.altitudeMeters?.let { altitude = it }
		payload.verticalAccuracyMeters?.let { verticalAccuracyMeters = it }
		payload.speedMetersPerSecond?.let { speed = it }
		payload.bearingDegrees?.let { bearing = it }
	}
	return TrackingCycle(timestampMs = requireNotNull(wal.wallTimeMs), elapsedRealtimeNanos = wal.observedElapsedNanos,
		location = LocationData(listOf(location), null, null, listOf(LocationFixMetadata(
			callbackId = wal.deliveryIdentity, sourceEventId = wal.eventId,
			clockDomainId = wal.clockDomainId, bootClockDomainId = wal.clockDomainId,
			receivedAtMs = requireNotNull(wal.receivedWallTimeMs), receivedElapsedRealtimeNanos = wal.receivedElapsedNanos,
			acquisitionMode = acquisitionMetadata.acquisitionMode, requestPriority = acquisitionMetadata.requestPriority,
			permissionPrecision = permissionPrecision(), batchIndex = requireNotNull(wal.deliveryUnitIndex),
			batchSize = requireNotNull(wal.deliveryUnitCount),
		))), locationCanonicalCuration = curation,
		persistenceSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(wal.eventId))
}

private fun LocationAmbientFactCommand.permissionPrecision(): LocationPermissionPrecision =
	if (wal.qualityFlags and SourceQualityFlag.APPROXIMATE.bit != 0L) LocationPermissionPrecision.APPROXIMATE
	else LocationPermissionPrecision.PRECISE

internal fun LocationAmbientFactCommand.toAmbientObservation(): LocationObservation = LocationObservation(
	fixTimeMs = requireNotNull(wal.wallTimeMs), fixElapsedRealtimeNanos = wal.observedElapsedNanos,
	receivedAtMs = requireNotNull(wal.receivedWallTimeMs), receivedElapsedRealtimeNanos = wal.receivedElapsedNanos,
	deliveryAgeMs = (wal.receivedElapsedNanos - wal.observedElapsedNanos) / 1_000_000,
	latE7 = (payload.latitudeDegrees * 1e7).toInt(), lonE7 = (payload.longitudeDegrees * 1e7).toInt(),
	rawAltitudeM = payload.altitudeMeters?.toFloat(), hAccM = payload.horizontalAccuracyMeters,
	vAccM = payload.verticalAccuracyMeters, speedMps = payload.speedMetersPerSecond, speedAccuracyMps = null,
	provider = payload.provider, acquisitionMode = acquisitionMetadata.acquisitionMode.name,
	requestPriority = acquisitionMetadata.requestPriority.name, permissionPrecision = permissionPrecision().name,
	batchIndex = requireNotNull(wal.deliveryUnitIndex), batchSize = requireNotNull(wal.deliveryUnitCount),
	isMock = requireNotNull(payload.isMock), ingressDisposition = "DELIVERED_VALID",
	estimatorVersion = 1, calibrationVersion = 0,
	createdAt = requireNotNull(wal.wallTimeMs), sourceSignalId = ProtectedLocationCanonicalSignalIdentity.rawObservation(wal.eventId),
	sourceEventId = wal.eventId, callbackId = wal.deliveryIdentity, clockDomainId = wal.clockDomainId,
	bearingDeg = payload.bearingDegrees, bearingAccuracyDeg = null, bootClockDomainId = wal.clockDomainId,
)

private fun LocationAmbientFactCommand.toAmbientDecision(accepted: Boolean, reason: String?) = LocationObservationDecision(
	observationSourceEventId = wal.eventId,
	decision = if (accepted) LocationObservationDecision.ACCEPTED else LocationObservationDecision.REJECTED,
	reason = reason, acceptedSampleSourceSignalId = if (accepted) ProtectedLocationCanonicalSignalIdentity.canonicalProduct(wal.eventId) else null,
	sourceSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(wal.eventId),
	clockDomainId = wal.clockDomainId, decidedAtMs = requireNotNull(wal.wallTimeMs),
)

private fun LocationAmbientFactCommand.toAmbientSample(data: MutableCollectionData): LocationSample {
	val accepted = requireNotNull(data.location)
	val altitude = data.processedAltitude
	return LocationSample(timeMs = requireNotNull(wal.wallTimeMs), elapsedRealtimeNanos = wal.observedElapsedNanos,
		latE7 = (accepted.latitude * 1e7).toInt(), lonE7 = (accepted.longitude * 1e7).toInt(),
		altitudeM = altitude?.altitudeM, rawGpsAltitudeM = data.rawGpsAltitudeM,
		hAccM = accepted.horizontalAccuracy, vAccM = accepted.verticalAccuracy,
		speedMps = data.estimatedSpeedMps, speedAccuracyMps = null, provider = payload.provider,
		quality = PersistenceProcessor.classifyQuality(accepted.horizontalAccuracy), motionState = null,
		policy = acquisitionMetadata.policyName, bucketId = null, createdAt = requireNotNull(wal.wallTimeMs),
		receivedElapsedRealtimeNanos = wal.receivedElapsedNanos,
		deliveryAgeMs = (wal.receivedElapsedNanos - wal.observedElapsedNanos) / 1_000_000,
		acquisitionMode = acquisitionMetadata.acquisitionMode.name, requestPriority = acquisitionMetadata.requestPriority.name,
		permissionPrecision = permissionPrecision().name, batchIndex = requireNotNull(wal.deliveryUnitIndex),
		batchSize = requireNotNull(wal.deliveryUnitCount), isMock = false,
		estimatorVersion = altitude?.estimatorVersion ?: 0, calibrationVersion = altitude?.calibrationVersion ?: 0,
		sourceSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(wal.eventId), sourceEventId = wal.eventId,
		clockDomainId = wal.clockDomainId, bootClockDomainId = wal.clockDomainId,
		altitudeDatum = altitude?.datum ?: com.adsamcik.tracker.shared.model.AltitudeDatum.UNKNOWN_LEGACY,
		altitudeSource = altitude?.source ?: com.adsamcik.tracker.shared.model.AltitudeSource.UNKNOWN_LEGACY,
		altitudeConversionStatus = altitude?.conversionStatus ?: com.adsamcik.tracker.shared.model.AltitudeConversionStatus.UNKNOWN_LEGACY,
		rawGpsAltitudeDatum = altitude?.rawAltitudeDatum ?: com.adsamcik.tracker.shared.model.AltitudeDatum.UNKNOWN_LEGACY,
		altitudeModelVersion = altitude?.modelVersion ?: 0, rawPlatformSpeedMps = payload.speedMetersPerSecond,
		bearingDeg = payload.bearingDegrees,
	)
}

internal suspend fun AppDatabase.loadLocationAmbientCurationState(command: LocationAmbientFactCommand): LocationCanonicalCurationState {
	val row = sourceProjectionStateDao().joinState(ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION, ambientStateKey(command)) ?: return LocationCanonicalCurationState.EMPTY
	check(row.logicalTrackingId == null && row.payloadVersion == ProtectedLocationCanonicalCurationStateCodec.VERSION)
	check(row.minimumRequiredOrdinal - 1 < command.wal.admissionOrdinal)
	return ProtectedLocationCanonicalCurationStateCodec.decode(row.payload)
}

/** Full content comparison prevents an event-ID collision being adopted as a successful retry. */
internal suspend fun AppDatabase.readLocationAmbientReceipt(command: LocationAmbientFactCommand): Boolean? {
	val row = sourceProjectionStateDao().joinState(ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION, ambientReceiptKey(command)) ?: return null
	check(row.logicalTrackingId == null && row.payloadVersion == 1 && row.payload.size in 1..2048)
	val observation = requireNotNull(locationObservationDao().getBySourceEventId(command.wal.eventId))
	val decision = requireNotNull(locationObservationDecisionDao().getBySourceEventId(command.wal.eventId))
	val sample = locationSampleDao().getBySourceSignalId(ProtectedLocationCanonicalSignalIdentity.canonicalProduct(command.wal.eventId))
	check((decision.decision == LocationObservationDecision.ACCEPTED) == (sample != null))
	DataInputStream(ByteArrayInputStream(row.payload)).use { input ->
		check(input.readUTF() == command.wal.integrityIdentity)
		check(input.readUTF() == command.authority.demandId)
		check(input.readUTF() == locationContentDigest(observation.canonicalReceiptBytes()))
		check(input.readUTF() == locationContentDigest(decision.canonicalReceiptBytes()))
		check(input.readUTF() == (sample?.let { locationContentDigest(it.canonicalReceiptBytes()) } ?: ""))
		check(input.available() == 0)
	}
	return sample != null
}

/** Invoked only by PersistenceProcessor after authority and curation-state CAS in its transaction. */
internal suspend fun AppDatabase.recordLocationAmbientReceipt(write: ProtectedLocationAmbientWrite) {
	val command = write.command
	val observation = requireNotNull(locationObservationDao().getBySourceEventId(command.wal.eventId))
	val decision = requireNotNull(locationObservationDecisionDao().getBySourceEventId(command.wal.eventId))
	val sample = locationSampleDao().getBySourceSignalId(ProtectedLocationCanonicalSignalIdentity.canonicalProduct(command.wal.eventId))
	val bytes = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use { out ->
		out.writeUTF(command.wal.integrityIdentity); out.writeUTF(command.authority.demandId)
		out.writeUTF(locationContentDigest(observation.canonicalReceiptBytes()))
		out.writeUTF(locationContentDigest(decision.canonicalReceiptBytes()))
		out.writeUTF(sample?.let { locationContentDigest(it.canonicalReceiptBytes()) } ?: "")
	} }.toByteArray()
	sourceProjectionStateDao().saveJoinState(ambientJoin(command, ambientReceiptKey(command), bytes, 1, Long.MAX_VALUE))
	sourceProjectionStateDao().saveJoinState(ambientJoin(command, ambientStateKey(command),
		ProtectedLocationCanonicalCurationStateCodec.encode(write.stateAfter), ProtectedLocationCanonicalCurationStateCodec.VERSION,
		Math.addExact(command.wal.admissionOrdinal, 1)))
	if (sample != null) recordLocationAmbientPurpose(command, sample)
}

/** Capture and ambient associate the same accepted sample, never insert a second coordinate row. */
internal suspend fun AppDatabase.recordLocationAmbientPurpose(command: LocationAmbientFactCommand, sample: LocationSample) {
	val wal = command.wal
	val a = command.authority
	val common = LocationAcceptedReceiptEntity(sample.id, wal.eventId, requireNotNull(sample.sourceSignalId),
		wal.admissionOrdinal, wal.integrityIdentity, LocationAcceptedReceiptIntegrity.canonicalChecksum(sample),
		wal.sourceInstanceId, wal.registrationGeneration, wal.sourceSequence, requireNotNull(wal.deliveryIdentity),
		requireNotNull(wal.deliveryUnitIndex), requireNotNull(wal.deliveryUnitCount), ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION, requireNotNull(wal.configRevision), requireNotNull(wal.physicalConfigurationFingerprint),
		requireNotNull(wal.authorizationRevision), requireNotNull(wal.authorizationFingerprint), wal.clockDomainId, wal.clockDomainId,
		wal.observedElapsedNanos, wal.receivedElapsedNanos, requireNotNull(wal.wallTimeMs), wal.receivedWallTimeMs,
		requireNotNull(wal.wallTimeUncertaintyMs), wal.capturedCollectedDataEpoch)
	val dao = locationPurposeDao()
	if (dao.insertReceiptIfAbsent(common) == -1L) check(dao.receipt(sample.id) == common && dao.receiptByEvent(wal.eventId) == common)
	val purpose = LocationSamplePurposeAuthorityEntity(sample.id, SourceBrokerPurpose.AMBIENT_PRODUCT, a.demandId,
		a.sourcePolicyRevision, a.consentEpoch, wal.observedElapsedNanos, Math.addExact(wal.observedElapsedNanos, 1),
		null, null, null, null, null, a.demandId, a.retentionPolicyId, a.retentionApprovalRevision, 0, null)
	if (dao.insertPurposeIfAbsent(purpose) == -1L) check(dao.purpose(sample.id, purpose.purpose, purpose.authorityId) == purpose)
}

private fun ambientStateKey(c: LocationAmbientFactCommand) = "ambient-curation:${c.authority.consentEpoch}:${c.wal.clockDomainId}:${c.wal.sourceInstanceId}:${c.wal.registrationGeneration}"
private fun ambientReceiptKey(c: LocationAmbientFactCommand) = "ambient-receipt:${c.wal.eventId}"
private fun ambientJoin(c: LocationAmbientFactCommand, key: String, payload: ByteArray, version: Int, ordinal: Long) =
	SourceProjectionJoinStateEntity(ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		key, null, ordinal, version, payload, requireNotNull(c.wal.receivedWallTimeMs))
private fun locationContentDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
