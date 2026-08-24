package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.control.CollectionMotionPolicy
import com.adsamcik.tracker.tracker.source.control.CollectionMotionState
import com.adsamcik.tracker.tracker.source.control.LocationCollectionStrategy
import com.adsamcik.tracker.tracker.source.control.MotionPolicyReason
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.LocationAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.LocationFloorMode
import com.adsamcik.tracker.tracker.source.model.PressureAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test

class SourcePlanResolverTest {
	@Test
	fun `permission loss disables only the affected source and remains explicit`() {
		val desired = plan()
		val resolved = SourcePlanResolver().resolve(
			desired = desired,
			demands = emptyList(),
			context = PlanResolutionContext(
				constraints = mapOf(SourceKind.LOCATION to SourceConstraint(permissionGranted = false)),
				powerSaver = false,
				doze = false,
				severeThermalPressure = false,
			),
		)

		(resolved.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan).mode shouldBe LocationMode.DISABLED
		(resolved.applicablePlans.getValue(SourceKind.WIFI) as WifiPlan).mode shouldBe WifiMode.ACTIVE_ATTEMPTS
		resolved.degradedReasons.getValue(SourceKind.LOCATION) shouldContain SourceDegradedReason.PERMISSION_MISSING
	}

	@Test
	fun `doze preserves requested Wi-Fi plan but marks active attempts deferred`() {
		val resolved = SourcePlanResolver().resolve(
			desired = plan(),
			demands = emptyList(),
			context = PlanResolutionContext(emptyMap(), powerSaver = false, doze = true, severeThermalPressure = false),
		)

		(resolved.applicablePlans.getValue(SourceKind.WIFI) as WifiPlan).mode shouldBe WifiMode.ACTIVE_ATTEMPTS
		resolved.degradedReasons.getValue(SourceKind.WIFI) shouldContain SourceDegradedReason.DOZE
	}

	@Test
	fun `power saver removes adaptive Wi-Fi active attempts but keeps broadcast acquisition`() {
		val desired = plan()
		val wifi = desired.plans.getValue(SourceKind.WIFI)
		val resolved = SourcePlanResolver().resolve(
			desired = desired,
			demands = listOf(directDemand(wifi, adaptiveReductionAllowed = true)),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = true,
				doze = false,
				severeThermalPressure = false,
			),
		)

