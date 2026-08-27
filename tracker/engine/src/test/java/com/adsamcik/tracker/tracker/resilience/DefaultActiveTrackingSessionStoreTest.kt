package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultActiveTrackingSessionStoreTest {
	@Test
	fun `descriptor survives repository recreation and is removed on graceful clear`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		val descriptor = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			restartBootId = "boot:test",
			restartToken = "restart-token",
			sessionSegmentId = 42L,
		)

		store.save(descriptor) shouldBe ActiveTrackingSessionStoreResult.Success(descriptor)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(descriptor)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(null)
	}

	@Test
	fun `conditional clear cannot erase a resumed or newer service run`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		val firstRun = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = "logical-session",
			serviceRunId = "service-run-one",
		).proposeStop(
			reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
			changedAtEpochMs = 100L,
		)
		val replacement = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = firstRun.logicalTrackingId,
			serviceRunId = "service-run-two",
		)

		val resumed = firstRun.withdrawStopCandidate(changedAtEpochMs = 101L)
		store.save(resumed) shouldBe ActiveTrackingSessionStoreResult.Success(resumed)
		store.clearIfCurrent(firstRun) shouldBe ActiveTrackingSessionStoreResult.Success(resumed)
		store.save(replacement) shouldBe ActiveTrackingSessionStoreResult.Success(replacement)
		store.clearIfCurrent(firstRun) shouldBe ActiveTrackingSessionStoreResult.Success(replacement)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(replacement)
	}

	@Test
	fun `exact clear removes stale active descriptor but preserves any changed revision`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		val staleAutomatic = ActiveTrackingSessionDescriptor(
			isUserInitiated = false,
			isAmbient = false,
			policyTier = PolicyTier.AMBIENT,
			logicalTrackingId = "stale-automatic",
			serviceRunId = "stale-run",
		)
		val changed = staleAutomatic.copy(lifecycleRevision = 1L)

		store.save(changed) shouldBe ActiveTrackingSessionStoreResult.Success(changed)
		store.clearExact(staleAutomatic) shouldBe ActiveTrackingSessionStoreResult.Success(changed)
		store.save(staleAutomatic) shouldBe ActiveTrackingSessionStoreResult.Success(staleAutomatic)
		store.clearExact(staleAutomatic) shouldBe ActiveTrackingSessionStoreResult.Success(null)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(null)
	}
}
