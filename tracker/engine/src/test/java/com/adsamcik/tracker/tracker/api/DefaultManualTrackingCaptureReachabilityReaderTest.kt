package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import javax.inject.Provider

class DefaultManualTrackingCaptureReachabilityReaderTest {
	@Test
	fun `contained rollout exposes no manually reachable source`() = runTest {
		val subject = reader(TrackingRolloutState.contained(revision = 7L))

		subject.read() shouldBe ManualTrackingCaptureReachability.Available(
			rolloutRevision = 7L,
			reachableSources = emptySet(),
		)
	}

	@Test
	fun `reader maps all source families without promoting a contained source`() = runTest {
		val sources = SourceKind.entries.toSet() - SourceKind.CELL
		val subject = reader(
			TrackingRolloutState.eventShadow(
				sources = sources,
				revision = 9L,
			),
		)

		subject.read() shouldBe ManualTrackingCaptureReachability.Available(
			rolloutRevision = 9L,
			reachableSources = setOf(
				TrackingCaptureSource.LOCATION,
				TrackingCaptureSource.WIFI,
				TrackingCaptureSource.ACTIVITY,
				TrackingCaptureSource.STEPS,
				TrackingCaptureSource.PRESSURE,
			),
		)
	}

	@Test
	fun `automatic-only capture and control ownership cannot authorize a manual start`() = runTest {
		val subject = reader(
			TrackingRolloutState.eventShadow(
				sources = setOf(SourceKind.STEPS),
				controlSources = setOf(SourceKind.ACTIVITY),
				captureModes = mapOf(
					SourceKind.STEPS to setOf(
						CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE,
					),
				),
			),
		)

		(subject.read() as ManualTrackingCaptureReachability.Available)
			.reachableSources shouldBe emptySet()
	}

	@Test
	fun `closed startup gate returns unavailable without constructing the rollout store`() = runTest {
		var rolloutStoreRequested = false
		val subject = DefaultManualTrackingCaptureReachabilityReader(
			startupGateProvider = Provider { startupGate(ready = false) },
			rolloutStateStoreProvider = Provider {
				rolloutStoreRequested = true
				error("Target Room authority must stay cold before startup is ready")
			},
		)

		subject.read() shouldBe ManualTrackingCaptureReachability.Unavailable
		rolloutStoreRequested shouldBe false
	}

	private fun reader(state: TrackingRolloutState) =
		DefaultManualTrackingCaptureReachabilityReader(
			startupGateProvider = Provider { startupGate(ready = true) },
			rolloutStateStoreProvider = Provider { object : TrackingRolloutStateStore {
				override suspend fun load(): TrackingRolloutState = state

				override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) = Unit
			} },
		)

	private fun startupGate(ready: Boolean) = object : TrackingStartupGate {
		override val isReady: Boolean = ready
		override val currentGeneration: Long = 4L

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			error("Reachability reads must not reconcile or wait for startup")
	}
}
