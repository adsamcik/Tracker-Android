package com.adsamcik.tracker.tracker.pipeline.persistence

import android.util.Log
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.signal.WifiSignal
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.withDatabaseRetry
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Unified persistence processor that writes all tracking data to Room.
 *
 * [onSignal] only buffers data — no I/O. [onFlush] writes buffered data
 * to Room DAOs with per-table error isolation. A failure in one table
 * (e.g. cell) does not lose data for another (e.g. wifi).
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
class PersistenceProcessor @Inject constructor(
	private val locationSampleDao: LocationSampleDao,
	private val cellSampleDao: CellSampleDao,
	private val wifiObservationDao: WifiObservationDao,
	private val pressureSampleDao: PressureSampleDao,
	private val stepIntervalDao: StepIntervalDao,
	private val activitySnapshotDao: ActivitySnapshotDao,
	private val errorCollector: PersistenceErrorCollector,
) : SignalProcessor {

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

	override suspend fun onStart(context: ProcessorContext) {
		clearBuffers()
	}

	/**
	 * Buffer incoming signal data. No I/O — must be fast.
	 */
	override fun onSignal(signal: TrackingSignal) {
		val location = signal.location
		val policyName = signal.policy?.policyName

		bufferLocation(signal, location, policyName)
		bufferCells(signal, location)
		bufferWifi(signal, location)
		bufferPressure(signal)
		bufferSteps(signal)
		bufferActivity(signal)
	}

	override suspend fun onFlush(): List<DomainEvent> {
		flushAll()
		return emptyList()
	}

	override suspend fun onStop(): List<DomainEvent> {
		flushAll()
		return emptyList()
	}

	override fun checkpoint(): ByteArray? = null

	override fun restore(state: ByteArray) {
		// No checkpoint support yet
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

		val quality = classifyQuality(location.horizontalAccuracyM)
		val motionState = inferMotionState(signal.activity)

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
					lac = 0,
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
		val timeMs = signal.timestampMs.raw
		val latE7 = location?.coordinate?.lat?.raw
		val lonE7 = location?.coordinate?.lon?.raw
		val provenance = if (location != null) CoordinateProvenance.DIRECT else CoordinateProvenance.UNKNOWN
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
	// Flushing (I/O, per-table error isolation)
	// -----------------------------------------------------------------------

	private suspend fun flushAll() {
		flushTable("location_sample", locationBuffer, LOCATION_BATCH_SIZE) { batch ->
			withDatabaseRetry { locationSampleDao.insert(batch) }
		}
		flushTable("cell_sample", cellBuffer, CELL_BATCH_SIZE) { batch ->
			withDatabaseRetry { cellSampleDao.insert(batch) }
		}
		flushTable("wifi_observation", wifiBuffer, WIFI_BATCH_SIZE) { batch ->
			withDatabaseRetry { wifiObservationDao.insert(batch) }
		}
		flushTable("pressure_sample", pressureBuffer, PRESSURE_BATCH_SIZE) { batch ->
			withDatabaseRetry { pressureSampleDao.insert(batch) }
		}
		flushTable("step_interval", stepBuffer, STEP_BATCH_SIZE) { batch ->
			withDatabaseRetry { stepIntervalDao.insert(batch) }
		}
		flushTable("activity_snapshot", activityBuffer, ACTIVITY_BATCH_SIZE) { batch ->
			withDatabaseRetry { activitySnapshotDao.insert(batch) }
		}
	}

	/**
	 * Drains [buffer] in chunks of [batchSize], calling [insert] for each chunk.
	 *
	 * Each chunk is written independently so a failure in chunk N does not
	 * prevent chunks N+1…M from being persisted. On [CancellationException]
	 * the current chunk plus all remaining chunks are put back into [buffer]
	 * so they survive a coroutine cancellation (e.g. service shutdown timeout).
	 */
	private suspend fun <T> flushTable(
		tableName: String,
		buffer: MutableList<T>,
		@Suppress("SameParameterValue") batchSize: Int,
		insert: suspend (List<T>) -> Unit,
	) {
		if (buffer.isEmpty()) return

		val snapshot = buffer.toList()
		buffer.clear()

		val chunks = snapshot.chunked(batchSize)
		for ((index, chunk) in chunks.withIndex()) {
			try {
				insert(chunk)
			} catch (e: CancellationException) {
				// Put back this chunk + all remaining chunks
				val remaining = chunks.subList(index, chunks.size).flatten()
				buffer.addAll(0, remaining)
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Flush failed for $tableName chunk $index (${chunk.size} records)", e)
				errorCollector.reportError(
					PersistenceError(
						source = PROCESSOR_ID,
						operation = "flush $tableName chunk $index",
						recordCount = chunk.size,
						cause = e,
					),
				)
			}
		}
	}

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

		internal const val LOCATION_BATCH_SIZE = 10
		internal const val PRESSURE_BATCH_SIZE = 5
		internal const val CELL_BATCH_SIZE = 50
		internal const val WIFI_BATCH_SIZE = 50
		internal const val STEP_BATCH_SIZE = 20
		internal const val ACTIVITY_BATCH_SIZE = 20

		private const val HIGH_ACCURACY_THRESHOLD = 10f
		private const val MEDIUM_ACCURACY_THRESHOLD = 50f

		/**
		 * Classify location quality from horizontal accuracy.
		 */
		internal fun classifyQuality(horizontalAccuracyM: Float?): SampleQuality = when {
			horizontalAccuracyM == null -> SampleQuality.COARSE
			horizontalAccuracyM < HIGH_ACCURACY_THRESHOLD -> SampleQuality.HIGH
			horizontalAccuracyM < MEDIUM_ACCURACY_THRESHOLD -> SampleQuality.MEDIUM
			else -> SampleQuality.LOW
		}

		/**
		 * Infer motion state from activity recognition.
		 */
		internal fun inferMotionState(activity: ActivitySignal?): MotionState? {
			if (activity == null) return null
			return when (activity.type) {
				DetectedActivityType.STILL -> MotionState.STILL
				DetectedActivityType.WALKING,
				DetectedActivityType.RUNNING,
				DetectedActivityType.ON_FOOT,
				DetectedActivityType.IN_VEHICLE,
				DetectedActivityType.ON_BICYCLE,
				-> MotionState.MOVING

				else -> MotionState.UNKNOWN
			}
		}
	}
}
