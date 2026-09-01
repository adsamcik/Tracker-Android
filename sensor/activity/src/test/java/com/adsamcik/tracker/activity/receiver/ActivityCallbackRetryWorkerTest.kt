package com.adsamcik.tracker.activity.receiver

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityDurableSelection
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressResult
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityCallbackRetryWorkerTest {
	private lateinit var directory: File
	private lateinit var store: ActivityCallbackRetryStore
	private lateinit var scheduler: ActivityCallbackRetryScheduler
	private lateinit var owner: ActivityCallbackRetryOwner
	private lateinit var ingress: ActivityRecognitionEventIngress

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		directory = File(application.cacheDir, "activity-callback-retry-worker-test")
		directory.deleteRecursively()
		store = ActivityCallbackRetryStore(
			directory = directory,
			currentClockDomainId = { "boot-7" },
			nowElapsedRealtimeNanos = { 1_000L },
		)
		scheduler = mockk(relaxed = true)
		owner = ActivityCallbackRetryOwner(store, scheduler)
		ingress = mockk()
	}

	@After
	fun tearDown() {
		directory.deleteRecursively()
	}

	@Test
	fun `retained callback replays exactly once without live foreground start context`() = runTest {
		val batch = callbackBatch()
		owner.retain(batch)
		val replay = slot<ActivityRecognitionEvidenceBatch>()
		coEvery { ingress.admit(capture(replay)) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(transitionIndexes = setOf(0)),
		)

		runActivityCallbackRetryWork(owner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.COMPLETE
		replay.captured.startContext shouldBe ActivityIngressStartContext.DURABLE_REPLAY
		replay.captured.copy(startContext = ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK) shouldBe batch
		owner.pendingIds() shouldBe emptyList()

		runActivityCallbackRetryWork(owner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.COMPLETE
		coVerify(exactly = 1) { ingress.admit(any()) }
	}

	@Test
	fun `startup not ready never resolves Room backed ingress provider`() = runTest {
		owner.retain(callbackBatch())
		var ingressResolved = false

		val outcome = runActivityCallbackRetryAfterStartup(
			retryOwner = owner,
			reconcileStartup = {
				TrackingAdmissionStartupResult.RetryableFailure(
					TrackingStartupStage.PREVIOUS_EXIT,
					"RECOVERY_PENDING",
				)
			},
			resolveIngress = {
				ingressResolved = true
				ingress
			},
		)

		outcome shouldBe ActivityCallbackRetryWorkOutcome.RETRY
		ingressResolved shouldBe false
		owner.pendingIds().size shouldBe 1
	}

	@Test
	fun `Room ownership after downstream retry removes redundant callback file`() = runTest {
		val batch = callbackBatch()
		owner.retain(
			batch.copy(
				transitions = batch.transitions + batch.transitions.single().copy(
					providerElapsedRealtimeNanos = 200L,
				),
			),
		)
		coEvery { ingress.admit(any()) } returns ActivityIngressResult.retryable(
			admittedCount = 1,
			duplicateCount = 0,
			failureCode = "PIPELINE_LEASE_UNAVAILABLE",
			discardedCount = 1,
		)

		runActivityCallbackRetryWork(owner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.COMPLETE
		owner.pendingIds() shouldBe emptyList()
	}

	@Test
	fun `terminal Room ownership retries when callback file removal durability is unproven`() = runTest {
		var failDirectorySync = false
		val failingStore = ActivityCallbackRetryStore(
			directory = directory,
			syncDirectory = {
				if (failDirectorySync) error("directory sync unavailable")
			},
			currentClockDomainId = { "boot-7" },
			nowElapsedRealtimeNanos = { 1_000L },
		)
		val failingOwner = ActivityCallbackRetryOwner(failingStore, scheduler)
		failingOwner.retain(callbackBatch())
		failDirectorySync = true
		coEvery { ingress.admit(any()) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(transitionIndexes = setOf(0)),
		)

		runActivityCallbackRetryWork(failingOwner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.RETRY

		// AtomicFile removal may already have succeeded, but the worker deliberately requests a
		// retry until it can re-inventory under a healthy durability boundary.
		failDirectorySync = false
		runActivityCallbackRetryWork(failingOwner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.COMPLETE
	}

	@Test
	fun `retryable pre-WAL failure keeps exact callback for WorkManager retry`() = runTest {
		val batch = callbackBatch()
		owner.retain(batch)
		coEvery { ingress.admit(any()) } returns ActivityIngressResult.retryable(
			0,
			0,
			"STORAGE_UNAVAILABLE",
		)

		runActivityCallbackRetryWork(owner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.RETRY
		owner.pendingIds().size shouldBe 1
		store.load(owner.pendingIds().single()) shouldBe batch
	}

	@Test
	fun `permission revocation after callback does not retroactively discard observed fact`() = runTest {
		owner.retain(callbackBatch())
		coEvery { ingress.admit(any()) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(transitionIndexes = setOf(0)),
		)

		// The worker performs no provider access. Current policy, consent, authorization, and
		// collected-data epoch are revalidated by Room using the immutable observed-time envelope.
		runActivityCallbackRetryWork(owner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.COMPLETE
		owner.pendingIds() shouldBe emptyList()
		coVerify(exactly = 1) { ingress.admit(any()) }
	}

	@Test
	fun `retain in final empty inventory window replaces worker and drains successor`() = runTest {
		val policies = mutableListOf<ExistingWorkPolicy>()
		val replacingScheduler = ActivityCallbackRetryScheduler { policy -> policies += policy }
		val replacingOwner = ActivityCallbackRetryOwner(store, replacingScheduler)
		val first = callbackBatch(receivedWallTimeMs = 1_000L)
		val second = callbackBatch(receivedWallTimeMs = 2_000L)
		replacingOwner.retain(first)
		coEvery { ingress.admit(any()) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(transitionIndexes = setOf(0)),
		)

		val firstOutcome = runActivityCallbackRetryWork(
			replacingOwner,
			ingress,
			afterFinalInventory = { morePending ->
				morePending shouldBe false
				replacingOwner.retain(second)
			},
		)

		firstOutcome shouldBe ActivityCallbackRetryWorkOutcome.COMPLETE
		policies shouldBe listOf(ExistingWorkPolicy.REPLACE, ExistingWorkPolicy.REPLACE)
		replacingOwner.pendingIds().size shouldBe 1

		runActivityCallbackRetryWork(replacingOwner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.COMPLETE
		replacingOwner.pendingIds() shouldBe emptyList()
		// No polling and no per-file chain: only each durable retain requested one unique replacement.
		policies.size shouldBe 2
		coVerify(exactly = 2) { ingress.admit(any()) }
	}

	@Test
	fun `corrupt callback is partitioned while good callback still reaches ingress`() = runTest {
		val corruptId = owner.retain(callbackBatch(receivedWallTimeMs = 1_000L))
		owner.retain(callbackBatch(receivedWallTimeMs = 2_000L))
		requireNotNull(directory.listFiles()).single { corruptId in it.name }
			.writeBytes(byteArrayOf(9, 8, 7))
		coEvery { ingress.admit(any()) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(transitionIndexes = setOf(0)),
		)

		runActivityCallbackRetryWork(owner, ingress) shouldBe
			ActivityCallbackRetryWorkOutcome.COMPLETE

		owner.pendingIds() shouldBe emptyList()
		store.gapReceipts().map { it.code } shouldBe
			listOf(ActivityCallbackGapCode.RETRY_RECORD_CORRUPT)
		coVerify(exactly = 1) { ingress.admit(any()) }
	}

	@Test
	fun `collected-data deletion waits replay claim then purges and fences old work`() = runTest {
		val id = owner.retain(callbackBatch())
		val claim = requireNotNull(owner.claim(id))
		val purge = async(start = CoroutineStart.UNDISPATCHED) {
			owner.fenceAndPurgeForCollectedDataDeletion()
		}

		purge.isCompleted shouldBe false
		claim.release()
		purge.await()
		owner.pendingIds() shouldBe emptyList()
		owner.claim(id) shouldBe null

		owner.resumeAfterCollectedDataDeletion()
		owner.retain(callbackBatch(receivedWallTimeMs = 3_000L))
		owner.pendingIds().size shouldBe 1
	}

	private fun callbackBatch(
		receivedWallTimeMs: Long = 1_000L,
	) = ActivityRecognitionEvidenceBatch(
		receivedElapsedRealtimeNanos = 500L,
		receivedWallTimeMs = receivedWallTimeMs,
		registrationIdentity = ActivityRegistrationIdentity(
			sourceInstanceId = "activity-instance",
			registrationGeneration = 7L,
			collectedDataEpoch = 11L,
			clockDomainId = "boot-7",
			physicalConfigurationFingerprint = "config-hash",
		),
		startContext = ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK,
		automaticTransitions = setOf(
			ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER),
		),
		transitions = listOf(
			ActivityTransitionEvidence(
				DetectedActivityType.WALKING,
				ActivityTransitionType.ENTER,
				100L,
			),
		),
	)
}
