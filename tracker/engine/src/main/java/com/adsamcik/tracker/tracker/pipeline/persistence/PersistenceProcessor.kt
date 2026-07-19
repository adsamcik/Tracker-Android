package com.adsamcik.tracker.tracker.pipeline.persistence

import android.util.Log
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.mapper.toEntity
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
import com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.signal.WifiSignal
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.withDatabaseRetry
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
 * 1. [onSignal] parses the signal into the typed destination buffers **and**
 *    stages the raw signal in [durableBuffer] — no I/O, must be fast.
 * 2. [onFlush]/[onStop] first [DurableSignalBuffer.checkpoint]s the staged
 *    signals to the WAL (durable), then, in a **single** transaction, inserts
 *    the buffered destination rows and deletes the exact WAL rows that backed
 *    them ([persistBufferedAndAcknowledge]). Either everything commits or
 *    everything rolls back and stays retryable.
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
 * - Corrupted WAL rows are logged and acknowledged inside the transaction so
 *   recovery always makes progress and never loops forever.
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
	private val cellSampleDao: CellSampleDao,
	private val wifiObservationDao: WifiObservationDao,
	private val pressureSampleDao: PressureSampleDao,
	private val stepIntervalDao: StepIntervalDao,
	private val activitySnapshotDao: ActivitySnapshotDao,
	private val pendingSignalDao: PendingSignalDao,
	private val durableBuffer: DurableSignalBuffer,
	private val transactor: TrackingPersistenceTransactor,
	private val errorCollector: PersistenceErrorCollector,
) : DurableSignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = PROCESSOR_ID,
		requiredTier = PolicyTier.AMBIENT,
		flushIntervalMs = FLUSH_INTERVAL_MS,
		priority = PRIORITY,
	)

	// --- Buffers (accessed only from onSignal which is single-threaded) ---
	private val locationBuffer = mutableListOf<LocationSample>()
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
	private var recoveryIncomplete = false
	private var commitStatusUnknown = false
	private val recoveryMutex = Mutex()
	private var pipelineActive = false

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
		recoverPendingSignals()
		pipelineActive = true
	}

	/**
	 * Buffer incoming signal data and stage it for durable checkpointing.
	 * No I/O — must be fast.
	 */
	override fun onSignal(signal: TrackingSignal) {
		if (!signal.hasPersistablePayload()) return
		bufferSignal(signal)
		durableBuffer.stage(signal)
	}

	private fun TrackingSignal.hasPersistablePayload(): Boolean =
		location != null ||
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
		recoverPendingSignals()
		!recoveryIncomplete && !durableBuffer.hasPendingEntries()
	}

	override suspend fun checkpointStagedSignals(): Boolean {
		try {
			withDatabaseRetry {
				durableBuffer.checkpoint { ids -> pendingIds.addAll(ids) }
			}
		} catch (e: CancellationException) {
			throw e
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			Log.w(TAG, "Durable checkpoint failed; staged signals retained", e)
			errorCollector.reportError(
				PersistenceError(
					source = PROCESSOR_ID,
					operation = "checkpoint",
					recordCount = durableBuffer.stagingSize,
					cause = e,
				),
			)
			return false
		}
		return true
	}

	override fun checkpoint(): ByteArray? = null

	override fun restore(state: ByteArray) {
		// No checkpoint support yet
	}

	/** Parse [signal] into the typed destination buffers (no staging, no I/O). */
	private fun bufferSignal(signal: TrackingSignal) {
		val location = signal.location
		val policyName = signal.policy?.policyName

		bufferLocation(signal, location, policyName)
		bufferCells(signal, location)
		bufferWifi(signal, location)
		bufferPressure(signal)
		bufferSteps(signal)
		bufferActivity(signal)
	}

	// -----------------------------------------------------------------------
	// Buffering (no I/O)
	// -----------------------------------------------------------------------

	private fun bufferLocation(
		signal: TrackingSignal,
		location: LocationSignal?,
		policyName: String?,
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
				hAccM = location.horizontalAccuracyM,
				vAccM = location.verticalAccuracyM,
				speedMps = location.speed?.raw,
				speedAccuracyMps = location.speedAccuracyMps,
				provider = location.provider,
				quality = quality,
				motionState = motionState,
				policy = policyName,
				bucketId = null,
				createdAt = Time.nowMillis,
			),
		)
	}

	private fun bufferCells(signal: TrackingSignal, location: LocationSignal?) {
		val cells = signal.cells ?: return
		val timeMs = signal.timestampMs.raw
		val latE7 = location?.coordinate?.lat?.raw
		val lonE7 = location?.coordinate?.lon?.raw
		val provenance = if (location != null) CoordinateProvenance.DIRECT else CoordinateProvenance.UNKNOWN
		val now = Time.nowMillis

		for (tower in cells.towers) {
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
					createdAt = now,
				),
			)
		}
	}

	private fun bufferWifi(signal: TrackingSignal, location: LocationSignal?) {
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
		val now = Time.nowMillis

		for (network in wifi.networks) {
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
					createdAt = now,
				),
			)
		}
	}

	private fun bufferPressure(signal: TrackingSignal) {
		val pressure = signal.pressure ?: return
		pressureBuffer.add(
			PressureSample(
				timeMs = signal.timestampMs.raw,
				elapsedRealtimeNanos = signal.elapsedRealtimeNanos,
				pressureHpa = pressure.pressureHpa,
				altitudeM = pressure.altitudeM,
				bucketId = null,
				createdAt = Time.nowMillis,
			),
		)
	}

	private fun bufferSteps(signal: TrackingSignal) {
		val steps = signal.steps ?: return
		stepBuffer.add(
			StepInterval(
				startTimeMs = signal.timestampMs.raw,
				endTimeMs = signal.timestampMs.raw,
				stepCount = steps.stepDelta.raw,
				sensorValueStart = steps.sensorValueStart,
				sensorValueEnd = steps.sensorValueEnd,
				sensorReset = steps.sensorReset,
				createdAt = Time.nowMillis,
			),
		)
	}

	private fun bufferActivity(signal: TrackingSignal) {
		if (!signal.activityFresh) return
		val activity = signal.activity ?: return
		activityBuffer.add(
			ActivitySnapshot(
				timeMs = signal.timestampMs.raw,
				activityType = activity.type.ordinal,
				confidence = activity.confidence.raw,
				isTransition = false,
				createdAt = Time.nowMillis,
			),
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
		if (!checkpointStagedSignals()) return false

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
		val recordCount = totalBufferedCount()

		try {
			withTimeout(PERSISTENCE_TRANSACTION_TIMEOUT_MILLIS) {
				withDatabaseRetry {
					transactor.inTransaction {
						insertBufferedDestinations()
						if (ackIds.isNotEmpty()) {
							ackIds.chunked(ACK_DELETE_BATCH_SIZE).forEach { chunk ->
								pendingSignalDao.deleteByIds(chunk)
							}
						}
					}
				}
			}
			withContext(NonCancellable) {
				clearBuffers()
				pendingIds.removeAll(ackIds.toSet())
			}
		} catch (e: TimeoutCancellationException) {
			commitStatusUnknown = true
			if (reconcileUnknownCommit() == CommitResolution.COMMITTED) return true
			reportFlushFailure(recordCount, e)
			return false
		} catch (e: CancellationException) {
			commitStatusUnknown = true
			withContext(NonCancellable) {
				reconcileUnknownCommit()
			}
			throw e
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			commitStatusUnknown = true
			if (reconcileUnknownCommit() == CommitResolution.COMMITTED) return true
			reportFlushFailure(recordCount, e)
			return false
		}

		return true
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
			Log.w(TAG, "Timed out reconciling persistence commit status", e)
			return CommitResolution.UNKNOWN
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Unable to reconcile persistence commit status", e)
			return CommitResolution.UNKNOWN
		}

		return when (remainingCount) {
			0 -> {
				clearBuffers()
				pendingIds.removeAll(ackIds.toSet())
				commitStatusUnknown = false
				CommitResolution.COMMITTED
			}
			ackIds.size -> {
				commitStatusUnknown = false
				CommitResolution.ROLLED_BACK
			}
			else -> {
				Log.e(
					TAG,
					"Persistence commit status is inconsistent: $remainingCount of ${ackIds.size} WAL rows remain",
				)
				CommitResolution.UNKNOWN
			}
		}
	}

	private suspend fun reportFlushFailure(recordCount: Int, exception: Exception) {
		Log.w(TAG, "Persist transaction failed; buffers and WAL retained for retry", exception)
		errorCollector.reportError(
			PersistenceError(
				source = PROCESSOR_ID,
				operation = "flush",
				recordCount = recordCount,
				cause = exception,
			),
		)
	}

	/**
	 * Insert all buffered destination rows in [batch]-sized chunks. Runs inside
	 * a transaction, so any failure aborts the whole transaction (no per-table
	 * isolation — durability comes from the WAL retry instead).
	 */
	private suspend fun insertBufferedDestinations() {
		locationBuffer.chunked(LOCATION_BATCH_SIZE).forEach { chunk ->
			locationSampleDao.insert(chunk.map { it.toEntity() })
		}
		cellBuffer.chunked(CELL_BATCH_SIZE).forEach { chunk ->
			cellSampleDao.insert(chunk)
		}
		wifiBuffer.chunked(WIFI_BATCH_SIZE).forEach { chunk ->
			wifiObservationDao.insert(chunk)
		}
		pressureBuffer.chunked(PRESSURE_BATCH_SIZE).forEach { chunk ->
			pressureSampleDao.insert(chunk)
		}
		stepBuffer.chunked(STEP_BATCH_SIZE).forEach { chunk ->
			stepIntervalDao.insert(chunk)
		}
		activityBuffer.chunked(ACTIVITY_BATCH_SIZE).forEach { chunk ->
			activitySnapshotDao.insert(chunk)
		}
	}

	/**
	 * Replay WAL rows left over from a previous process. Rows are peeked
	 * **across all sessions** (a restart mints a new session id, so the prior
	 * session's rows must not be filtered out), read but not deleted, parsed
	 * into the typed buffers and persisted via the same transactional
	 * [persistBufferedAndAcknowledge]. Corrupted rows are logged and still
	 * acknowledged so recovery always makes progress; if the persist fails the
	 * loop stops (no infinite retry) and leaves the rows in the WAL for a later
	 * flush.
	 */
	private suspend fun recoverPendingSignals() {
		recoveryIncomplete = false
		while (true) {
			val peeked = durableBuffer.peekBatch(DurableSignalBuffer.RECOVERY_BATCH_SIZE)
			if (peeked.isEmpty()) break

			clearBuffers()
			pendingIds.clear()

			for (entry in peeked) {
				val signal = entry.signal
				if (signal == null) {
					Log.w(TAG, "Corrupted WAL entry id=${entry.id}; acknowledging to avoid replay loop")
				} else {
					bufferSignal(signal)
				}
			}

			// Acknowledge every peeked row (including corrupted ones) once the
			// destination writes commit.
			pendingIds.addAll(peeked.map { it.id })
			val persisted = persistBufferedAndAcknowledge()

			if (!persisted) {
				// Persist failed: WAL rows remain for a later retry. Stop now so
				// we do not spin re-reading the same un-acknowledged rows.
				recoveryIncomplete = true
				break
			}
		}
	}

	private fun hasRetainedInMemoryState(): Boolean =
		!isAllBuffersEmpty() || pendingIds.isNotEmpty() || durableBuffer.stagingSize > 0

	private fun isAllBuffersEmpty(): Boolean =
		locationBuffer.isEmpty() &&
			cellBuffer.isEmpty() &&
			wifiBuffer.isEmpty() &&
			pressureBuffer.isEmpty() &&
			stepBuffer.isEmpty() &&
			activityBuffer.isEmpty()

	private fun totalBufferedCount(): Int =
		locationBuffer.size +
			cellBuffer.size +
			wifiBuffer.size +
			pressureBuffer.size +
			stepBuffer.size +
			activityBuffer.size

	private fun clearBuffers() {
		locationBuffer.clear()
		cellBuffer.clear()
		wifiBuffer.clear()
		pressureBuffer.clear()
		stepBuffer.clear()
		activityBuffer.clear()
	}

	companion object {
		private const val TAG = "PersistenceProcessor"
		internal const val PROCESSOR_ID = "persistence"
		internal const val FLUSH_INTERVAL_MS = 5000L
		internal const val PRIORITY = 0
		private const val PERSISTENCE_TRANSACTION_TIMEOUT_MILLIS = 3_000L
		private const val PERSISTENCE_RECONCILIATION_TIMEOUT_MILLIS = 2_000L

		internal const val LOCATION_BATCH_SIZE = 10
		internal const val PRESSURE_BATCH_SIZE = 5
		internal const val CELL_BATCH_SIZE = 50
		internal const val WIFI_BATCH_SIZE = 50
		internal const val STEP_BATCH_SIZE = 20
		internal const val ACTIVITY_BATCH_SIZE = 20
		internal const val ACK_DELETE_BATCH_SIZE = 900

		private const val HIGH_ACCURACY_THRESHOLD = 10f
		private const val MEDIUM_ACCURACY_THRESHOLD = 50f

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
