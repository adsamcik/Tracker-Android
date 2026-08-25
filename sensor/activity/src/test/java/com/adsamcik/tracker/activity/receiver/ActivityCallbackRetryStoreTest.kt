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
import java.security.MessageDigest
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
	private var nowWallTimeMs = 1_000L
	private var nowElapsedRealtimeNanos = 1_000L
	private var currentClockDomainId = "boot-7"

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		directory = File(application.cacheDir, "activity-callback-retry-store-test")
		directory.deleteRecursively()
		store = newStore()
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
		store.recordGap(callbackBatch(), ActivityCallbackGapCode.HANDOFF_BUDGET_EXHAUSTED)
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
		val boundedStore = newStore(maxPendingCallbacks = 1)
		val retainedId = boundedStore.retain(callbackBatch(receivedWallTimeMs = 1_000L))

		val error = shouldThrow<ActivityCallbackRetryStoreException> {
			boundedStore.retain(callbackBatch(receivedWallTimeMs = 2_000L))
		}

		error.corrupt shouldBe false
		error.code shouldBe ActivityCallbackGapCode.RETRY_COUNT_BUDGET_EXHAUSTED
		boundedStore.pendingIds() shouldBe listOf(retainedId)
		boundedStore.load(retainedId) shouldBe callbackBatch(receivedWallTimeMs = 1_000L)
	}

	@Test
	fun `byte budget rejects new callback without disturbing retained retry authority`() {
		val boundedStore = newStore(maxPendingBytes = 1L)

		val error = shouldThrow<ActivityCallbackRetryStoreException> {
			boundedStore.retain(callbackBatch())
		}

		error.code shouldBe ActivityCallbackGapCode.RETRY_BYTE_BUDGET_EXHAUSTED
		boundedStore.pendingIds() shouldBe emptyList()
	}

	@Test
	fun `wall clock rollback cannot extend operational inbox freshness`() {
		val id = store.retain(callbackBatch(receivedWallTimeMs = 50_000L))
		nowWallTimeMs = 1L
		nowElapsedRealtimeNanos = 501L

		store.load(id) shouldBe callbackBatch(receivedWallTimeMs = 50_000L)
	}

	@Test
	fun `elapsed reset and boot change terminally gap instead of reviving retry`() {
		val id = store.retain(callbackBatch(receivedElapsedRealtimeNanos = 500L))
		nowElapsedRealtimeNanos = 499L

		shouldThrow<ActivityCallbackRetryStoreException> { store.load(id) }.code shouldBe
			ActivityCallbackGapCode.RETRY_RECORD_EXPIRED

		nowElapsedRealtimeNanos = 501L
		currentClockDomainId = "boot-8"
		shouldThrow<ActivityCallbackRetryStoreException> { store.load(id) }.code shouldBe
			ActivityCallbackGapCode.RETRY_CLOCK_DOMAIN_CHANGED
	}

	@Test
	fun `six hour bound is elapsed operational inbox freshness not product retention`() {
		val boundedStore = newStore(maxRetryAgeMs = 10L)
		val id = boundedStore.retain(callbackBatch(receivedElapsedRealtimeNanos = 500L))
		nowWallTimeMs = 1L
		nowElapsedRealtimeNanos = 500L + 11_000_000L

		shouldThrow<ActivityCallbackRetryStoreException> { boundedStore.load(id) }.code shouldBe
			ActivityCallbackGapCode.RETRY_RECORD_EXPIRED
	}

	@Test
	fun `v2 record uses stable wire codes and unsupported versions are explicit`() {
		val id = store.retain(callbackBatch())
		val file = requireNotNull(directory.listFiles()).single { id in it.name }
		val encoded = file.readBytes()
		String(encoded, Charsets.ISO_8859_1).contains("WALKING") shouldBe false
		String(encoded, Charsets.ISO_8859_1).contains("LIVE_PROVIDER_CALLBACK") shouldBe false
		encoded[4] = 0
		encoded[5] = 0
		encoded[6] = 0
		encoded[7] = 99
		val unsupportedId = MessageDigest.getInstance("SHA-256")
			.digest(encoded)
			.joinToString("") { byte -> "%02x".format(byte) }
		file.delete()
		File(directory, "activity-callback-$unsupportedId.bin").writeBytes(encoded)

		shouldThrow<ActivityCallbackRetryStoreException> {
			store.load(unsupportedId)
		}.code shouldBe ActivityCallbackGapCode.RETRY_RECORD_UNSUPPORTED_VERSION
	}

	@Test
	fun `gap receipts are explicit bounded and purged with collected data`() {
		val boundedStore = newStore(maxGapReceipts = 1)
		val first = callbackBatch(receivedWallTimeMs = 1_000L)
		val second = callbackBatch(receivedWallTimeMs = 2_000L)

		boundedStore.recordGap(first, ActivityCallbackGapCode.RETRY_STORAGE_UNAVAILABLE) shouldBe true
		nowWallTimeMs += 1L
		boundedStore.recordGap(second, ActivityCallbackGapCode.RETRY_RECORD_EXPIRED) shouldBe true
		boundedStore.gapReceipts().map { it.code } shouldBe
			listOf(ActivityCallbackGapCode.RETRY_RECORD_EXPIRED)

		boundedStore.purge()
		boundedStore.gapReceipts() shouldBe emptyList()
	}

	private fun newStore(
		maxPendingCallbacks: Int = 64,
		maxPendingBytes: Long = 512L * 1_024L,
		maxRetryAgeMs: Long = 6L * 60L * 60L * 1_000L,
		maxGapReceipts: Int = 64,
	) = ActivityCallbackRetryStore(
		directory = directory,
		nowWallTimeMs = { nowWallTimeMs },
		currentClockDomainId = { currentClockDomainId },
		nowElapsedRealtimeNanos = { nowElapsedRealtimeNanos },
		maxPendingCallbacks = maxPendingCallbacks,
		maxPendingBytes = maxPendingBytes,
		maxRetryAgeMs = maxRetryAgeMs,
		maxGapReceipts = maxGapReceipts,
	)

	private fun callbackBatch(
		receivedWallTimeMs: Long = 1_000L,
		receivedElapsedRealtimeNanos: Long = 500L,
	) = ActivityRecognitionEvidenceBatch(
		receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
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
