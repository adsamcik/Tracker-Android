package com.adsamcik.tracker.activity.receiver

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityCallbackRetryStoreTest {
	private lateinit var directory: File
	private lateinit var store: ActivityCallbackRetryStore

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		directory = File(application.cacheDir, "activity-callback-retry-store-test")
		directory.deleteRecursively()
		store = ActivityCallbackRetryStore(directory)
	}

	@After
	fun tearDown() {
		directory.deleteRecursively()
	}

	@Test
	fun `exact callback round trips and duplicate retain is idempotent`() {
		val batch = callbackBatch()

		val firstId = store.retain(batch)
		val secondId = store.retain(batch)

		secondId shouldBe firstId
		store.pendingIds() shouldBe listOf(firstId)
		store.load(firstId) shouldBe batch
	}

	@Test
	fun `independent callback files let poison be removed without touching good retry`() {
		val poisonId = store.retain(callbackBatch(receivedWallTimeMs = 1_000L))
		val good = callbackBatch(receivedWallTimeMs = 2_000L)
		val goodId = store.retain(good)
		val poisonFile = requireNotNull(directory.listFiles()).single { poisonId in it.name }
		poisonFile.writeBytes(byteArrayOf(1, 2, 3, 4))

		val error = shouldThrow<ActivityCallbackRetryStoreException> { store.load(poisonId) }
		error.corrupt shouldBe true
		store.remove(poisonId)

		store.pendingIds() shouldBe listOf(goodId)
		store.load(goodId) shouldBe good
	}

	@Test
	fun `purge removes valid corrupt and atomic sidecar files`() {
		val id = store.retain(callbackBatch())
		File(directory, "activity-callback-$id.bin.bak").writeBytes(byteArrayOf(1))
		File(directory, "activity-callback-$id.bin.new").writeBytes(byteArrayOf(2))
		File(directory, "unreadable-poison").writeBytes(byteArrayOf(3))

		store.purge()

		directory.listFiles().orEmpty().toList() shouldBe emptyList()
	}

	@Test
	fun `interrupted AtomicFile new sidecar never claims callback retry ownership`() {
		directory.mkdirs()
		File(
			directory,
			"activity-callback-${"0".repeat(64)}.bin.new",
		).writeBytes(byteArrayOf(1, 2, 3))

		store.pendingIds() shouldBe emptyList()
	}

	@Test
	fun `oversized callback is rejected before claiming durable ownership`() {
		val oversized = callbackBatch().copy(
			recognitions = List(1_025) { index ->
				ActivityRecognitionEvidence(
					DetectedActivityType.WALKING,
					90,
					100L + index,
				)
			},
			transitions = emptyList(),
		)

		shouldThrow<IllegalArgumentException> { store.retain(oversized) }
		store.pendingIds() shouldBe emptyList()
	}

	@Test
	fun `capacity rejects a new callback without disturbing retained retry authority`() {
		val boundedStore = ActivityCallbackRetryStore(
			directory = directory,
			maxPendingCallbacks = 1,
		)
		val retainedId = boundedStore.retain(callbackBatch(receivedWallTimeMs = 1_000L))

		val error = shouldThrow<ActivityCallbackRetryStoreException> {
			boundedStore.retain(callbackBatch(receivedWallTimeMs = 2_000L))
		}

		error.corrupt shouldBe false
		boundedStore.pendingIds() shouldBe listOf(retainedId)
		boundedStore.load(retainedId) shouldBe callbackBatch(receivedWallTimeMs = 1_000L)
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
		automaticRecognitionEligible = true,
		automaticTransitions = setOf(
			ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER),
		),
		recognitions = listOf(
			ActivityRecognitionEvidence(DetectedActivityType.WALKING, 87, 100L),
		),
		transitions = listOf(
			ActivityTransitionEvidence(
				DetectedActivityType.WALKING,
				ActivityTransitionType.ENTER,
				200L,
			),
		),
	)
}
