package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import android.util.Log
import androidx.core.location.LocationCompat
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.LocationDecision
import com.adsamcik.tracker.stats.api.signal.LocationDecisionSignal
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.TrackingClockDomain
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
			val processedAltitude = collectionData.processedAltitude
			val rawLocation = cycle.location?.lastLocation
			val locationMetadata = cycle.location?.lastFixMetadata
			val sourceEventId = locationMetadata?.sourceEventId
			// Keep the decision boundary identical to SignalAdapter's LocationSignal construction.
			// A partial curated object is not an accepted persisted sample.
			val locationAccepted = collectionData.location?.horizontalAccuracy != null

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
				speed = collectionData.estimatedSpeedMps,
				rawPlatformSpeed = rawLocation?.speed?.takeIf { rawLocation.hasSpeed() },
				rawPlatformSpeedAccuracy = rawLocation?.speedAccuracyMetersPerSecond?.takeIf {
					rawLocation.hasSpeedAccuracy()
				},
				bearingDeg = rawLocation?.bearing?.takeIf { rawLocation.hasBearing() },
				bearingAccuracyDeg = rawLocation?.bearingAccuracyDegrees?.takeIf {
					rawLocation.hasBearingAccuracy()
				},
				altitude = processedAltitude?.altitudeM,
				rawGpsAltitude = collectionData.rawGpsAltitudeM,
				altitudeDatum = processedAltitude?.datum
					?: com.adsamcik.tracker.shared.model.AltitudeDatum.UNKNOWN_LEGACY,
				altitudeSource = processedAltitude?.source
					?: com.adsamcik.tracker.shared.model.AltitudeSource.UNKNOWN_LEGACY,
				altitudeConversionStatus = processedAltitude?.conversionStatus
					?: com.adsamcik.tracker.shared.model.AltitudeConversionStatus.UNKNOWN_LEGACY,
				rawGpsAltitudeDatum = processedAltitude?.rawAltitudeDatum
					?: com.adsamcik.tracker.shared.model.AltitudeDatum.UNKNOWN_LEGACY,
				altitudeModelVersion = processedAltitude?.modelVersion ?: 0,
				altitudeEstimatorVersion = processedAltitude?.estimatorVersion ?: 0,
				altitudeCalibrationVersion = processedAltitude?.calibrationVersion ?: 0,
				verticalAccuracy = collectionData.location?.verticalAccuracy,
				// The legacy curated speed is derived/EMA-smoothed and has no calibrated
				// uncertainty yet. Provider uncertainty is carried separately above.
				speedAccuracy = null,
				distanceDelta = collectionData.distanceFromPreviousM,
				provider = rawLocation?.provider ?: "unknown",
				receivedElapsedRealtimeNanos = locationMetadata?.receivedElapsedRealtimeNanos ?: 0L,
				acquisitionMode = locationMetadata?.acquisitionMode?.name ?: "UNKNOWN",
				requestPriority = locationMetadata?.requestPriority?.name ?: "UNKNOWN",
				permissionPrecision = locationMetadata?.permissionPrecision?.name ?: "UNKNOWN",
				batchIndex = locationMetadata?.batchIndex ?: 0,
				batchSize = locationMetadata?.batchSize ?: 1,
				isMock = rawLocation?.let(LocationCompat::isMock) ?: false,
				sourceEventId = sourceEventId,
				clockDomainId = locationMetadata?.clockDomainId ?: TrackingClockDomain.currentId(),
				bootClockDomainId = locationMetadata?.bootClockDomainId
					?: TrackingClockDomain.currentBootId(),
				locationDecision = sourceEventId?.let { eventId ->
					LocationDecisionSignal(
						sourceEventId = eventId,
						decision = if (locationAccepted) {
							LocationDecision.ACCEPTED
						} else {
							LocationDecision.REJECTED
						},
						reason = if (locationAccepted) null else "CURATED_LOCATION_REJECTED",
					)
				},
				activityTypeCode = effectiveActivity?.activityType,
				activityConfidence = effectiveActivity?.confidence,
				activityFresh = cycle.activityFresh ||
					effectiveActivityChanged(cycle, effectiveActivity),
				activitySourceElapsedRealtimeNanos =
					cycle.activitySourceElapsedRealtimeNanos,
				activitySourceSequence = cycle.activitySourceSequence,
				stepDelta = cycle.stepDelta,
				totalStepsSinceBoot = cycle.totalStepsSinceBoot,
				stepSensorValueStart = cycle.stepSensorValueStart,
				stepSensorValueEnd = cycle.stepSensorValueEnd,
				stepSensorReset = cycle.stepSensorReset,
				stepWindowStartElapsedRealtimeNanos =
					cycle.stepWindowStartElapsedRealtimeNanos,
				stepWindowEndElapsedRealtimeNanos =
					cycle.stepWindowEndElapsedRealtimeNanos,
				stepSourceFirstSequence = cycle.stepSourceFirstSequence,
				stepSourceLastSequence = cycle.stepSourceLastSequence,
				cellTowers = cellTowers,
				cellObservedAtMs = cycle.cellScan?.observedAtEpochMs,
				cellObservedElapsedRealtimeNanos =
					cycle.cellScan?.observedAtElapsedRealtimeNanos,
				cellSourceSequence = cycle.cellScan?.sourceSequence,
				wifiNetworks = wifiNetworks,
				pressureHpa = cycle.pressure?.pressureHpa,
				wifiTimestampMs = collectionData.wifi?.time,
				wifiElapsedRealtimeNanos = cycle.wifiScan?.relativeTimeNanos,
				wifiSourceSequence = cycle.wifiScan?.sourceSequence,
				wifiLatitude = collectionData.wifi?.location?.latitude,
				wifiLongitude = collectionData.wifi?.location?.longitude,
				wifiCoordinateProvenance = if (collectionData.wifi?.location != null) {
					com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance.INTERPOLATED
				} else {
					com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance.UNKNOWN
				},
				pressureAltitudeM = cycle.pressure?.altitudeM,
				pressureSampleCount = cycle.pressure?.sampleCount ?: 1,
				pressureMinHpa = cycle.pressure?.minPressureHpa,
				pressureMaxHpa = cycle.pressure?.maxPressureHpa,
				pressureStandardDeviationHpa =
					cycle.pressure?.standardDeviationHpa ?: 0f,
				pressureWindowStartElapsedRealtimeNanos =
					cycle.pressure?.windowStartElapsedRealtimeNanos,
				pressureWindowEndElapsedRealtimeNanos =
					cycle.pressure?.windowEndElapsedRealtimeNanos,
				pressureSourceFirstSequence = cycle.pressure?.sourceFirstSequence,
				pressureSourceLastSequence = cycle.pressure?.sourceLastSequence,
				policyTier = currentTierProvider(),
				policyName = currentPolicyNameProvider(),
				persistenceSignalId = cycle.persistenceSignalId,
			)
			signal
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Failed to build tracking signal; skipping dispatch", e)
			return StageResult.Continue
		}

		cycleContext.signal = signal
		val admitted = pipeline.onSignal(signal) {
			lastEffectiveActivity = effectiveActivity
		}
		return if (admitted) {
			StageResult.Continue
		} else {
			// Do not advance downstream SignalProcessor state or the activity
			// acknowledgement until the persistence processor has committed the
			// pending-signal admission record. A storage failure remains staged for
			// retry; a lifecycle rejection is a terminal, intentionally unforwarded
			// discard.
			StageResult.Skip("durable signal admission failed")
		}
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
