package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.battery.ImpactDriver
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackingSettingsStatusProviderTest {
	private val telemetry = TrackingCoordinatorTelemetry()
	private val subject = DefaultTrackingSettingsStatusProvider(
		SemanticAcquisitionPlanFactory(),
		SourcePlanResolver(),
		QualitativeBatteryImpactEstimator(),
		telemetry,
	)

	@Test
	fun `preview preserves requested intent while reporting permission block`() {
		val settings = TrackingParamsState(
			locationEnabled = true,
			sourceCollectionSettings = SourceCollectionSettings(
				location = SourceCollectionFrequency.RESPONSIVE,
			),
		)
		val preview = subject.preview(
			settings,
			TrackingSettingsPreviewEnvironment(
				planEnvironment = SourcePlanEnvironment(LocationBackend.FUSED, false, emptySet()),
				resolutionContext = PlanResolutionContext(
					constraints = SourceKind.entries.associateWith { source ->
						SourceConstraint(permissionGranted = source != SourceKind.LOCATION)
					},
					powerSaver = false,
					doze = false,
					severeThermalPressure = false,
				),
			),
		)

		val location = preview.sources.getValue(SourceKind.LOCATION)
		location.requestedFrequency shouldBe SourceCollectionFrequency.RESPONSIVE
		location.requestedMode shouldBe "HIGH_ACCURACY"
		location.effectiveMode shouldBe "DISABLED"
		location.state shouldBe EffectiveSourceState.BLOCKED
		location.reasonCodes shouldContain "PERMISSION_MISSING"
		preview.batteryEstimate.dominantDrivers shouldContain ImpactDriver.LOCATION
		preview.batteryEstimate.estimatedPercentPerHour shouldBe null
	}

	@Test
	fun `applied event revision replaces applying state`() {
		val settings = TrackingParamsState()
		val desired = SemanticAcquisitionPlanFactory().create(
			settings,
			revision = 7L,
			createdAtMs = 100L,
			environment = SourcePlanEnvironment(LocationBackend.FUSED, true, emptySet()),
		)
		val resolved = SourcePlanResolver().resolve(
			desired,
			emptyList(),
			PlanResolutionContext(
				constraints = SourceKind.entries.associateWith { SourceConstraint() },
				powerSaver = false,
				doze = false,
				severeThermalPressure = false,
			),
		)
		val rollout = TrackingRolloutState(
			revision = 3L,
			schemaVersion = 1,
			coordinatorMode = CoordinatorMode.EVENT,
			projectionMode = ProjectionMode.SHADOW_READ_ONLY,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.EVENT },
			semanticSettingsEnabled = true,
			batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
		)

		subject.publishResolved(settings, rollout, resolved)
		subject.runtimeStatus.value.sources.getValue(SourceKind.LOCATION).state shouldBe
			EffectiveSourceState.APPLYING
		subject.publishApplied(
			listOf(
				AppliedSourcePlan(
					desiredRevision = 7L,
					appliedRevision = 7L,
					source = SourceKind.LOCATION,
					sourceInstanceId = null,
					registrationGeneration = null,
					appliedAtElapsedRealtimeNanos = 1L,
					status = SourceApplyStatus.APPLIED,
				),
			),
		)

		subject.runtimeStatus.value.appliedRevision shouldBe 7L
		subject.runtimeStatus.value.sources.getValue(SourceKind.LOCATION).state shouldBe
			EffectiveSourceState.ACTIVE
	}

	@Test
	fun `coordinator telemetry is observable without payloads`() {
		telemetry.recordProjectionDrain(events = 4, durationNanos = 2_000L)
		telemetry.recordPlanRevision()
		telemetry.recordTrackingFrame(wakeLockNanos = 3_000L)
		telemetry.recordMotionPolicyChange(stationaryOptimized = true, fullFidelity = false)
		telemetry.recordMotionPolicyChange(stationaryOptimized = false, fullFidelity = true)

		subject.telemetry.value shouldBe TrackingCoordinatorMetrics(
			projectionDrainCount = 1L,
			projectedEventCount = 4L,
			projectionDrainNanos = 2_000L,
			planRevisionCount = 1L,
			trackingFrameCount = 1L,
			trackingFrameWakeLockNanos = 3_000L,
			motionPolicyChangeCount = 2L,
			stationaryOptimizationCount = 1L,
			fullFidelityRestoreCount = 1L,
		)
	}
}
