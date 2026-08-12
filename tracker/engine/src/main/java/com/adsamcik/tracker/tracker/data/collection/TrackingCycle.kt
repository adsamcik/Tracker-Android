package com.adsamcik.tracker.tracker.data.collection

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.LocationProviderObservation
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.WifiNetworkReading
import java.util.UUID

/**
 * Typed envelope for one collection cycle's worth of sensor data.
 * Immutable — constructed from [TrackingCycleBuilder] after all producers finish.
 */
internal data class TrackingCycle(
	val timestampMs: Long,
	val elapsedRealtimeNanos: Long,
	val activity: ActivityInfo? = null,
	val activityFresh: Boolean = false,
	val activitySourceElapsedRealtimeNanos: Long? = null,
	val activitySourceSequence: Long? = null,
	val location: LocationData? = null,
	/** Lossless provider deliveries captured before trigger and pipeline rejection. */
	val locationObservations: List<LocationProviderObservation> = emptyList(),
	val cellScan: CellScanData? = null,
	val cellScanFresh: Boolean = false,
	val wifiScan: WifiScanData? = null,
	/** Normalized event-owned cell evidence that no longer has an Android CellInfo wrapper. */
	val normalizedCellScan: NormalizedCellScanData? = null,
	/** Normalized event-owned Wi-Fi evidence that no longer has an Android ScanResult wrapper. */
	val normalizedWifiScan: NormalizedWifiScanData? = null,
	val stepDelta: Int? = null,
	val totalStepsSinceBoot: Long? = null,
	val stepSensorValueStart: Int = 0,
	val stepSensorValueEnd: Int = 0,
	val stepSensorReset: Boolean = false,
	val stepWindowStartElapsedRealtimeNanos: Long? = null,
	val stepWindowEndElapsedRealtimeNanos: Long? = null,
	val stepSourceFirstSequence: Long? = null,
	val stepSourceLastSequence: Long? = null,
	val pressure: PressureReading? = null,
	val rawGpsAltitude: Double? = null,
	/**
	 * Stable identity assigned while this collection unit is still owned by the
	 * cycle queue. It is propagated to [com.adsamcik.tracker.stats.api.signal.TrackingSignal]
	 * and becomes the pending-signal idempotency key.
	 */
	val persistenceSignalId: String = UUID.randomUUID().toString(),
)

/**
 * Whether a producer supplied new data that still needs to pass through the tracking pipeline.
 *
 * This is intentionally stricter than checking whether a cached snapshot is present. Cell and
 * activity producers keep their latest value in the cycle for context, but only fresh snapshots
 * are persisted. Wi-Fi producers already de-duplicate cached scans before attaching them.
 */
internal fun TrackingCycle.hasPersistableProducerPayload(): Boolean =
	(activityFresh && activity != null) ||
		(cellScanFresh && cellScan != null) ||
		normalizedCellScan?.towers?.isNotEmpty() == true ||
		wifiScan != null ||
		normalizedWifiScan?.networks?.isNotEmpty() == true ||
		(stepDelta != null && (stepDelta > 0 || stepSensorReset)) ||
		pressure != null

/** True for a provider callback containing only rejected raw fixes. */
internal fun TrackingCycle.isLocationObservationOnly(): Boolean =
	locationObservations.isNotEmpty() &&
		location == null &&
		activity == null &&
		cellScan == null &&
		wifiScan == null &&
		stepDelta == null &&
		pressure == null

internal data class NormalizedCellScanData(
	val towers: List<CellTowerReading>,
	val observedAtMs: Long,
	val observedElapsedRealtimeNanos: Long,
	val sourceSequence: Long,
)

internal data class NormalizedWifiScanData(
	val networks: List<WifiNetworkReading>,
	val observedAtMs: Long,
	val observedElapsedRealtimeNanos: Long,
	val sourceSequence: Long,
)
