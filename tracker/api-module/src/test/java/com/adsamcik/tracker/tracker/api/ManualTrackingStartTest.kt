package com.adsamcik.tracker.tracker.api

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ManualTrackingStartTest {
	@Test
	fun `each source can independently make a manual start ready`() {
		TrackingCaptureSource.entries.forEach { source ->
			resolveManualTrackingStartReadiness(
				rolloutRevision = 2L,
				enabledSources = setOf(source),
				reachableSources = setOf(source),
				availableSources = setOf(source),
				preciseLocationPermissionWouldEnable = emptySet(),
			) shouldBe ManualTrackingStartReadiness.Ready(2L)
		}
	}

	@Test
	fun `any available enabled and reachable source is enough`() {
		resolveManualTrackingStartReadiness(
			rolloutRevision = 8L,
			enabledSources = setOf(TrackingCaptureSource.LOCATION, TrackingCaptureSource.STEPS),
			reachableSources = setOf(TrackingCaptureSource.STEPS),
			availableSources = setOf(TrackingCaptureSource.STEPS),
			preciseLocationPermissionWouldEnable = setOf(TrackingCaptureSource.LOCATION),
		) shouldBe ManualTrackingStartReadiness.Ready(8L)
	}

	@Test
	fun `missing Location permission does not block another available source`() {
		setOf(
			TrackingCaptureSource.ACTIVITY,
			TrackingCaptureSource.STEPS,
			TrackingCaptureSource.PRESSURE,
		).forEach { independentSource ->
			resolveManualTrackingStartReadiness(
				rolloutRevision = 8L,
				enabledSources = setOf(TrackingCaptureSource.LOCATION, independentSource),
				reachableSources = setOf(TrackingCaptureSource.LOCATION, independentSource),
				availableSources = setOf(independentSource),
				preciseLocationPermissionWouldEnable = setOf(TrackingCaptureSource.LOCATION),
			) shouldBe ManualTrackingStartReadiness.Ready(8L)
		}
	}

	@Test
	fun `precise Location is requested only when it unlocks an enabled reachable source`() {
		resolveManualTrackingStartReadiness(
			rolloutRevision = 3L,
			enabledSources = setOf(TrackingCaptureSource.WIFI),
			reachableSources = setOf(TrackingCaptureSource.WIFI),
			availableSources = emptySet(),
			preciseLocationPermissionWouldEnable = setOf(TrackingCaptureSource.WIFI),
		) shouldBe ManualTrackingStartReadiness.PreciseLocationPermissionRequired

		resolveManualTrackingStartReadiness(
			rolloutRevision = 3L,
			enabledSources = setOf(TrackingCaptureSource.STEPS),
			reachableSources = setOf(TrackingCaptureSource.STEPS),
			availableSources = emptySet(),
			preciseLocationPermissionWouldEnable = setOf(TrackingCaptureSource.WIFI),
		) shouldBe ManualTrackingStartReadiness.NoAvailableCaptureSource
	}

	@Test
	fun `rollout containment is unavailable rather than a settings repair`() {
		resolveManualTrackingStartReadiness(
			rolloutRevision = 11L,
			enabledSources = setOf(TrackingCaptureSource.STEPS),
			reachableSources = emptySet(),
			availableSources = setOf(TrackingCaptureSource.STEPS),
			preciseLocationPermissionWouldEnable = emptySet(),
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
	fun `repair outcomes never attempt Android enqueue`() = runTest {
		var enqueueCalls = 0
		val result = executeManualTrackingStart(
			readReadiness = { ManualTrackingStartReadiness.PreciseLocationPermissionRequired },
			enqueuePreparedStart = {
				enqueueCalls += 1
				true
			},
		)

		result shouldBe ManualTrackingStartResult.PRECISE_LOCATION_PERMISSION_REQUIRED
		enqueueCalls shouldBe 0
	}
}
