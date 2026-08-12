package com.adsamcik.tracker.tracker.pipeline.persistence

import dev.tracebox.Tracebox
import android.database.sqlite.SQLiteConstraintException
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDecisionDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalClaimDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.mapper.toEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.base.database.data.MotionState as EntityMotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality as EntitySampleQuality
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.LocationDecision
import com.adsamcik.tracker.stats.api.signal.LocationDecisionSignal
import com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance
import com.adsamcik.tracker.stats.api.signal.ObservationStamp
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.signal.WifiSignal
import com.adsamcik.tracker.tracker.data.withDatabaseRetry
import com.adsamcik.tracker.tracker.pipeline.DurableAdmissionStatus
import com.adsamcik.tracker.tracker.pipeline.DurableSignalProcessor
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Unified persistence processor that writes all tracking data to Room, backed
 * by a durable write-ahead log ([DurableSignalBuffer]) so no signal is lost if
 * a flush fails or the process dies mid-write.
 *
 * ### Per-signal lifecycle
 *
 * 1. [onSignal] stages the raw signal in [durableBuffer] — no I/O, must be
 *    fast. Only the post-commit checkpoint callback builds typed destination
 *    rows, so no derived state is consumed before a pending row exists.
 * 2. [onFlush]/[onStop] persist those buffered destination rows and delete the
 *    exact WAL rows that backed them ([persistBufferedAndAcknowledge]) in one
 *    transaction. Either everything commits or everything rolls back and stays
 *    retryable.
 * 3. [onStart] recovers any WAL rows left over from a previous process by the
 *    same transactional persist-then-acknowledge path.
 *
 * ### Failure handling
 *
 * - A checkpoint failure preserves the staged signals and skips the flush.
 * - A destination/transaction failure preserves the in-memory typed buffers and
 *   the pending WAL IDs so the next flush retries them; the WAL rows are never
 *   deleted until their destination writes commit.
 * - Cancellation propagates, leaving all buffers/IDs intact.
 * - Corrupted or unsupported WAL rows move to the durable quarantine ledger in
 *   the same transaction that removes their claimed pending row, so recovery
 *   makes progress without silently discarding evidence.
 *
 * ### Thread-safety contract
 *
 * The mutable buffers below are **not** protected by an internal lock.
 * They are safe because all callers are serialized externally:
 *
 * - [onSignal] is called under the pipeline mutex in [ProcessorPipeline].
 * - [onFlush] is called from the pipeline's slow-path which is
 *   serialized by `componentMutex` in `TrackingOrchestrator`.
 * - [onStop] is called under the pipeline mutex during shutdown.
 *
 * If this invariant changes, the buffers **must** be guarded by a lock.
 */
