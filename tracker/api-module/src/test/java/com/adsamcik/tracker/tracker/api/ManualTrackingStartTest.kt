package com.adsamcik.tracker.tracker.api

import io.kotest.matchers.shouldBe
import java.util.stream.Stream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

class ManualTrackingStartTest {
	@ParameterizedTest(name = "ready {0}-only starts")
	@EnumSource(TrackingCaptureSource::class)
	fun `each source can independently make a manual start ready`(source: TrackingCaptureSource) {
		resolve(source = source, availableSources = setOf(source)) shouldBe
			ManualTrackingStartReadiness.Ready(2L)
	}

	@ParameterizedTest(name = "{0}-only requests {1}")
	@MethodSource("onlySourceRepairCases")
	fun `only-source repair exposes its exact prerequisite`(
		source: TrackingCaptureSource,
		prerequisite: ManualTrackingStartPrerequisite,
		missingPreciseLocation: Set<TrackingCaptureSource>,
		missingActivityRecognition: Set<TrackingCaptureSource>,
		missingReadPhoneState: Set<TrackingCaptureSource>,
		blockedByLocationServices: Set<TrackingCaptureSource>,
	) {
		resolve(
			source = source,
			sourcesMissingPreciseLocationPermission = missingPreciseLocation,
			sourcesMissingActivityRecognitionPermission = missingActivityRecognition,
			sourcesMissingReadPhoneStatePermission = missingReadPhoneState,
			sourcesBlockedByLocationServices = blockedByLocationServices,
		) shouldBe ManualTrackingStartReadiness.RepairRequired(prerequisite)
	}

	@Test
	fun `any available enabled and reachable source is enough`() {
		resolveManualTrackingStartReadiness(
			rolloutRevision = 8L,
			enabledSources = setOf(TrackingCaptureSource.LOCATION, TrackingCaptureSource.STEPS),
			reachableSources = setOf(TrackingCaptureSource.LOCATION, TrackingCaptureSource.STEPS),
			supportedSources = setOf(TrackingCaptureSource.LOCATION, TrackingCaptureSource.STEPS),
			availableSources = setOf(TrackingCaptureSource.STEPS),
			sourcesMissingPreciseLocationPermission = setOf(TrackingCaptureSource.LOCATION),
			sourcesMissingActivityRecognitionPermission = emptySet(),
			sourcesMissingReadPhoneStatePermission = emptySet(),
			sourcesBlockedByLocationServices = emptySet(),
		) shouldBe ManualTrackingStartReadiness.Ready(8L)
	}

	@Test
	fun `repair chooses the source with the fewest missing prerequisites`() {
		resolveManualTrackingStartReadiness(
			rolloutRevision = 8L,
			enabledSources = setOf(TrackingCaptureSource.CELL, TrackingCaptureSource.ACTIVITY),
			reachableSources = setOf(TrackingCaptureSource.CELL, TrackingCaptureSource.ACTIVITY),
			supportedSources = setOf(TrackingCaptureSource.CELL, TrackingCaptureSource.ACTIVITY),
			availableSources = emptySet(),
			sourcesMissingPreciseLocationPermission = setOf(TrackingCaptureSource.CELL),
			sourcesMissingActivityRecognitionPermission = setOf(TrackingCaptureSource.ACTIVITY),
			sourcesMissingReadPhoneStatePermission = setOf(TrackingCaptureSource.CELL),
			sourcesBlockedByLocationServices = emptySet(),
		) shouldBe ManualTrackingStartReadiness.RepairRequired(
			ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION,
		)
	}

	@Test
	fun `Cell repair sequence is precise Location then phone state then Location Services`() {
		resolve(
			source = TrackingCaptureSource.CELL,
			sourcesMissingPreciseLocationPermission = setOf(TrackingCaptureSource.CELL),
			sourcesMissingReadPhoneStatePermission = setOf(TrackingCaptureSource.CELL),
			sourcesBlockedByLocationServices = setOf(TrackingCaptureSource.CELL),
		) shouldBe repair(ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION)
		resolve(
			source = TrackingCaptureSource.CELL,
			sourcesMissingReadPhoneStatePermission = setOf(TrackingCaptureSource.CELL),
			sourcesBlockedByLocationServices = setOf(TrackingCaptureSource.CELL),
		) shouldBe repair(ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION)
		resolve(
			source = TrackingCaptureSource.CELL,
			sourcesBlockedByLocationServices = setOf(TrackingCaptureSource.CELL),
		) shouldBe repair(ManualTrackingStartPrerequisite.LOCATION_SERVICES)
	}

	@Test
	fun `unsupported hardware or provider is not presented as a permission repair`() {
		resolve(
			source = TrackingCaptureSource.STEPS,
			supportedSources = emptySet(),
			sourcesMissingActivityRecognitionPermission = setOf(TrackingCaptureSource.STEPS),
		) shouldBe ManualTrackingStartReadiness.NoAvailableCaptureSource
	}