		(resolved.applicablePlans.getValue(SourceKind.WIFI) as WifiPlan).mode shouldBe
			WifiMode.BROADCAST_DRIVEN
		resolved.degradedReasons.getValue(SourceKind.WIFI) shouldBe setOf(SourceDegradedReason.POWER_SAVER)
	}

	@Test
	fun `explicitly adaptive direct Pressure demand may reduce under severe thermal pressure`() {
		val desired = planWithPressure()
		val pressureTarget = desired.plans.getValue(SourceKind.PRESSURE)
		val resolved = SourcePlanResolver().resolve(
			desired = desired,
			demands = listOf(
				directDemand(pressureTarget, adaptiveReductionAllowed = true),
			),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = true,
			),
		)

		(resolved.applicablePlans.getValue(SourceKind.PRESSURE) as PressurePlan).let { pressure ->
			pressure.enabled shouldBe true
			pressure.hardwareSamplePeriodMicros shouldBe 1_000_000
			pressure.maximumReportLatencyMicros shouldBe 60_000_000
			pressure.aggregationWindowMs shouldBe 60_000L
			pressure.movementGatedBurst shouldBe true
		}
		resolved.degradedReasons.getValue(SourceKind.PRESSURE) shouldBe setOf(SourceDegradedReason.THERMAL)
		resolved.fullyApplicable shouldBe false
	}

	@Test
	fun `adaptive Pressure reduction never crosses its declared mode floor`() {
		val desired = planWithPressure()
		val highRateFloor = desired.plans.getValue(SourceKind.PRESSURE) as PressurePlan
		val contract = SourceDemandContract(
			floor = PressureAcquisitionFloor(
				maximumSamplePeriodMicros = highRateFloor.hardwareSamplePeriodMicros,
				maximumReportLatencyMicros = highRateFloor.maximumReportLatencyMicros,
				maximumAggregationWindowMs = highRateFloor.aggregationWindowMs,
			),
			maximumProviderItemAgeMs = highRateFloor.aggregationWindowMs,
			targetPlanningLatencyMs = 1_000L,
			requestedDeliveryLatencyMs = 1_000L,
			adaptiveReductionAllowed = true,
		)
		val resolved = SourcePlanResolver().resolve(
			desired = desired,
			demands = listOf(directDemand(contract)),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = true,
			),
		)

		resolved.applicablePlans.getValue(SourceKind.PRESSURE) shouldBe highRateFloor
		resolved.degradedReasons.getValue(SourceKind.PRESSURE) shouldBe emptySet()
	}

	@Test
	fun `adaptive Location reduction stops at the floor across plan revisions`() {
		val desired = plan()
		val balancedFloor = SourceDemandContract(
			floor = LocationAcquisitionFloor(LocationFloorMode.BALANCED),
			maximumProviderItemAgeMs = 120_000L,
			targetPlanningLatencyMs = 1_000L,
			requestedDeliveryLatencyMs = 30_000L,
			adaptiveReductionAllowed = true,
		)
		val resolved = SourcePlanResolver().resolve(
			desired = desired,
			demands = listOf(directDemand(balancedFloor)),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = false,
				motionProfile = stationaryPolicy().acquisitionProfile,
			),
		)

		(resolved.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan).let { location ->
			location.mode shouldBe LocationMode.BALANCED
			location.requestedIntervalMs shouldBe 30_000L
			location.minimumUpdateIntervalMs shouldBe 15_000L
			location.maximumBatchDelayMs shouldBe 30_000L
		}
		resolved.degradedReasons.getValue(SourceKind.LOCATION) shouldBe
			setOf(SourceDegradedReason.POWER_SAVER)
	}

	@Test
	fun `captured responsive Activity does not substitute control-only transitions`() {
		val base = plan()
		val activity = ActivityPlan(
			revision = base.revision,
			mode = ActivityMode.CONTINUOUS_RECOGNITION,
			desiredDetectionLatencyMs = 5_000L,
			confidenceThresholdPercent = 55,
			transitionTypes = setOf(0, 1),
		)
		val desired = base.copy(plans = base.plans + (SourceKind.ACTIVITY to activity))
		val resolved = SourcePlanResolver().resolve(
			desired = desired,
			demands = listOf(directDemand(activity, adaptiveReductionAllowed = true)),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = false,
				motionProfile = stationaryPolicy().acquisitionProfile,
			),
		)

		resolved.applicablePlans.getValue(SourceKind.ACTIVITY) shouldBe activity
		resolved.degradedReasons.getValue(SourceKind.ACTIVITY) shouldBe emptySet()
	}

	@Test
	fun `hard prerequisite block still disables directly requested Pressure`() {
		val desired = planWithPressure()
		val pressureFloor = desired.plans.getValue(SourceKind.PRESSURE) as PressurePlan
		val resolved = SourcePlanResolver().resolve(
			desired = desired,
			demands = listOf(
				directDemand(pressureFloor),
			),
			context = PlanResolutionContext(
				constraints = mapOf(SourceKind.PRESSURE to SourceConstraint(hardwareAvailable = false)),
				powerSaver = false,
				doze = false,
				severeThermalPressure = true,
				motionProfile = stationaryPolicy().acquisitionProfile,
			),
		)

		(resolved.applicablePlans.getValue(SourceKind.PRESSURE) as PressurePlan).enabled shouldBe false
		resolved.degradedReasons.getValue(SourceKind.PRESSURE) shouldBe
			setOf(
				SourceDegradedReason.HARDWARE_UNAVAILABLE,
				SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED,
			)
	}

	@Test
	fun `non-adaptive direct floors survive thermal motion and power reductions`() {
		val pressureDesired = planWithPressure()
		val pressureFloor = pressureDesired.plans.getValue(SourceKind.PRESSURE) as PressurePlan
		val thermal = SourcePlanResolver().resolve(
			desired = pressureDesired,
			demands = listOf(directDemand(pressureFloor)),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = true,
			),
		)
		thermal.applicablePlans.getValue(SourceKind.PRESSURE) shouldBe pressureFloor
		thermal.degradedReasons.getValue(SourceKind.PRESSURE) shouldBe emptySet()

		val activity = ActivityPlan(
			revision = pressureDesired.revision,
			mode = ActivityMode.CONTINUOUS_RECOGNITION,
			desiredDetectionLatencyMs = 10_000,
			confidenceThresholdPercent = 60,
			transitionTypes = setOf(0, 1),
		)
		val motionDesired = pressureDesired.copy(
			plans = pressureDesired.plans + (SourceKind.ACTIVITY to activity),
		)
		val motion = SourcePlanResolver().resolve(
			desired = motionDesired,
			demands = listOf(directDemand(pressureFloor)),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = false,
				motionProfile = stationaryPolicy().acquisitionProfile,
			),
		)
		motion.applicablePlans.getValue(SourceKind.PRESSURE) shouldBe pressureFloor
		motion.degradedReasons.getValue(SourceKind.PRESSURE) shouldBe emptySet()

		val locationDesired = plan()
		val locationFloor = locationDesired.plans.getValue(SourceKind.LOCATION) as LocationPlan
		val power = SourcePlanResolver().resolve(
			desired = locationDesired,
			demands = listOf(directDemand(locationFloor)),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = true,
				doze = false,
				severeThermalPressure = false,
			),
		)
		power.applicablePlans.getValue(SourceKind.LOCATION) shouldBe locationFloor
		power.degradedReasons.getValue(SourceKind.LOCATION) shouldBe emptySet()
	}

	@Test
	fun `direct floor above policy ceiling fails closed instead of running below the floor`() {
		val policyPlan = plan().let { base ->
			val location = base.plans.getValue(SourceKind.LOCATION) as LocationPlan
			base.copy(plans = base.plans + (SourceKind.LOCATION to location.copy(
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 60_000,
				minimumUpdateIntervalMs = 30_000,
				maximumBatchDelayMs = 60_000,
			)))
		}
		val policyLocation = policyPlan.plans.getValue(SourceKind.LOCATION) as LocationPlan
		val requestedFloor = SourceDemandContract(
			floor = LocationAcquisitionFloor(LocationFloorMode.HIGH_ACCURACY),
			maximumProviderItemAgeMs = 10_000L,
			targetPlanningLatencyMs = 1_000L,
			requestedDeliveryLatencyMs = 1_000L,
			adaptiveReductionAllowed = false,
		)

		val resolved = SourcePlanResolver().resolve(
			desired = policyPlan,
			demands = listOf(directDemand(requestedFloor)),
			context = PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = false,
			),
		)

		(resolved.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan).mode shouldBe
			LocationMode.DISABLED
		resolved.degradedReasons.getValue(SourceKind.LOCATION) shouldBe
			setOf(SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED)
	}

	@Test
	fun `strongest demand cannot exceed policy plan or re-enable disabled source`() {
		val desired = plan().copy(
			plans = plan().plans + (
				SourceKind.LOCATION to (plan().plans.getValue(SourceKind.LOCATION) as LocationPlan).copy(
					mode = LocationMode.BALANCED,
					requestedIntervalMs = 60_000,
					minimumUpdateIntervalMs = 30_000,
					maximumBatchDelayMs = 60_000,
				)
			),
		)
		val resolved = SourcePlanResolver().resolve(
			desired,
			listOf(
				SourceDemand(SourceKind.LOCATION, 5_000, 1_000, EvidenceQuality.HIGH, DemandReason.POLICY),
				SourceDemand(SourceKind.WIFI, 1_000, 1_000, EvidenceQuality.HIGH, DemandReason.POLICY),
			),
			PlanResolutionContext(emptyMap(), powerSaver = false, doze = false, severeThermalPressure = false),
		)

		val location = resolved.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan
		location.mode shouldBe LocationMode.BALANCED
		location.requestedIntervalMs shouldBe 60_000L
		location.minimumUpdateIntervalMs shouldBe 30_000L

		val wifiOff = (desired.plans.getValue(SourceKind.WIFI) as WifiPlan).copy(mode = WifiMode.OFF)
		val offResolved = SourcePlanResolver().resolve(
			desired.copy(plans = desired.plans + (SourceKind.WIFI to wifiOff)),
			listOf(SourceDemand(SourceKind.WIFI, 1_000, 1_000, EvidenceQuality.HIGH, DemandReason.POLICY)),
			PlanResolutionContext(emptyMap(), powerSaver = false, doze = false, severeThermalPressure = false),
		)
		(offResolved.applicablePlans.getValue(SourceKind.WIFI) as WifiPlan).mode shouldBe WifiMode.OFF
	}

	@Test
	fun `strongest demands cannot upgrade any source above battery saver policy`() {
		val batterySaver = SourceCollectionSettings(
			location = SourceCollectionFrequency.BATTERY_SAVER,
			activity = SourceCollectionFrequency.BATTERY_SAVER,
			steps = SourceCollectionFrequency.BATTERY_SAVER,
			pressure = SourceCollectionFrequency.BATTERY_SAVER,
			wifi = SourceCollectionFrequency.BATTERY_SAVER,
			cell = SourceCollectionFrequency.BATTERY_SAVER,
		)
		val desired = SemanticAcquisitionPlanFactory().create(
			settings = TrackingParamsState(
				wifiEnabled = true,
				cellEnabled = true,
				sourceCollectionSettings = batterySaver,
			),
			revision = 3,
			createdAtMs = 100,
			environment = SourcePlanEnvironment(LocationBackend.FUSED, true, emptySet()),
		)
		val demands = SourceKind.entries.map { source ->
			SourceDemand(source, 1, 1, EvidenceQuality.HIGH, DemandReason.POLICY)
		}

		val resolved = SourcePlanResolver().resolve(
			desired,
			demands,
			PlanResolutionContext(emptyMap(), powerSaver = false, doze = false, severeThermalPressure = false),
		)

		resolved.applicablePlans shouldBe desired.plans
	}

	@Test
	fun `stationary policy makes location passive and suppresses active Wi-Fi when activity can wake`() {
		val desired = plan().let { base ->
			val activity = ActivityPlan(
				revision = base.revision,
				mode = ActivityMode.CONTINUOUS_RECOGNITION,
				desiredDetectionLatencyMs = 10_000,
				confidenceThresholdPercent = 60,
				transitionTypes = setOf(0, 1),
			)
			base.copy(plans = base.plans + mapOf(
				SourceKind.ACTIVITY to activity,
				SourceKind.STEPS to StepsPlan(base.revision, true, 5_000, 2_000, true),
				SourceKind.PRESSURE to PressurePlan(base.revision, true, 50_000, 1_000_000, 2_000, false),
				SourceKind.CELL to CellPlan(
					base.revision,
					CellMode.OBSERVE_AND_SPARSE_REFRESH,
					60_000,
					60_000,
					emptySet(),
					RetryBackoff(1_000, 60_000),
				),
			))
		}
		val resolved = SourcePlanResolver().resolve(
			desired,
			listOf(
				directDemand(
					desired.plans.getValue(SourceKind.PRESSURE),
					adaptiveReductionAllowed = true,
				),
			),
			PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = false,
				motionProfile = stationaryPolicy().acquisitionProfile,
			),
		)

		(resolved.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan).mode shouldBe LocationMode.PASSIVE
		(resolved.applicablePlans.getValue(SourceKind.ACTIVITY) as ActivityPlan).mode shouldBe ActivityMode.TRANSITIONS_ONLY
		(resolved.applicablePlans.getValue(SourceKind.WIFI) as WifiPlan).mode shouldBe WifiMode.BROADCAST_DRIVEN
		(resolved.applicablePlans.getValue(SourceKind.STEPS) as StepsPlan).let { steps ->
			steps.maximumReportLatencyMs shouldBe 120_000L
			steps.movementPolicyNeedsLowLatency shouldBe false
		}
		(resolved.applicablePlans.getValue(SourceKind.PRESSURE) as PressurePlan).let { pressure ->
			pressure.enabled shouldBe true
			pressure.hardwareSamplePeriodMicros shouldBe 1_000_000
			pressure.maximumReportLatencyMicros shouldBe 60_000_000
			pressure.aggregationWindowMs shouldBe 60_000L
			pressure.movementGatedBurst shouldBe true
		}
		resolved.degradedReasons.getValue(SourceKind.PRESSURE) shouldContain SourceDegradedReason.POWER_SAVER
		(resolved.applicablePlans.getValue(SourceKind.CELL) as CellPlan).mode shouldBe CellMode.OBSERVE_CHANGES
	}

	@Test
	fun `stationary policy retains a low power location sentinel without activity wake`() {
		val resolved = SourcePlanResolver().resolve(
			plan(),
			emptyList(),
			PlanResolutionContext(
				constraints = emptyMap(),
				powerSaver = false,
				doze = false,
				severeThermalPressure = false,
				motionProfile = stationaryPolicy().acquisitionProfile,
			),
		)

		(resolved.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan).mode shouldBe LocationMode.LOW_POWER
	}

	private fun stationaryPolicy() = CollectionMotionPolicy(
		motionState = CollectionMotionState.STATIONARY,
		confidencePercent = 90,
		reason = MotionPolicyReason.STATIONARY_CONFIRMED,
		locationStrategy = LocationCollectionStrategy.PASSIVE_WHILE_STATIONARY,
		expensiveNetworkScansAllowed = false,
		continuousPressureAllowed = false,
		lowLatencyStepReporting = false,
	)

	private fun plan(): AcquisitionPlanRevision {
		val revision = 2L
		val location = LocationPlan(
			revision = revision,
			backend = LocationBackend.FUSED,
			mode = LocationMode.HIGH_ACCURACY,
			requestedIntervalMs = 1_000,
			minimumUpdateIntervalMs = 500,
			minimumDisplacementMeters = 2f,
			maximumBatchDelayMs = 2_000,
			preciseLocationAvailable = true,
		)
		val wifi = WifiPlan(
			revision = revision,
			mode = WifiMode.ACTIVE_ATTEMPTS,
			minimumAttemptIntervalMs = 60_000,
			maximumAcceptableResultAgeMs = 60_000,
			unchangedResultDedupeWindowMs = 30_000,
			backoff = RetryBackoff(1_000, 60_000),
		)
		return AcquisitionPlanRevision(revision, "test", 100, mapOf(location.source to location, wifi.source to wifi))
	}

	private fun planWithPressure(): AcquisitionPlanRevision = plan().let { base ->
		val pressure = PressurePlan(base.revision, true, 50_000, 1_000_000, 2_000, false)
		base.copy(plans = base.plans + (SourceKind.PRESSURE to pressure))
	}

	private fun directDemand(
		plan: SourcePlan,
		adaptiveReductionAllowed: Boolean = false,
	): SourceDemand = directDemand(
		SourceDemandContractFactory.forQos(
			plan.source,
			3,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		).copy(adaptiveReductionAllowed = adaptiveReductionAllowed),
	)

	private fun directDemand(contract: SourceDemandContract) = SourceDemand(
		source = contract.source,
		maximumAgeMs = contract.maximumProviderItemAgeMs,
		desiredLatencyMs = contract.targetPlanningLatencyMs,
		quality = EvidenceQuality.HIGH,
		reason = DemandReason.SESSION,
		acquisitionFloor = contract.floor,
		requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
		adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
	)
}
