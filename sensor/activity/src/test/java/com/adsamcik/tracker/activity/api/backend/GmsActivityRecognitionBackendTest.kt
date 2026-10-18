package com.adsamcik.tracker.activity.api.backend

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupKey
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupKind
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupScheduler
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupStore
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.activity.receiver.ActivityReceiver
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.TaskCompletionSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class GmsActivityRecognitionBackendTest {

	private val context: Context
		get() = ApplicationProvider.getApplicationContext()

	private lateinit var backend: GmsActivityRecognitionBackend
	private lateinit var appScope: CoroutineScope
	private lateinit var cleanupStore: ActivityRegistrationCleanupStore
	private lateinit var cleanupFile: File
	private val cleanupScheduler = mockk<ActivityRegistrationCleanupScheduler>(relaxed = true)
	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setUp() {
		appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		cleanupFile = File.createTempFile("activity-backend", ".cleanup", context.cacheDir).apply { delete() }
		cleanupStore = ActivityRegistrationCleanupStore(cleanupFile)
		backend = GmsActivityRecognitionBackend(
			context, appScope, cleanupStore, cleanupScheduler, TestDispatchersProvider(testDispatcher),
		)
		mockkObject(Assist)
	}

	@After
	fun tearDown() {
		appScope.cancel()
		cleanupFile.delete()
		File("${cleanupFile.path}.bak").delete()
		File("${cleanupFile.path}.new").delete()
		unmockkAll()
	}

	// region isAvailable
	@Test
	fun `isAvailable returns false when Play Services unavailable`() {
		every { Assist.isPlayServicesAvailable(any<Context>()) } returns false

		backend.isAvailable shouldBe false
	}

	@Test
	fun `isAvailable returns true when Play Services available`() {
		every { Assist.isPlayServicesAvailable(any<Context>()) } returns true

		backend.isAvailable shouldBe true
	}
	// endregion

	// region name
	@Test
	fun `name returns Google Play Services`() {
		backend.name shouldBe "Google Play Services"
	}
	// endregion

	// region startUpdates
	@Test
	fun `startUpdates returns false when Play Services unavailable`() = runTest(testDispatcher) {
		every { Assist.isPlayServicesAvailable(any<Context>()) } returns false

		val result = backend.startUpdates(RecognitionConfig(intervalSeconds = 10))

		result shouldBe false
	}

	@Test
	fun `brokered intent captures exact automatic owner mechanism`() {
		val identity = ActivityRegistrationIdentity(
			sourceInstanceId = "activity-instance",
			registrationGeneration = 7L,
			collectedDataEpoch = 3L,
			clockDomainId = "boot-3",
			physicalConfigurationFingerprint = "physical-config",
		)
		val transitions = setOf(
			ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER),
			ActivityTransitionData(DetectedActivityType.STILL, ActivityTransitionType.ENTER),
		)

		val intent = backend.activityDetectionIntent(
			identity,
			RecognitionConfig(
				intervalSeconds = 5,
				automaticRecognitionEligible = true,
				automaticTransitions = transitions,
			),
		)

		intent.getBooleanExtra(
			GmsActivityRecognitionBackend.EXTRA_AUTOMATIC_RECOGNITION_ELIGIBLE,
			false,
		) shouldBe true
		intent.getIntArrayExtra(
			GmsActivityRecognitionBackend.EXTRA_AUTOMATIC_TRANSITION_ACTIVITY_TYPES,
		)?.toList() shouldBe listOf(
			com.google.android.gms.location.DetectedActivity.STILL,
			com.google.android.gms.location.DetectedActivity.WALKING,
		)
		intent.getIntArrayExtra(
			GmsActivityRecognitionBackend.EXTRA_AUTOMATIC_TRANSITION_TYPES,
		)?.toList() shouldBe listOf(
			ActivityTransitionType.ENTER.value,
			ActivityTransitionType.ENTER.value,
		)
	}

	@Test
	fun `metadata refresh updates existing pending intent without provider request`() = runTest(testDispatcher) {
		val identity = ActivityRegistrationIdentity(
			sourceInstanceId = "activity-instance",
			registrationGeneration = 7L,
			collectedDataEpoch = 3L,
			clockDomainId = "boot-3",
			physicalConfigurationFingerprint = "physical-config",
		)
		val key = ActivityRegistrationCleanupKey(
			kind = ActivityRegistrationCleanupKind.BROKERED,
			sourceInstanceId = identity.sourceInstanceId,
			registrationGeneration = identity.registrationGeneration,
		)
		backend.refreshRegistrationMetadata(
			RecognitionConfig(intervalSeconds = 5),
			identity,
		) shouldBe true
		val original = requireNotNull(backend.findPendingIntentForCleanup(key))

		backend.refreshRegistrationMetadata(
			RecognitionConfig(
				intervalSeconds = 5,
				automaticRecognitionEligible = true,
			),
			identity,
		) shouldBe true

		val refreshed = requireNotNull(backend.findPendingIntentForCleanup(key))
		refreshed shouldBe original
		shadowOf(refreshed).savedIntent.getBooleanExtra(
			GmsActivityRecognitionBackend.EXTRA_AUTOMATIC_RECOGNITION_ELIGIBLE,
			false,
		) shouldBe true
		refreshed.cancel()
	}
	// endregion

	@Test
	fun `brokered cleanup retrieves the exact process-stable pending intent`() {
		val sourceInstanceId = "random-instance"
		val generation = 7L
		val action = "${context.packageName}.ACTIVITY_RECOGNITION.$sourceInstanceId.$generation"
		val requestCode = 31 * sourceInstanceId.hashCode() + generation.hashCode()
		val original = PendingIntent.getBroadcast(
			context,
			requestCode,
			Intent(context, ActivityReceiver::class.java).setAction(action),
			PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_MUTABLE),
		)
		val key = ActivityRegistrationCleanupKey(
			kind = ActivityRegistrationCleanupKind.BROKERED,
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = generation,
		)

		backend.findPendingIntentForCleanup(key) shouldBe original

		original.cancel()
		backend.findPendingIntentForCleanup(key) shouldBe null
	}

	@Test
	fun `released v27 cleanup retrieves the component-only request code`() {
		val original = PendingIntent.getBroadcast(
			context,
			GmsActivityRecognitionBackend.RELEASED_V27_REQUEST_CODE,
			Intent(context, ActivityReceiver::class.java),
			PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_MUTABLE),
		)

		backend.findPendingIntentForCleanup(ActivityRegistrationCleanupKey.RELEASED_V27) shouldBe original

		original.cancel()
	}

	@Test
	fun `failed GMS removal preserves pending intent for durable retry`() = runTest(testDispatcher) {
		val client = mockk<ActivityRecognitionClient>()
		mockkStatic(ActivityRecognition::class)
		every { ActivityRecognition.getClient(any<Context>()) } returns client
		every { client.removeActivityUpdates(any()) } returns
			Tasks.forException(IllegalStateException("provider unavailable"))
		every { client.removeActivityTransitionUpdates(any()) } returns Tasks.forResult(null)
		val key = ActivityRegistrationCleanupKey.RELEASED_V27
		val original = PendingIntent.getBroadcast(
			context,
			GmsActivityRecognitionBackend.RELEASED_V27_REQUEST_CODE,
			Intent(context, ActivityReceiver::class.java),
			PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_MUTABLE),
		)

		runCatching { backend.removePendingRegistration(key) }.isFailure shouldBe true

		backend.findPendingIntentForCleanup(key) shouldBe original
		original.cancel()
	}

	@Test
	fun `successful GMS removal cancels the recovered pending intent`() = runTest(testDispatcher) {
		val client = mockk<ActivityRecognitionClient>()
		mockkStatic(ActivityRecognition::class)
		every { ActivityRecognition.getClient(any<Context>()) } returns client
		every { client.removeActivityUpdates(any()) } returns Tasks.forResult(null)
		every { client.removeActivityTransitionUpdates(any()) } returns Tasks.forResult(null)
		val key = ActivityRegistrationCleanupKey.RELEASED_V27
		PendingIntent.getBroadcast(
			context,
			GmsActivityRecognitionBackend.RELEASED_V27_REQUEST_CODE,
			Intent(context, ActivityReceiver::class.java),
			PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_MUTABLE),
		)

		backend.removePendingRegistration(key)

		backend.findPendingIntentForCleanup(key) shouldBe null
	}

	@Test
	fun `registration timeout retains exact cleanup until late registration settles`() = runTest(testDispatcher) {
		val client = prepareClient()
		val registration = TaskCompletionSource<Void>()
		every { client.requestActivityUpdates(any(), any()) } returns registration.task
		val identity = registrationIdentity()
		val key = cleanupKey(identity)
		val start = async { backend.applyRegistration(RecognitionConfig(intervalSeconds = 5), identity) }
		runCurrent()

		advanceTimeBy(GmsActivityRecognitionBackend.PROVIDER_OPERATION_TIMEOUT_MS + 1L)
		start.await() shouldBe false
		cleanupStore.read().pending shouldBe setOf(key)
		runCatching { backend.removePendingRegistration(key) }.isFailure shouldBe true
		verify(exactly = 0) { client.removeActivityUpdates(any()) }
		verify { cleanupScheduler.ensureScheduled() }

		registration.setResult(null)
		backend.removePendingRegistration(key)
		cleanupStore.read().pending shouldBe emptySet()
		backend.findPendingIntentForCleanup(key) shouldBe null
	}

	@Test
	fun `cancelled registration releases caller and preserves exact cleanup`() = runTest(testDispatcher) {
		val client = prepareClient()
		val registration = TaskCompletionSource<Void>()
		every { client.requestActivityUpdates(any(), any()) } returns registration.task
		val identity = registrationIdentity()
		val start = launch { backend.applyRegistration(RecognitionConfig(intervalSeconds = 5), identity) }
		runCurrent()

		start.cancelAndJoin()

		currentTime shouldBe 0L
		cleanupStore.read().pending shouldBe setOf(cleanupKey(identity))
		runCatching { backend.removePendingRegistration(cleanupKey(identity)) }.isFailure shouldBe true
		registration.setResult(null)
		backend.removePendingRegistration(cleanupKey(identity))
		cleanupStore.read().pending shouldBe emptySet()
	}

	@Test
	fun `removal timeout preserves pending intent and cleanup for a later retry`() = runTest(testDispatcher) {
		val client = prepareClient()
		val removal = TaskCompletionSource<Void>()
		every { client.removeActivityUpdates(any()) } returns removal.task
		val identity = registrationIdentity()
		val key = cleanupKey(identity)
		backend.refreshRegistrationMetadata(RecognitionConfig(intervalSeconds = 5), identity) shouldBe true
		cleanupStore.addPending(listOf(key))
		val original = requireNotNull(backend.findPendingIntentForCleanup(key))
		val attempt = async { runCatching { backend.removePendingRegistration(key) } }
		runCurrent()

		advanceTimeBy(GmsActivityRecognitionBackend.PROVIDER_OPERATION_TIMEOUT_MS + 1L)
		attempt.await().isFailure shouldBe true
		cleanupStore.read().pending shouldBe setOf(key)
		backend.findPendingIntentForCleanup(key) shouldBe original

		removal.setResult(null)
		every { client.removeActivityUpdates(any()) } returns Tasks.forResult(null)
		backend.removePendingRegistration(key)
		cleanupStore.read().pending shouldBe emptySet()
		backend.findPendingIntentForCleanup(key) shouldBe null
	}

	@Test
	fun `accepted registration clears only its temporary cleanup key`() = runTest(testDispatcher) {
		prepareClient()
		val identity = registrationIdentity()
		cleanupStore.addPending(listOf(ActivityRegistrationCleanupKey.RELEASED_V27))

		backend.applyRegistration(RecognitionConfig(intervalSeconds = 5), identity) shouldBe true

		cleanupStore.read().pending shouldBe setOf(ActivityRegistrationCleanupKey.RELEASED_V27)
		backend.findPendingIntentForCleanup(cleanupKey(identity))?.cancel()
	}

	@Test
	fun `cleanup queued during registration cannot remove its later accepted request`() = runTest(testDispatcher) {
		val client = prepareClient()
		val registration = TaskCompletionSource<Void>()
		every { client.requestActivityUpdates(any(), any()) } returns registration.task
		val identity = registrationIdentity()
		val key = cleanupKey(identity)
		val start = async { backend.applyRegistration(RecognitionConfig(intervalSeconds = 5), identity) }
		runCurrent()
		cleanupStore.read().pending shouldBe setOf(key)
		val cleanup = async { backend.removePendingRegistration(key) }
		runCurrent()

		registration.setResult(null)
		start.await() shouldBe true
		cleanup.await()

		verify(exactly = 0) { client.removeActivityUpdates(any()) }
		cleanupStore.read().pending shouldBe emptySet()
		requireNotNull(backend.findPendingIntentForCleanup(key)).cancel()
	}

	private fun prepareClient(): ActivityRecognitionClient {
		val client = mockk<ActivityRecognitionClient>()
		mockkStatic(ActivityRecognition::class)
		every { Assist.isPlayServicesAvailable(any<Context>()) } returns true
		every { ActivityRecognition.getClient(any<Context>()) } returns client
		every { client.requestActivityUpdates(any(), any()) } returns Tasks.forResult(null)
		every { client.removeActivityUpdates(any()) } returns Tasks.forResult(null)
		every { client.removeActivityTransitionUpdates(any()) } returns Tasks.forResult(null)
		return client
	}

	private fun registrationIdentity() = ActivityRegistrationIdentity(
		sourceInstanceId = "bounded-registration",
		registrationGeneration = 9L,
		collectedDataEpoch = 0L,
		clockDomainId = "boot-9",
		physicalConfigurationFingerprint = "physical-9",
	)

	private fun cleanupKey(identity: ActivityRegistrationIdentity) = ActivityRegistrationCleanupKey(
		kind = ActivityRegistrationCleanupKind.BROKERED,
		sourceInstanceId = identity.sourceInstanceId,
		registrationGeneration = identity.registrationGeneration,
	)

	// region onActivityResult
	@Test
	fun `onActivityResult updates lastActivity`() {
		val activity = RecognizedActivity(DetectedActivityType.WALKING, 85)

		backend.onActivityResult(activity, 5000L)

		backend.lastActivity shouldBe activity
		backend.lastActivityElapsedTimeMillis shouldBe 5000L
	}

	@Test
	fun `transition activity updates last state and emits watcher update`() = runTest(testDispatcher) {
		val activity = RecognizedActivity(DetectedActivityType.WALKING, 100)
		val update = async { backend.activityUpdates.first() }
		runCurrent()

		backend.onTransitionActivityResult(activity, 5_000_000_000L)

		update.await() shouldBe ActivityUpdate(
			activity = activity,
			elapsedTimeMillis = 5_000L,
			source = ActivityUpdateSource.TRANSITION,
		)
		backend.lastActivityElapsedTimeMillis shouldBe 5_000L
	}

	@Test
	fun `activity burst is delivered without dropping updates`() = runTest(testDispatcher) {
		val releaseCollector = CompletableDeferred<Unit>()
		val received = mutableListOf<ActivityUpdate>()
		val collector = launch {
			backend.activityUpdates
				.onEach {
					received += it
					releaseCollector.await()
				}
				.take(32)
				.collect {}
		}
		runCurrent()

		repeat(32) { index ->
			backend.onActivityResult(
				activity = RecognizedActivity(DetectedActivityType.WALKING, 80),
				elapsedTimeMillis = index.toLong(),
			)
		}
		releaseCollector.complete(Unit)
		advanceUntilIdle()

		received shouldHaveSize 32
		collector.cancel()
	}

	@Test
	fun `stalled activity subscriber keeps a bounded queue and receives the newest state`() = runTest(testDispatcher) {
		val releaseCollector = CompletableDeferred<Unit>()
		val received = mutableListOf<ActivityUpdate>()
		val expectedCount = GmsActivityRecognitionBackend.LIVE_UPDATE_BUFFER_CAPACITY + 1
		val collector = launch {
			backend.activityUpdates
				.onEach {
					received += it
					if (received.size == 1) releaseCollector.await()
				}
				.take(expectedCount)
				.collect {}
		}
		runCurrent()

		val burstSize = GmsActivityRecognitionBackend.LIVE_UPDATE_BUFFER_CAPACITY * 4
		repeat(burstSize) { index ->
			backend.onActivityResult(
				activity = RecognizedActivity(DetectedActivityType.WALKING, 80),
				elapsedTimeMillis = index.toLong(),
			)
		}
		releaseCollector.complete(Unit)
		advanceUntilIdle()

		received shouldHaveSize expectedCount
		received.last().elapsedTimeMillis shouldBe (burstSize - 1).toLong()
		collector.cancel()
	}

	@Test
	fun `transition events are emitted as one ordered batch`() = runTest(testDispatcher) {
		val updates = listOf(
			TransitionUpdate(
				activityType = DetectedActivityType.STILL,
				transitionType = ActivityTransitionType.ENTER,
				elapsedRealTimeNanos = 1L,
			),
			TransitionUpdate(
				activityType = DetectedActivityType.WALKING,
				transitionType = ActivityTransitionType.ENTER,
				elapsedRealTimeNanos = 2L,
			),
		)
		val emitted = async { backend.transitionUpdates.first() }
		runCurrent()

		backend.onTransitionResult(updates)

		emitted.await() shouldBe updates
	}
	// endregion
}
