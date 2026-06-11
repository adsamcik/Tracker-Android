package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.stats.api.PolicyTier
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
) : PipelineStage {
	private companion object {
		const val TAG = "SignalDispatchStage"
	}

	override val name: String = "SignalDispatch"

	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		val pipeline = processorPipelineProvider() ?: return StageResult.Continue

		val signal = try {
			val cycle = cycleContext.cycle
			val collectionData = cycleContext.collectionData

			val cellTowers = cycle.cellScan?.registeredCells?.map { cell ->
				com.adsamcik.tracker.stats.api.signal.CellTowerReading(
					cellId = cell.cellId,
					mcc = cell.networkOperator.mcc,
					mnc = cell.networkOperator.mnc,
					networkType = cell.type.ordinal,
					signalStrength = cell.asu,
				)
			}

			val wifiNetworks = cycle.wifiScan?.data?.map { sr ->
				com.adsamcik.tracker.stats.api.signal.WifiNetworkReading(
					bssid = sr.BSSID ?: "",
					ssid = sr.SSID ?: "",
					capabilities = sr.capabilities ?: "",
					frequency = sr.frequency,
					level = sr.level,
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
				rawGpsAltitude = cycle.rawGpsAltitude?.toFloat(),
				activityTypeCode = collectionData.activity?.activityType,
				activityConfidence = collectionData.activity?.confidence,
				stepDelta = cycle.stepDelta,
				totalStepsSinceBoot = cycle.totalStepsSinceBoot,
				stepSensorValueStart = cycle.stepSensorValueStart,
				stepSensorValueEnd = cycle.stepSensorValueEnd,
				stepSensorReset = cycle.stepSensorReset,
				cellTowers = cellTowers,
				wifiNetworks = wifiNetworks,
				pressureHpa = cycle.pressure?.pressureHpa,
				pressureAltitudeM = cycle.pressure?.altitudeM,
				policyTier = currentTierProvider(),
			)
			signal
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Failed to build tracking signal; skipping dispatch", e)
			return StageResult.Continue
		}

		cycleContext.signal = signal
		pipeline.onSignal(signal)

		return StageResult.Continue
	}
}