@Singleton
class PersistenceProcessor @Inject constructor(
	private val locationSampleDao: LocationSampleDao,
	private val locationObservationDao: LocationObservationDao,
	private val locationObservationDecisionDao: LocationObservationDecisionDao? = null,
	private val sourceEvidenceStateDao: SourceEvidenceStateDao? = null,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore? = null,
	private val cellSampleDao: CellSampleDao,
	private val wifiObservationDao: WifiObservationDao,
	private val pressureSampleDao: PressureSampleDao,
	private val stepIntervalDao: StepIntervalDao,
	private val activitySnapshotDao: ActivitySnapshotDao,
	private val pendingSignalDao: PendingSignalDao,
	private val pendingSignalClaimDao: PendingSignalClaimDao? = null,
	private val durableBuffer: DurableSignalBuffer,
	private val transactor: TrackingPersistenceTransactor,
) : DurableSignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = PROCESSOR_ID,
		requiredTier = PolicyTier.AMBIENT,
		flushIntervalMs = FLUSH_INTERVAL_MS,
		priority = PRIORITY,
	)

	// --- Buffers (accessed only from onSignal which is single-threaded) ---
	private val locationBuffer = mutableListOf<LocationSample>()
	private val locationObservationBuffer = mutableListOf<LocationObservation>()
	private val locationDecisionBuffer = mutableListOf<BufferedLocationDecision>()
	private val cellBuffer = mutableListOf<CellSample>()
	private val wifiBuffer = mutableListOf<WifiObservation>()
	private val pressureBuffer = mutableListOf<PressureSample>()
	private val stepBuffer = mutableListOf<StepInterval>()
	private val activityBuffer = mutableListOf<ActivitySnapshot>()

	/**
	 * WAL row IDs that have been durably checkpointed but whose destination
	 * writes have not yet committed. Cleared only when the transaction that
	 * persists their destination rows succeeds; retained (with the typed
	 * buffers) on failure so the next flush retries the exact same rows.
	 */
	private val pendingIds = mutableListOf<Long>()
	/**
	 * Immutable lifecycle admission metadata for every row in [pendingIds].
	 *
	 * The WAL carries this data so recovery can re-check a deletion epoch or retention boundary
	 * immediately before publishing a destination row. Keeping it alongside the typed buffers lets
	 * a mixed batch discard only stale signals while preserving the rest.
	 */
	private val pendingAdmissions = mutableMapOf<Long, PendingAdmission>()
	/** Non-null only while replaying a lease-owned recovery batch. */
	private var pendingClaimToken: String? = null
	private var recoveryIncomplete = false
	private var commitStatusUnknown = false
	private var lastPersistenceFailure: Throwable? = null
	private val recoveryMutex = Mutex()
	private var pipelineActive = false

	private data class BufferedLocationDecision(
		val signal: LocationDecisionSignal,
		val sourceSignalId: String,
		val acceptedSampleSourceSignalId: String?,
		val clockDomainId: String?,
		val decidedAtMs: Long,
	)

	private data class PendingAdmission(
		val sourceSignalId: String,
		val capturedEpoch: Long,
		val acquiredAtMs: Long,
	)

	private enum class CommitResolution {
		COMMITTED,
		ROLLED_BACK,
		UNKNOWN,
	}

	override suspend fun onStart(context: ProcessorContext) = recoveryMutex.withLock {
		durableBuffer.setSessionId(context.sessionId)
		if (hasRetainedInMemoryState() && !flushAll()) {
			pipelineActive = true
			return@withLock
		}
		clearBuffers()
		pendingIds.clear()
		pendingAdmissions.clear()
		pendingClaimToken = null
		recoverPendingSignals()
		pipelineActive = true
	}

	/**
	 * Buffer incoming signal data and stage it for durable checkpointing.
	 * No I/O — must be fast.
	 */
	override fun onSignal(signal: TrackingSignal) {
		if (!signal.hasPersistablePayload()) return
		durableBuffer.stage(signal)
	}

	private fun TrackingSignal.hasPersistablePayload(): Boolean =
		locationObservation != null ||
			location != null ||
			locationDecision != null ||
			(activityFresh && activity != null) ||
			steps != null ||
			cells != null ||
			wifi != null ||
			pressure != null

	override suspend fun onFlush(): List<DomainEvent> {
		if (flushAll() && recoveryIncomplete) {
			recoverPendingSignals()
		}
		return emptyList()
	}

	override suspend fun onStop(): List<DomainEvent> = recoveryMutex.withLock {
		try {
			check(flushAll()) {
				"Final persistence flush failed; retaining buffered signals for retry"
			}
			emptyList()
		} finally {
			pipelineActive = false
		}
	}

	internal suspend fun drainOrphanedSignals(): Boolean = recoveryMutex.withLock {
		if (pipelineActive) return@withLock true
		clearBuffers()
		pendingIds.clear()
		pendingAdmissions.clear()
		pendingClaimToken = null
		recoverPendingSignals()
		!recoveryIncomplete && !durableBuffer.hasPendingEntries()
	}

	override suspend fun checkpointStagedSignals(): Boolean =
		checkpointStagedSignalsStatus() == DurableAdmissionStatus.ADMITTED

	override suspend fun checkpointStagedSignalsStatus(): DurableAdmissionStatus =
		checkpointStagedSignalsStatus(signalId = null)

	override suspend fun checkpointStagedSignalStatus(
		signal: TrackingSignal,
	): DurableAdmissionStatus = checkpointStagedSignalsStatus(
		signalId = signal.persistenceSignalId?.takeIf(String::isNotBlank),
	)

	private suspend fun checkpointStagedSignalsStatus(signalId: String?): DurableAdmissionStatus {
		try {
			val admission = withDatabaseRetry {
				durableBuffer.checkpointWithAdmission { committed ->
					committed.forEach { checkpointed ->
						bufferSignal(checkpointed.signal, checkpointed.signalId)
						pendingIds.add(checkpointed.id)
						pendingAdmissions[checkpointed.id] = PendingAdmission(
							sourceSignalId = checkpointed.signalId,
							capturedEpoch = checkpointed.capturedEpoch,
							acquiredAtMs = checkpointed.acquiredAtMs,
						)
					}
				}
			}
			return admission.statusFor(signalId)
		} catch (e: CancellationException) {
			throw e
		} catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
			Tracebox.log.error(error, "Tracking persistence write failed")
			return DurableAdmissionStatus.FAILED
		}
	}

	/** Parse [signal] into the typed destination buffers (no staging, no I/O). */
	private fun bufferSignal(signal: TrackingSignal, sourceSignalId: String) {
		val location = signal.location
		val policyName = signal.policy?.policyName

		bufferLocationObservation(signal, sourceSignalId)
		bufferLocation(signal, location, policyName, sourceSignalId)
		bufferLocationDecision(signal, sourceSignalId)
		bufferCells(signal, location, sourceSignalId)
		bufferWifi(signal, location, sourceSignalId)
		bufferPressure(signal, sourceSignalId)
		bufferSteps(signal, sourceSignalId)
		bufferActivity(signal, sourceSignalId)
	}

	// -----------------------------------------------------------------------
	// Buffering (no I/O)
	// -----------------------------------------------------------------------

	private fun bufferLocationObservation(signal: TrackingSignal, sourceSignalId: String) {
		val observation = signal.locationObservation ?: return
		locationObservationBuffer.add(
			LocationObservation(
				fixTimeMs = observation.rawFixTimeMs ?: signal.timestampMs.raw,
				fixElapsedRealtimeNanos = signal.elapsedRealtimeNanos,
				receivedAtMs = observation.receivedAtMs,
				receivedElapsedRealtimeNanos = observation.receivedElapsedRealtimeNanos,
				deliveryAgeMs = deliveryAgeMs(
					fixElapsedRealtimeNanos = signal.elapsedRealtimeNanos,
					receivedElapsedRealtimeNanos = observation.receivedElapsedRealtimeNanos,
				),
				latE7 = observation.coordinate?.lat?.raw,
				lonE7 = observation.coordinate?.lon?.raw,
				rawAltitudeM = observation.altitudeM,
				hAccM = observation.horizontalAccuracyM,
				vAccM = observation.verticalAccuracyM,
				speedMps = observation.speedMps,
				speedAccuracyMps = observation.speedAccuracyMps,
				bearingDeg = observation.bearingDeg,
				bearingAccuracyDeg = observation.bearingAccuracyDeg,
				provider = observation.provider,
				acquisitionMode = observation.acquisitionMode,
				requestPriority = observation.requestPriority,
				permissionPrecision = observation.permissionPrecision,
				batchIndex = observation.batchIndex,
				batchSize = observation.batchSize,
				isMock = observation.isMock,
				ingressDisposition = observation.ingressDisposition,
				estimatorVersion = CURRENT_ESTIMATOR_VERSION,
				calibrationVersion = CURRENT_CALIBRATION_VERSION,
				createdAt = signal.timestampMs.raw,
				sourceSignalId = sourceSignalId,
				sourceEventId = observation.sourceEventId,
				callbackId = observation.callbackId,
				clockDomainId = signal.clockDomainId,
				bootClockDomainId = signal.bootClockDomainId,
			),
		)
		// Invalid-at-ingress provider fixes never reach the curated dispatch stage. Their terminal
		// decision is still explicit, so future source reads distinguish them from an unresolved
		// pipeline interruption.
		val observationSourceEventId = observation.sourceEventId
		if (
			observationSourceEventId != null &&
			observation.ingressDisposition != "DELIVERED_VALID" &&
			signal.locationDecision == null
		) {
			locationDecisionBuffer.add(
				BufferedLocationDecision(
					signal = LocationDecisionSignal(
						sourceEventId = observationSourceEventId,
						decision = LocationDecision.REJECTED,
						reason = observation.ingressDisposition,
					),
					sourceSignalId = sourceSignalId,
					acceptedSampleSourceSignalId = null,
					clockDomainId = signal.clockDomainId,
					decidedAtMs = observation.receivedAtMs.takeIf { it > 0L }
						?: signal.timestampMs.raw,
				),
			)
		}
	}

	private fun bufferLocation(
		signal: TrackingSignal,
		location: LocationSignal?,
		policyName: String?,
		sourceSignalId: String,
	) {
		if (location == null) return

		val quality = classifyQuality(location.horizontalAccuracyM).toModel()
		val motionState = inferMotionState(signal.activity)?.toModel()

		locationBuffer.add(
			LocationSample(
				timeMs = signal.timestampMs.raw,
				elapsedRealtimeNanos = signal.elapsedRealtimeNanos,
				latE7 = location.coordinate.lat.raw,
				lonE7 = location.coordinate.lon.raw,
				altitudeM = location.altitudeM,
				rawGpsAltitudeM = location.rawGpsAltitudeM,
				altitudeDatum = location.altitudeDatum,
				altitudeSource = location.altitudeSource,
				altitudeConversionStatus = location.altitudeConversionStatus,
				rawGpsAltitudeDatum = location.rawGpsAltitudeDatum,
				altitudeModelVersion = location.altitudeModelVersion,
				hAccM = location.horizontalAccuracyM,
				vAccM = location.verticalAccuracyM,
				speedMps = location.speed?.raw,
				speedAccuracyMps = location.speedAccuracyMps,
				rawPlatformSpeedMps = location.rawPlatformSpeedMps,
				rawPlatformSpeedAccuracyMps = location.rawPlatformSpeedAccuracyMps,
				bearingDeg = location.bearingDeg,
				bearingAccuracyDeg = location.bearingAccuracyDeg,
				provider = location.provider,
				quality = quality,
				motionState = motionState,
				policy = policyName,
				bucketId = null,
				createdAt = signal.timestampMs.raw,
				receivedElapsedRealtimeNanos = location.receivedElapsedRealtimeNanos,
				deliveryAgeMs = deliveryAgeMs(
					fixElapsedRealtimeNanos = signal.elapsedRealtimeNanos,
					receivedElapsedRealtimeNanos = location.receivedElapsedRealtimeNanos,
				),
				acquisitionMode = location.acquisitionMode,
				requestPriority = location.requestPriority,
				permissionPrecision = location.permissionPrecision,
				batchIndex = location.batchIndex,
				batchSize = location.batchSize,
				isMock = location.isMock,
				estimatorVersion = location.altitudeEstimatorVersion,
				calibrationVersion = location.altitudeCalibrationVersion,
				sourceSignalId = sourceSignalId,
				sourceEventId = location.sourceEventId,
				clockDomainId = signal.clockDomainId,
				bootClockDomainId = signal.bootClockDomainId,
			),
		)
	}

	private fun bufferLocationDecision(signal: TrackingSignal, sourceSignalId: String) {
		val decision = signal.locationDecision ?: return
		locationDecisionBuffer.add(
			BufferedLocationDecision(
				signal = decision,
				sourceSignalId = sourceSignalId,
				acceptedSampleSourceSignalId = sourceSignalId.takeIf {
					decision.decision == LocationDecision.ACCEPTED
				},
				clockDomainId = signal.clockDomainId,
				decidedAtMs = signal.timestampMs.raw,
			),
		)
	}

	private fun deliveryAgeMs(
		fixElapsedRealtimeNanos: Long,
		receivedElapsedRealtimeNanos: Long,
	): Long? {
		if (fixElapsedRealtimeNanos <= 0L || receivedElapsedRealtimeNanos <= 0L) return null
		return ((receivedElapsedRealtimeNanos - fixElapsedRealtimeNanos).coerceAtLeast(0L) /
			Time.MILLISECONDS_IN_NANOSECONDS)
	}

	private fun bufferCells(
		signal: TrackingSignal,
		location: LocationSignal?,
		sourceSignalId: String,
	) {
		val cells = signal.cells ?: return
		val timeMs = signal.timestampMs.raw
		val latE7 = location?.coordinate?.lat?.raw
		val lonE7 = location?.coordinate?.lon?.raw
		val provenance = if (location != null) CoordinateProvenance.DIRECT else CoordinateProvenance.UNKNOWN

		for ((itemIndex, tower) in cells.towers.withIndex()) {
			cellBuffer.add(
				CellSample(
					timeMs = timeMs,
					cellId = tower.cellId,
					lac = tower.areaCode,
					mcc = tower.mcc.toIntOrNull() ?: 0,
					mnc = tower.mnc.toIntOrNull() ?: 0,
					networkType = tower.networkType,
					signalStrength = tower.signalStrength,
					latE7 = latE7,
					lonE7 = lonE7,
					provenance = provenance,
					createdAt = timeMs,
					sourceSignalId = sourceSignalId,
					sourceItemIndex = itemIndex,
					observationStamp = cells.stamp.toColumns(signal),
				),
			)
		}
	}

	private fun bufferWifi(
		signal: TrackingSignal,
		location: LocationSignal?,
		sourceSignalId: String,
	) {
		val wifi = signal.wifi ?: return
		val timeMs = wifi.timestampMs?.raw ?: signal.timestampMs.raw
		val coordinate = wifi.coordinate ?: location?.coordinate
		val latE7 = coordinate?.lat?.raw
		val lonE7 = coordinate?.lon?.raw
		val provenance = if (wifi.coordinate != null) {
			when (wifi.coordinateProvenance) {
				ObservationCoordinateProvenance.UNKNOWN -> CoordinateProvenance.UNKNOWN
				ObservationCoordinateProvenance.DIRECT -> CoordinateProvenance.DIRECT
				ObservationCoordinateProvenance.INTERPOLATED -> CoordinateProvenance.INTERPOLATED
			}
		} else if (location != null) CoordinateProvenance.DIRECT else CoordinateProvenance.UNKNOWN

		for ((itemIndex, network) in wifi.networks.withIndex()) {
			wifiBuffer.add(
				WifiObservation(
					timeMs = timeMs,
					bssid = network.bssid,
					ssid = network.ssid.ifEmpty { "<unknown>" },
					capabilities = network.capabilities,
					frequency = network.frequency,
					level = network.level,
					latE7 = latE7,
					lonE7 = lonE7,
					provenance = provenance,
					createdAt = timeMs,
					sourceSignalId = sourceSignalId,
					sourceItemIndex = itemIndex,
					observationStamp = wifi.stamp.toColumns(signal),
				),
			)
		}
	}

	private fun bufferPressure(signal: TrackingSignal, sourceSignalId: String) {
		val pressure = signal.pressure ?: return
		pressureBuffer.add(
			PressureSample(
				timeMs = signal.timestampMs.raw,
				elapsedRealtimeNanos = signal.elapsedRealtimeNanos,
				pressureHpa = pressure.pressureHpa,
				altitudeM = pressure.altitudeM,
				bucketId = null,
				createdAt = signal.timestampMs.raw,
				sourceSignalId = sourceSignalId,
				sampleCount = pressure.sampleCount,
				minPressureHpa = pressure.minPressureHpa,
				maxPressureHpa = pressure.maxPressureHpa,
				standardDeviationHpa = pressure.standardDeviationHpa,
				windowStartElapsedRealtimeNanos =
					pressure.windowStartElapsedRealtimeNanos,
				windowEndElapsedRealtimeNanos =
					pressure.windowEndElapsedRealtimeNanos,
				observationStamp = pressure.stamp.toColumns(signal),
			),
		)
	}

	private fun bufferSteps(signal: TrackingSignal, sourceSignalId: String) {
		val steps = signal.steps ?: return
		val stamp = steps.stamp
		val endTimeMs = stamp.sourceEpochOrEstimate(signal.timestampMs.raw)
		val sourceEndElapsed = stamp?.sourceElapsedRealtimeNanos
		val sourceStartElapsed = stamp?.sourceFirstElapsedRealtimeNanos
		val windowDurationMs = if (sourceEndElapsed != null && sourceStartElapsed != null) {
			((sourceEndElapsed - sourceStartElapsed).coerceAtLeast(0L) /
				Time.MILLISECONDS_IN_NANOSECONDS)
		} else {
			0L
		}
		stepBuffer.add(
			StepInterval(
				startTimeMs = endTimeMs - windowDurationMs,
				endTimeMs = endTimeMs,
				stepCount = steps.stepDelta.raw,
				sensorValueStart = steps.sensorValueStart,
				sensorValueEnd = steps.sensorValueEnd,
				sensorReset = steps.sensorReset,
				createdAt = signal.timestampMs.raw,
				sourceSignalId = sourceSignalId,
				observationStamp = stamp.toColumns(signal),
			),
		)
	}

	private fun bufferActivity(signal: TrackingSignal, sourceSignalId: String) {
		if (!signal.activityFresh) return
		val activity = signal.activity ?: return
		activityBuffer.add(
			ActivitySnapshot(
				timeMs = signal.timestampMs.raw,
				activityType = activity.type.ordinal,
				confidence = activity.confidence.raw,
				isTransition = false,
				createdAt = signal.timestampMs.raw,
				sourceSignalId = sourceSignalId,
				observationStamp = activity.stamp.toColumns(signal),
			),
		)
	}

	private fun ObservationStamp?.sourceEpochOrEstimate(fallbackEpochMs: Long): Long {
		if (this == null) return fallbackEpochMs
		sourceEpochMs?.let { return it }
		return fallbackEpochMs - (sourceAgeMs ?: 0L)
	}

	private fun ObservationStamp?.toColumns(signal: TrackingSignal): ObservationStampColumns {
		val stamp = this
		return ObservationStampColumns(
			sourceTimeMs = stamp?.sourceEpochMs,
			sourceElapsedRealtimeNanos = stamp?.sourceElapsedRealtimeNanos,
			sourceFirstElapsedRealtimeNanos = stamp?.sourceFirstElapsedRealtimeNanos,
			receivedTimeMs = stamp?.receivedEpochMs ?: signal.timestampMs.raw,
			receivedElapsedRealtimeNanos =
				stamp?.receivedElapsedRealtimeNanos ?: signal.elapsedRealtimeNanos,
			sourceSequence = stamp?.sourceSequence,
			sourceFirstSequence = stamp?.sourceFirstSequence,
			clockDomainId = stamp?.clockDomainId ?: signal.clockDomainId,
			bootClockDomainId = stamp?.bootClockDomainId ?: signal.bootClockDomainId,
			sourceAgeMs = stamp?.sourceAgeMs,
			timeUncertaintyMs = stamp?.timeUncertaintyMs,
			capabilityFlags = stamp?.capabilityFlags
				?.takeIf(Set<String>::isNotEmpty)
				?.toList()
				?.sorted()
				?.joinToString(","),
			permissionPrecision = stamp?.permissionPrecision,
		)
	}

	// -----------------------------------------------------------------------
	// Flushing (durable checkpoint + transactional persist/acknowledge)
	// -----------------------------------------------------------------------

	/**
	 * Durably checkpoint the staged signals to the WAL, then persist the
	 * buffered destination rows and acknowledge the WAL rows in one transaction.
	 *
	 * If the checkpoint fails the staged signals are preserved and the flush is
	 * skipped (retried next interval). The typed buffers hold rows whose signals
	 * are still only in [durableBuffer.stage]-ing until the checkpoint succeeds,
	 * so persisting them without a WAL backing could double-count them on
	 * recovery — hence the early return.
	 */
	private suspend fun flushAll(): Boolean {
		if (commitStatusUnknown && reconcileUnknownCommit() == CommitResolution.UNKNOWN) return false
		if (checkpointStagedSignalsStatus() == DurableAdmissionStatus.FAILED) return false

		return persistBufferedAndAcknowledge()
	}

	/**
	 * Persist every buffered destination row and delete the acknowledged WAL
	 * rows ([pendingIds]) inside a single transaction. On success the persisted
	 * in-memory snapshots and acknowledged IDs are cleared; on failure they are
	 * retained for retry and the error is reported (cancellation propagates).
	 */
	private suspend fun persistBufferedAndAcknowledge(): Boolean {
		if (isAllBuffersEmpty() && pendingIds.isEmpty()) return true

		val ackIds = pendingIds.toList()
		val claimToken = pendingClaimToken
		lastPersistenceFailure = null

		try {
			withTimeout(PERSISTENCE_TRANSACTION_TIMEOUT_MILLIS) {
				withDatabaseRetry {
					// Refresh for every retry attempt. A lifecycle transition can occur after a
					// transient SQLite failure and before the next publish transaction.
					val authoritativeLifecycle = captureAuthoritativeLifecycle()
					transactor.inTransaction {
						val staleSources = staleSourceSignalIds(ackIds, authoritativeLifecycle)
						insertBufferedDestinations(staleSources)
						deleteAcknowledgedRows(ackIds, claimToken)
					}
				}
			}
			withContext(NonCancellable) {
				clearBuffers()
				pendingIds.removeAll(ackIds.toSet())
				pendingAdmissions.keys.removeAll(ackIds.toSet())
				if (pendingClaimToken == claimToken) pendingClaimToken = null
			}
		} catch (e: TimeoutCancellationException) {
			lastPersistenceFailure = e
			commitStatusUnknown = true
			if (reconcileUnknownCommit() == CommitResolution.COMMITTED) return true
			Tracebox.log.error(e, "Tracking persistence write failed")
			return false
		} catch (e: CancellationException) {
			lastPersistenceFailure = e
			commitStatusUnknown = true
			withContext(NonCancellable) {
				reconcileUnknownCommit()
			}
			throw e
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			lastPersistenceFailure = e
			commitStatusUnknown = true
			if (reconcileUnknownCommit() == CommitResolution.COMMITTED) return true
			Tracebox.log.error(e, "Tracking persistence write failed")
			return false
		}

		return true
	}

	/** Delete only the rows this transaction is authorized to acknowledge. */
	private suspend fun deleteAcknowledgedRows(
		ackIds: List<Long>,
		claimToken: String?,
	) {
		if (ackIds.isEmpty()) return
		if (claimToken == null) {
			ackIds.chunked(ACK_DELETE_BATCH_SIZE).forEach { chunk ->
				pendingSignalDao.deleteByIds(chunk)
			}
			return
		}

		val claimDao = requireNotNull(pendingSignalClaimDao) {
			"PendingSignalClaimDao is required to acknowledge a claimed recovery batch"
		}
		val deleted = ackIds.chunked(ACK_DELETE_BATCH_SIZE).sumOf { chunk ->
			claimDao.deleteClaimedByIds(chunk, claimToken)
		}
		check(deleted == ackIds.size) {
			"Lost pending-signal recovery lease: deleted $deleted of ${ackIds.size} rows"
		}
	}

	/**
	 * Resolves the lifecycle guard inside the same Room transaction that publishes destinations.
	 *
	 * An old WAL row is intentionally acknowledged without writing a destination row. Its typed
	 * buffers are filtered by the returned source identities, while valid rows in the same batch
	 * continue through the normal persist-and-ack path.
	 */
	private suspend fun staleSourceSignalIds(
		ackIds: List<Long>,
		authoritativeLifecycle: CollectedDataLifecycleSnapshot?,
	): Set<String> {
		val guard = currentLifecycleGuardOrNull(authoritativeLifecycle) ?: return emptySet()
		return ackIds.asSequence()
			.mapNotNull(pendingAdmissions::get)
			.filterNot { admission ->
				guard.accepts(
					capturedEpoch = admission.capturedEpoch,
					acquiredAtMs = admission.acquiredAtMs,
				)
			}
			.map(PendingAdmission::sourceSignalId)
			.toSet()
	}

	/**
	 * Resolves the Room-side lifecycle mirror from the authoritative DataStore state.
	 *
	 * The caller captures the DataStore snapshot immediately before opening its Room transaction, so
	 * this method never holds a database write transaction across DataStore I/O. Reconciliation still
	 * happens in that transaction, which makes the source guard and destination publish indivisible.
	 */
	private suspend fun currentLifecycleGuardOrNull(
		authoritativeLifecycle: CollectedDataLifecycleSnapshot?,
	): SourceEvidenceState? {
		val stateDao = sourceEvidenceStateDao ?: return null
		stateDao.ensure()
		val current = requireNotNull(stateDao.get()) {
			"Source-evidence lifecycle guard disappeared inside transaction"
		}
		val desiredEpoch = maxOf(
			current.collectedDataEpoch,
			authoritativeLifecycle?.epoch ?: current.collectedDataEpoch,
		)
		val desiredRetainedFrom = listOfNotNull(
			current.retainedFromMs,
			authoritativeLifecycle?.retainedFromMs,
		).maxOrNull()
		if (
			desiredEpoch != current.collectedDataEpoch ||
			desiredRetainedFrom != current.retainedFromMs
		) {
			check(stateDao.updateLifecycle(
				epoch = desiredEpoch,
				retainedFromMs = desiredRetainedFrom,
				updatedAtMs = Time.nowMillis,
			) == 1) {
				"Unable to synchronize source-evidence lifecycle guard"
			}
		}
		return requireNotNull(stateDao.get()) {
			"Source-evidence lifecycle guard disappeared after synchronization"
		}
	}

	/** Captures DataStore state before acquiring the Room transaction that consumes it. */
	private suspend fun captureAuthoritativeLifecycle(): CollectedDataLifecycleSnapshot? =
		if (sourceEvidenceStateDao == null) null else collectedDataLifecycleStore?.snapshot()

	private fun SourceEvidenceState.accepts(capturedEpoch: Long, acquiredAtMs: Long): Boolean {
		val retainedFrom = retainedFromMs
		return capturedEpoch == collectedDataEpoch &&
			(retainedFrom == null || acquiredAtMs >= retainedFrom)
	}

	private suspend fun reconcileUnknownCommit(): CommitResolution {
		val ackIds = pendingIds.distinct()
		if (ackIds.isEmpty()) {
			commitStatusUnknown = false
			return CommitResolution.COMMITTED
		}

		val remainingCount = try {
			withTimeout(PERSISTENCE_RECONCILIATION_TIMEOUT_MILLIS) {
				var count = 0
				ackIds.chunked(ACK_DELETE_BATCH_SIZE).forEach { chunk ->
					count += pendingSignalDao.countByIds(chunk)
				}
				count
			}
		} catch (e: TimeoutCancellationException) {
			return CommitResolution.UNKNOWN
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			return CommitResolution.UNKNOWN
		}

		return when (remainingCount) {
			0 -> {
				clearBuffers()
				pendingIds.removeAll(ackIds.toSet())
				pendingAdmissions.keys.removeAll(ackIds.toSet())
				pendingClaimToken = null
				commitStatusUnknown = false
				CommitResolution.COMMITTED
			}
			ackIds.size -> {
				commitStatusUnknown = false
				CommitResolution.ROLLED_BACK
			}
			else -> {
				Tracebox.log.error("Persistence commit became inconsistent")
				CommitResolution.UNKNOWN
			}
		}
	}

	/**
	 * Insert all buffered destination rows in [batch]-sized chunks. Runs inside
	 * a transaction, so any failure aborts the whole transaction (no per-table
	 * isolation — durability comes from the WAL retry instead).
	 */
	private suspend fun insertBufferedDestinations(staleSourceSignalIds: Set<String>) {
		val observations = locationObservationBuffer.filterNot {
			it.hasStaleSourceSignal(staleSourceSignalIds)
		}
		val locations = locationBuffer.filterNot { it.hasStaleSourceSignal(staleSourceSignalIds) }
		val decisions = locationDecisionBuffer.filterNot {
			it.hasStaleSourceSignal(staleSourceSignalIds)
		}
		val cells = cellBuffer.filterNot { it.hasStaleSourceSignal(staleSourceSignalIds) }
		val wifi = wifiBuffer.filterNot { it.hasStaleSourceSignal(staleSourceSignalIds) }
		val pressure = pressureBuffer.filterNot { it.hasStaleSourceSignal(staleSourceSignalIds) }
		val steps = stepBuffer.filterNot { it.hasStaleSourceSignal(staleSourceSignalIds) }
		val activities = activityBuffer.filterNot { it.hasStaleSourceSignal(staleSourceSignalIds) }

		val sourceRevision = nextSourceRevisionOrNull(
			hasSourceMutation = observations.isNotEmpty() ||
				locations.isNotEmpty() ||
				decisions.isNotEmpty(),
		)
		// Raw evidence must exist before an accepted sample or decision is committed. This makes a
		// missing raw source a retryable transaction failure instead of silently weakening replay.
		observations.chunked(LOCATION_OBSERVATION_BATCH_SIZE).forEach { chunk ->
			locationObservationDao.insert(chunk.map { observation ->
				if (sourceRevision == null) observation else observation.copy(sourceRevision = sourceRevision)
			})
		}
		locations.chunked(LOCATION_BATCH_SIZE).forEach { chunk ->
			locationSampleDao.insert(chunk.map { sample ->
				val revised = if (sourceRevision == null) sample else sample.copy(sourceRevision = sourceRevision)
				revised.toEntity()
			})
		}
		insertLocationDecisions(decisions, sourceRevision)
		cells.chunked(CELL_BATCH_SIZE).forEach { chunk ->
			cellSampleDao.insert(chunk)
		}
		wifi.chunked(WIFI_BATCH_SIZE).forEach { chunk ->
			wifiObservationDao.insert(chunk)
		}
		pressure.chunked(PRESSURE_BATCH_SIZE).forEach { chunk ->
			pressureSampleDao.insert(chunk)
		}
		steps.chunked(STEP_BATCH_SIZE).forEach { chunk ->
			stepIntervalDao.insert(chunk)
		}
		activities.chunked(ACTIVITY_BATCH_SIZE).forEach { chunk ->
			activitySnapshotDao.insert(chunk)
		}
	}

	private fun LocationObservation.hasStaleSourceSignal(staleSourceSignalIds: Set<String>): Boolean =
		sourceSignalId != null && sourceSignalId in staleSourceSignalIds

	private fun LocationSample.hasStaleSourceSignal(staleSourceSignalIds: Set<String>): Boolean =
		sourceSignalId != null && sourceSignalId in staleSourceSignalIds

	private fun BufferedLocationDecision.hasStaleSourceSignal(
		staleSourceSignalIds: Set<String>,
	): Boolean = sourceSignalId in staleSourceSignalIds

	private fun CellSample.hasStaleSourceSignal(staleSourceSignalIds: Set<String>): Boolean =
		sourceSignalId != null && sourceSignalId in staleSourceSignalIds

	private fun WifiObservation.hasStaleSourceSignal(staleSourceSignalIds: Set<String>): Boolean =
		sourceSignalId != null && sourceSignalId in staleSourceSignalIds

	private fun PressureSample.hasStaleSourceSignal(staleSourceSignalIds: Set<String>): Boolean =
		sourceSignalId != null && sourceSignalId in staleSourceSignalIds

	private fun StepInterval.hasStaleSourceSignal(staleSourceSignalIds: Set<String>): Boolean =
		sourceSignalId != null && sourceSignalId in staleSourceSignalIds

	private fun ActivitySnapshot.hasStaleSourceSignal(staleSourceSignalIds: Set<String>): Boolean =
		sourceSignalId != null && sourceSignalId in staleSourceSignalIds

	/** Bump once per source transaction and read the resulting coherent snapshot revision. */
	private suspend fun nextSourceRevisionOrNull(hasSourceMutation: Boolean): Long? {
		if (!hasSourceMutation) return null
		val stateDao = sourceEvidenceStateDao ?: return null
		stateDao.ensure()
		check(stateDao.incrementRevision(Time.nowMillis) == 1) {
			"Unable to advance source-evidence revision"
		}
		return requireNotNull(stateDao.get()) { "Source-evidence state disappeared inside transaction" }.revision
	}

	private suspend fun insertLocationDecisions(
		bufferedDecisions: List<BufferedLocationDecision>,
		sourceRevision: Long?,
	) {
		if (bufferedDecisions.isEmpty()) return
		val decisionDao = requireNotNull(locationObservationDecisionDao) {
			"Location decisions require LocationObservationDecisionDao"
		}
		val decisions = bufferedDecisions.map { buffered ->
			check(locationObservationDao.existsBySourceEventId(buffered.signal.sourceEventId)) {
				"Cannot persist ${buffered.signal.decision} decision without raw observation " +
					"${buffered.signal.sourceEventId}"
			}
			LocationObservationDecision(
				observationSourceEventId = buffered.signal.sourceEventId,
				decision = buffered.signal.decision.name,
				reason = buffered.signal.reason,
				acceptedSampleSourceSignalId = buffered.acceptedSampleSourceSignalId,
				sourceSignalId = buffered.sourceSignalId,
				clockDomainId = buffered.clockDomainId,
				decidedAtMs = buffered.decidedAtMs,
				sourceRevision = sourceRevision ?: 0L,
			)
		}
		decisionDao.insert(decisions)
	}

	/**
	 * Replay lease-owned WAL rows across every session.  A malformed or
	 * forward-incompatible row moves to the durable quarantine ledger in the
	 * same transaction that removes its claimed pending row.  Transient storage
	 * failures retain and release the claim for retry; known permanent
	 * destination failures are isolated by recursively splitting the batch so
	 * one poison row cannot block later sessions.
	 */
	private suspend fun recoverPendingSignals() {
		recoveryIncomplete = false
		while (true) {
			val claimed = durableBuffer.claimBatch(DurableSignalBuffer.RECOVERY_BATCH_SIZE) ?: break
			val claimToken = claimed.claimToken
			val currentSignals = discardStaleClaimedSignals(claimed.signals, claimToken)
			if (currentSignals == null) {
				durableBuffer.releaseClaim(claimToken)
				recoveryIncomplete = true
				break
			}
			val undecodable = currentSignals.filter { it.payload !is PendingSignalDecodeResult.Valid }

			val quarantined = undecodable.all { entry ->
				quarantineClaimed(
					entry = entry,
					claimToken = claimToken,
					failureReason = entry.decodeFailureCode(),
					failureDetail = null,
				)
			}
			if (!quarantined) {
				durableBuffer.releaseClaim(claimToken)
				recoveryIncomplete = true
				break
			}

			val valid = currentSignals.filter { it.payload is PendingSignalDecodeResult.Valid }
			if (!recoverClaimedValidSignals(valid, claimToken)) {
				durableBuffer.releaseClaim(claimToken)
				recoveryIncomplete = true
				break
			}
		}
	}

	/**
	 * Drops stale rows before decoding/quarantining them. Retention and deletion are privacy
	 * boundaries, not malformed-payload diagnostics: an expired row must not be copied into the
	 * quarantine ledger just because it cannot be decoded.
	 */
	private suspend fun discardStaleClaimedSignals(
		entries: List<DurableSignalBuffer.PeekedSignal>,
		claimToken: String,
	): List<DurableSignalBuffer.PeekedSignal>? = try {
		withDatabaseRetry {
			val authoritativeLifecycle = captureAuthoritativeLifecycle()
			transactor.inTransaction {
				val guard = currentLifecycleGuardOrNull(authoritativeLifecycle)
				if (guard == null) return@inTransaction entries
				val staleIds = entries.filterNot { entry ->
					guard.accepts(
						capturedEpoch = entry.capturedEpoch,
						acquiredAtMs = entry.acquiredAtMs,
					)
				}.map(DurableSignalBuffer.PeekedSignal::id)
				deleteAcknowledgedRows(staleIds, claimToken)
				entries.filterNot { entry -> entry.id in staleIds }
			}
		}
	} catch (e: CancellationException) {
		throw e
	} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
		null
	}

	private suspend fun recoverClaimedValidSignals(
		entries: List<DurableSignalBuffer.PeekedSignal>,
		claimToken: String,
	): Boolean {
		if (entries.isEmpty()) return true

		clearBuffers()
		pendingIds.clear()
		pendingAdmissions.clear()
		pendingClaimToken = claimToken
		entries.forEach { entry ->
			val signal = (entry.payload as PendingSignalDecodeResult.Valid).signal
			bufferSignal(signal, entry.signalId)
			pendingIds.add(entry.id)
			pendingAdmissions[entry.id] = PendingAdmission(
				sourceSignalId = entry.signalId,
				capturedEpoch = entry.capturedEpoch,
				acquiredAtMs = entry.acquiredAtMs,
			)
		}

		if (persistBufferedAndAcknowledge()) return true

		val failure = lastPersistenceFailure
		clearBuffers()
		pendingIds.clear()
		pendingAdmissions.clear()
		pendingClaimToken = null
		if (!failure.isKnownPermanentDestinationFailure()) return false

		if (entries.size == 1) {
			val entry = entries.single()
			return quarantineClaimed(
				entry = entry,
				claimToken = claimToken,
				failureReason = PERMANENT_DESTINATION_FAILURE,
				failureDetail = failure.safeFailureDetail(),
			)
		}

		val midpoint = entries.size / 2
		return recoverClaimedValidSignals(entries.take(midpoint), claimToken) &&
			recoverClaimedValidSignals(entries.drop(midpoint), claimToken)
	}

	/** Move one terminal row into quarantine and delete only our lease-owned source row. */
	private suspend fun quarantineClaimed(
		entry: DurableSignalBuffer.PeekedSignal,
		claimToken: String,
		failureReason: String,
		failureDetail: String?,
	): Boolean = try {
		withDatabaseRetry {
			val authoritativeLifecycle = captureAuthoritativeLifecycle()
			transactor.inTransaction {
				val guard = currentLifecycleGuardOrNull(authoritativeLifecycle)
				if (
					guard != null && !guard.accepts(
						capturedEpoch = entry.capturedEpoch,
						acquiredAtMs = entry.acquiredAtMs,
					)
				) {
					deleteAcknowledgedRows(listOf(entry.id), claimToken)
					return@inTransaction true
				}
				requireNotNull(pendingSignalClaimDao) {
					"PendingSignalClaimDao is required to quarantine a claimed recovery batch"
				}.quarantineClaimed(
					signal = QuarantinedSignalEntity(
						sourcePendingId = entry.id,
						signalId = entry.signalId,
						sessionId = entry.sessionId,
						envelopeVersion = entry.envelopeVersion,
						payloadChecksum = entry.payloadChecksum,
						signalJson = entry.signalJson,
						createdAt = entry.createdAt,
						deliveryAttemptCount = entry.deliveryAttemptCount,
						failureReason = failureReason,
						failureDetail = failureDetail,
						quarantinedAt = Time.nowMillis,
					),
					claimToken = claimToken,
				)
			}
		}
	} catch (e: CancellationException) {
		throw e
	} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
		false
	}

	private fun DurableSignalBuffer.PeekedSignal.decodeFailureCode(): String = when (val result = payload) {
		is PendingSignalDecodeResult.Malformed -> result.reason.code
		is PendingSignalDecodeResult.Unsupported -> result.reason.code
		is PendingSignalDecodeResult.Valid -> error("Valid pending signal has no decode failure")
	}

	private fun Throwable?.isKnownPermanentDestinationFailure(): Boolean {
		var current: Throwable? = this
		while (current != null) {
			if (current is SQLiteConstraintException || current is IllegalArgumentException) return true
			current = current.cause
		}
		return false
	}

	private fun Throwable?.safeFailureDetail(): String? {
		if (this == null) return null
		val message = message?.replace(Regex("\\s+"), " ")?.take(MAX_FAILURE_DETAIL_LENGTH)
		return listOfNotNull(javaClass.simpleName.takeIf { it.isNotBlank() }, message)
			.joinToString(": ")
			.take(MAX_FAILURE_DETAIL_LENGTH)
	}

	private fun hasRetainedInMemoryState(): Boolean =
		!isAllBuffersEmpty() ||
			pendingIds.isNotEmpty() ||
			pendingClaimToken != null ||
			durableBuffer.stagingSize > 0

	private fun isAllBuffersEmpty(): Boolean =
		locationBuffer.isEmpty() &&
			locationObservationBuffer.isEmpty() &&
			locationDecisionBuffer.isEmpty() &&
			cellBuffer.isEmpty() &&
			wifiBuffer.isEmpty() &&
			pressureBuffer.isEmpty() &&
			stepBuffer.isEmpty() &&
			activityBuffer.isEmpty()

	private fun clearBuffers() {
		locationBuffer.clear()
		locationObservationBuffer.clear()
		locationDecisionBuffer.clear()
		cellBuffer.clear()
		wifiBuffer.clear()
		pressureBuffer.clear()
		stepBuffer.clear()
		activityBuffer.clear()
	}

	companion object {
		internal const val PROCESSOR_ID = "persistence"
		internal const val FLUSH_INTERVAL_MS = 5000L
		internal const val PRIORITY = 0
		private const val PERSISTENCE_TRANSACTION_TIMEOUT_MILLIS = 3_000L
		private const val PERSISTENCE_RECONCILIATION_TIMEOUT_MILLIS = 2_000L
		private const val PERMANENT_DESTINATION_FAILURE = "permanent_destination_failure"
		private const val MAX_FAILURE_DETAIL_LENGTH = 512

		internal const val LOCATION_BATCH_SIZE = 10
		internal const val LOCATION_OBSERVATION_BATCH_SIZE = 25
		internal const val PRESSURE_BATCH_SIZE = 5
		internal const val CELL_BATCH_SIZE = 50
		internal const val WIFI_BATCH_SIZE = 50
		internal const val STEP_BATCH_SIZE = 20
		internal const val ACTIVITY_BATCH_SIZE = 20
		internal const val ACK_DELETE_BATCH_SIZE = 900

		private const val HIGH_ACCURACY_THRESHOLD = 10f
		private const val MEDIUM_ACCURACY_THRESHOLD = 50f
		internal const val CURRENT_ESTIMATOR_VERSION = 1
		internal const val CURRENT_CALIBRATION_VERSION = 0

		/**
		 * Classify location quality from horizontal accuracy.
		 */
		internal fun classifyQuality(horizontalAccuracyM: Float?): EntitySampleQuality = when {
			horizontalAccuracyM == null -> EntitySampleQuality.COARSE
			horizontalAccuracyM < HIGH_ACCURACY_THRESHOLD -> EntitySampleQuality.HIGH
			horizontalAccuracyM < MEDIUM_ACCURACY_THRESHOLD -> EntitySampleQuality.MEDIUM
			else -> EntitySampleQuality.LOW
		}

		/**
		 * Infer motion state from activity recognition.
		 */
		internal fun inferMotionState(activity: ActivitySignal?): EntityMotionState? {
			if (activity == null) return null
			return when (activity.type) {
				DetectedActivityType.STILL -> EntityMotionState.STILL
				DetectedActivityType.WALKING,
				DetectedActivityType.RUNNING,
				DetectedActivityType.ON_FOOT,
				DetectedActivityType.IN_VEHICLE,
				DetectedActivityType.ON_BICYCLE,
				-> EntityMotionState.MOVING

				else -> EntityMotionState.UNKNOWN
			}
		}
	}
}

private fun EntitySampleQuality.toModel(): SampleQuality = SampleQuality.valueOf(name)

private fun EntityMotionState.toModel(): MotionState = MotionState.valueOf(name)
