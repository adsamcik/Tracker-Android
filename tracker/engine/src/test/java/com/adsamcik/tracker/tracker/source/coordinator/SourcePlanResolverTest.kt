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
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
			emptyList(),
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
		(resolved.applicablePlans.getValue(SourceKind.PRESSURE) as PressurePlan).enabled shouldBe false
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
			requestedIntervalMs = 5_000,
			minimumUpdateIntervalMs = 1_000,
			minimumDisplacementMeters = 1f,
			maximumBatchDelayMs = 10_000,
			preciseLocationAvailable = true,
		)
		val wifi = WifiPlan(
			revision = revision,
			mode = WifiMode.ACTIVE_ATTEMPTS,
			minimumAttemptIntervalMs = 60_000,
			maximumAcceptableResultAgeMs = 120_000,
			unchangedResultDedupeWindowMs = 30_000,
			backoff = RetryBackoff(1_000, 60_000),
		)
		return AcquisitionPlanRevision(revision, "test", 100, mapOf(location.source to location, wifi.source to wifi))
	}
}
