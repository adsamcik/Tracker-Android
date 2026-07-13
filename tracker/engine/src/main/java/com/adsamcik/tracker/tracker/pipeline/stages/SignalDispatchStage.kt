package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.PipelineStage
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.SignalAdapter
import com.adsamcik.tracker.tracker.pipeline.StageResult
import kotlinx.coroutines.CancellationException

/**
 * Builds a [com.adsamcik.tracker.stats.api.signal.TrackingSignal] from cycle and
 * collection data, then dispatches it through the [ProcessorPipeline].
 */
internal class SignalDispatchStage(
	private val processorPipelineProvider: () -> ProcessorPipeline?,
	private val currentTierProvider: () -> PolicyTier,
	private val currentPolicyNameProvider: () -> String? = { null },
) : PipelineStage {
	private var lastEffectiveActivity: ActivityInfo? = null

	private companion object {
		const val TAG = "SignalDispatchStage"
	}

	override val name: String = "SignalDispatch"

	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		val pipeline = processorPipelineProvider() ?: return StageResult.Continue
		val effectiveActivity = cycleContext.collectionData.activity

		val signal = try {
			val cycle = cycleContext.cycle
			val collectionData = cycleContext.collectionData
			val rawLocation = cycle.location?.lastLocation

			val cellTowers = cycle.cellScan
				?.takeIf { cycle.cellScanFresh }
				?.registeredCells
				?.map { cell ->
				com.adsamcik.tracker.stats.api.signal.CellTowerReading(
					cellId = cell.cellId,
					mcc = cell.networkOperator.mcc,
					mnc = cell.networkOperator.mnc,
					networkType = cell.type.ordinal,
					signalStrength = cell.asu,
					areaCode = cell.areaCode,
				)
			}

			val wifiNetworks = collectionData.wifi?.inRange?.map { network ->
				com.adsamcik.tracker.stats.api.signal.WifiNetworkReading(
					bssid = network.bssid,
					ssid = network.ssid.orEmpty(),
					capabilities = network.capabilities,
					frequency = network.frequency,
					level = network.level,
				)
			}

			val signal = SignalAdapter.buildSignal(
				timestampMs = cycle.timestampMs,
				elapsedRealtimeNanos = cycle.elapsedRealtimeNanos,
				latitude = collectionData.location?.latitude,
				longitude = collectionData.location?.longitude,
				accuracy = collectionData.location?.horizontalAccuracy,
				speed = collectionData.location?.speed,
				altitude = collectionData.location?.altitude?.toFloat(),
				rawGpsAltitude = collectionData.rawGpsAltitudeM,
				verticalAccuracy = collectionData.location?.verticalAccuracy,
				speedAccuracy = collectionData.location?.speedAccuracy,
				distanceDelta = collectionData.distanceFromPreviousM,
				provider = rawLocation?.provider ?: "unknown",
				activityTypeCode = effectiveActivity?.activityType,
				activityConfidence = effectiveActivity?.confidence,
				activityFresh = cycle.activityFresh ||
					effectiveActivityChanged(cycle, effectiveActivity),
				stepDelta = cycle.stepDelta,
				totalStepsSinceBoot = cycle.totalStepsSinceBoot,
				stepSensorValueStart = cycle.stepSensorValueStart,
				stepSensorValueEnd = cycle.stepSensorValueEnd,
				stepSensorReset = cycle.stepSensorReset,
				cellTowers = cellTowers,
				wifiNetworks = wifiNetworks,
				pressureHpa = cycle.pressure?.pressureHpa,
				wifiTimestampMs = collectionData.wifi?.time,
				wifiLatitude = collectionData.wifi?.location?.latitude,
				wifiLongitude = collectionData.wifi?.location?.longitude,
				wifiCoordinateProvenance = if (collectionData.wifi?.location != null) {
					com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance.INTERPOLATED
				} else {
					com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance.UNKNOWN
				},
				pressureAltitudeM = cycle.pressure?.altitudeM,
				policyTier = currentTierProvider(),
				policyName = currentPolicyNameProvider(),
			)
			signal
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Failed to build tracking signal; skipping dispatch", e)
			return StageResult.Continue
		}

		cycleContext.signal = signal
		pipeline.onSignal(signal) {
			lastEffectiveActivity = effectiveActivity
		}
		return StageResult.Continue
	}

	private fun effectiveActivityChanged(
		cycle: TrackingCycle,
		activity: ActivityInfo?,
	): Boolean {
		if (activity == null) return false
		val previous = lastEffectiveActivity
		return if (previous == null) {
			activity != cycle.activity
		} else {
			activity != previous
		}
	}
}
