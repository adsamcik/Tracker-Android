package com.adsamcik.tracker.tracker.service

import android.app.Service
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.LogicalTrackingLifecycleState
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class TrackerServiceRedeliveryRecoveryTest {
	@Test
	fun `eligible stored descriptor wins over an ordinary requested start`() {
		val stored = descriptor(
			logicalTrackingId = "logical-session",
			serviceRunId = "killed-service-run",
		)
		val requested = descriptor(
			logicalTrackingId = "new-logical-session",
			serviceRunId = "new-service-run",
		)

		trackerServiceStartRecoveryDisposition(
			startFlags = 0,
			hasInMemorySession = false,
			gracefulStopRequested = false,
			isWatchdogStart = false,
		) shouldBe TrackerServiceStartRecoveryDisposition.RESOLVE_DURABLE_START
		resolveTrackerServiceStartRequest(
			storeResult = ActiveTrackingSessionStoreResult.Success(stored),
			requestedDescriptor = requested,
		) shouldBe TrackerServiceStartRequestResolution.Begin(stored, isRecovery = true)

		val recovered = stored.forNewServiceRun(changedAtEpochMs = 2_000L)
		recovered.logicalTrackingId shouldBe stored.logicalTrackingId
		recovered.serviceRunId shouldNotBe stored.serviceRunId
	}

	@Test
	fun `absent durable descriptor executes the ordinary requested start`() {
		val requested = descriptor()

		resolveTrackerServiceStartRequest(
			storeResult = ActiveTrackingSessionStoreResult.Success(null),
			requestedDescriptor = requested,
		) shouldBe TrackerServiceStartRequestResolution.Begin(requested, isRecovery = false)
	}

	@Test
	fun `redelivery falls back to its original user start when descriptor is absent`() {
		val requested = descriptor()

		trackerServiceStartRecoveryDisposition(
			startFlags = Service.START_FLAG_REDELIVERY or UNRELATED_START_FLAG,
			hasInMemorySession = false,
			gracefulStopRequested = false,
			isWatchdogStart = false,
		) shouldBe TrackerServiceStartRecoveryDisposition.RESOLVE_DURABLE_START
		resolveTrackerServiceStartRequest(
			storeResult = ActiveTrackingSessionStoreResult.Success(null),
			requestedDescriptor = requested,
		) shouldBe TrackerServiceStartRequestResolution.Begin(requested, isRecovery = false)
	}

	@Test
	fun `explicit watchdog start bypasses durable ordinary-start resolution`() {
		trackerServiceStartRecoveryDisposition(
			startFlags = Service.START_FLAG_REDELIVERY,
			hasInMemorySession = false,
			gracefulStopRequested = false,
			isWatchdogStart = true,
		) shouldBe TrackerServiceStartRecoveryDisposition.HANDLE_START_INTENT
	}

	@Test
	fun `store failure fails safely but new request supersedes ineligible durable state`() {
		val requested = descriptor()
		val failure = IllegalStateException("durable store unavailable")

		resolveTrackerServiceStartRequest(
			storeResult = ActiveTrackingSessionStoreResult.Failure(failure),
			requestedDescriptor = requested,
		) shouldBe TrackerServiceStartRequestResolution.StoreFailure(failure)
		resolveTrackerServiceStartRequest(
			storeResult = ActiveTrackingSessionStoreResult.Success(
				descriptor().copy(lifecycleState = LogicalTrackingLifecycleState.PAUSED),
			),
			requestedDescriptor = requested,
		) shouldBe TrackerServiceStartRequestResolution.Begin(requested, isRecovery = false)
	}

	@Test
	fun `new request supersedes stop candidate left by interrupted teardown`() {
		val requested = descriptor(logicalTrackingId = "new-logical-session")
		val staleStopCandidate = descriptor(logicalTrackingId = "stopped-logical-session")
			.proposeStop(
				reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
				changedAtEpochMs = 1_000L,
			)

		resolveTrackerServiceStartRequest(
			storeResult = ActiveTrackingSessionStoreResult.Success(staleStopCandidate),
			requestedDescriptor = requested,
		) shouldBe TrackerServiceStartRequestResolution.Begin(requested, isRecovery = false)
		resolveTrackerServiceStartRequest(
			storeResult = ActiveTrackingSessionStoreResult.Success(staleStopCandidate),
			requestedDescriptor = null,
		) shouldBe TrackerServiceStartRequestResolution.DoNotStart
	}

	@Test
	fun `live session ignores duplicate redelivery`() {
		trackerServiceStartRecoveryDisposition(
			startFlags = Service.START_FLAG_REDELIVERY,
			hasInMemorySession = true,
			gracefulStopRequested = false,
			isWatchdogStart = false,
		) shouldBe TrackerServiceStartRecoveryDisposition.IGNORE_DUPLICATE_REDELIVERY
	}

	@Test
	fun `graceful stop blocks ordinary watchdog and redelivery starts`() {
		listOf(0, Service.START_FLAG_REDELIVERY).forEach { startFlags ->
			listOf(false, true).forEach { hasInMemorySession ->
				listOf(false, true).forEach { isWatchdogStart ->
					trackerServiceStartRecoveryDisposition(
						startFlags = startFlags,
						hasInMemorySession = hasInMemorySession,
						gracefulStopRequested = true,
						isWatchdogStart = isWatchdogStart,
					) shouldBe TrackerServiceStartRecoveryDisposition.IGNORE_AFTER_GRACEFUL_STOP
				}
			}
		}
	}

	@Test
	fun `retry flag alone remains an ordinary durable-aware start`() {
		trackerServiceStartRecoveryDisposition(
			startFlags = Service.START_FLAG_RETRY,
			hasInMemorySession = false,
			gracefulStopRequested = false,
			isWatchdogStart = false,
		) shouldBe TrackerServiceStartRecoveryDisposition.RESOLVE_DURABLE_START
	}

	private fun descriptor(
		logicalTrackingId: String = "requested-logical-session",
		serviceRunId: String = "requested-service-run",
	) = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.PRECISION,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	)

	private companion object {
		const val UNRELATED_START_FLAG = 1 shl 8
	}
}