	@Test
	fun `rollout containment is unavailable rather than a settings repair`() {
		resolveManualTrackingStartReadiness(
			rolloutRevision = 11L,
			enabledSources = setOf(TrackingCaptureSource.STEPS),
			reachableSources = emptySet(),
			supportedSources = setOf(TrackingCaptureSource.STEPS),
			availableSources = setOf(TrackingCaptureSource.STEPS),
			sourcesMissingPreciseLocationPermission = emptySet(),
			sourcesMissingActivityRecognitionPermission = emptySet(),
			sourcesMissingReadPhoneStatePermission = emptySet(),
			sourcesBlockedByLocationServices = emptySet(),
		) shouldBe ManualTrackingStartReadiness.TrackingUnavailable
	}

	@Test
	fun `only an acknowledged durable enqueue reports success`() = runTest {
		executeManualTrackingStart(
			readReadiness = { ManualTrackingStartReadiness.Ready(1L) },
			enqueuePreparedStart = { false },
		) shouldBe ManualTrackingStartResult.TRACKING_UNAVAILABLE

		executeManualTrackingStart(
			readReadiness = { ManualTrackingStartReadiness.Ready(1L) },
			enqueuePreparedStart = { true },
		) shouldBe ManualTrackingStartResult.ENQUEUED
	}

	@Test
	fun `repair preserves the prerequisite and never attempts Android enqueue`() = runTest {
		var enqueueCalls = 0
		val prerequisite = ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION
		val result = executeManualTrackingStart(
			readReadiness = { ManualTrackingStartReadiness.RepairRequired(prerequisite) },
			enqueuePreparedStart = {
				enqueueCalls += 1
				true
			},
		)

		result shouldBe ManualTrackingStartResult.RepairRequired(prerequisite)
		enqueueCalls shouldBe 0
	}

	private fun resolve(
		source: TrackingCaptureSource,
		supportedSources: Set<TrackingCaptureSource> = setOf(source),
		availableSources: Set<TrackingCaptureSource> = emptySet(),
		sourcesMissingPreciseLocationPermission: Set<TrackingCaptureSource> = emptySet(),
		sourcesMissingActivityRecognitionPermission: Set<TrackingCaptureSource> = emptySet(),
		sourcesMissingReadPhoneStatePermission: Set<TrackingCaptureSource> = emptySet(),
		sourcesBlockedByLocationServices: Set<TrackingCaptureSource> = emptySet(),
	) = resolveManualTrackingStartReadiness(
		rolloutRevision = 2L,
		enabledSources = setOf(source),
		reachableSources = setOf(source),
		supportedSources = supportedSources,
		availableSources = availableSources,
		sourcesMissingPreciseLocationPermission = sourcesMissingPreciseLocationPermission,
		sourcesMissingActivityRecognitionPermission = sourcesMissingActivityRecognitionPermission,
		sourcesMissingReadPhoneStatePermission = sourcesMissingReadPhoneStatePermission,
		sourcesBlockedByLocationServices = sourcesBlockedByLocationServices,
	)

	private companion object {
		fun repair(prerequisite: ManualTrackingStartPrerequisite) =
			ManualTrackingStartReadiness.RepairRequired(prerequisite)

		@JvmStatic
		fun onlySourceRepairCases(): Stream<Arguments> = Stream.of(
			repairCase(
				TrackingCaptureSource.LOCATION,
				ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION,
				missingPrecise = true,
			),
			repairCase(
				TrackingCaptureSource.ACTIVITY,
				ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION,
				missingActivity = true,
			),
			repairCase(
				TrackingCaptureSource.STEPS,
				ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION,
				missingActivity = true,
			),
			repairCase(
				TrackingCaptureSource.WIFI,
				ManualTrackingStartPrerequisite.LOCATION_SERVICES,
				blockedByLocationServices = true,
			),
			repairCase(
				TrackingCaptureSource.CELL,
				ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION,
				missingPhone = true,
			),
		)

		private fun repairCase(
			source: TrackingCaptureSource,
			prerequisite: ManualTrackingStartPrerequisite,
			missingPrecise: Boolean = false,
			missingActivity: Boolean = false,
			missingPhone: Boolean = false,
			blockedByLocationServices: Boolean = false,
		): Arguments = Arguments.of(
			source,
			prerequisite,
			setOf(source).takeIf { missingPrecise } ?: emptySet<TrackingCaptureSource>(),
			setOf(source).takeIf { missingActivity } ?: emptySet<TrackingCaptureSource>(),
			setOf(source).takeIf { missingPhone } ?: emptySet<TrackingCaptureSource>(),
			setOf(source).takeIf { blockedByLocationServices }
				?: emptySet<TrackingCaptureSource>(),
		)
	}
}
