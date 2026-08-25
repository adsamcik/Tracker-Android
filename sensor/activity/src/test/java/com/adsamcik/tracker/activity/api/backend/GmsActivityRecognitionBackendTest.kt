package com.adsamcik.tracker.activity.api.backend

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupKey
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupKind
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.activity.receiver.ActivityReceiver
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.tasks.Tasks
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

	@Before
	fun setUp() {
		appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		backend = GmsActivityRecognitionBackend(context, appScope)
		mockkObject(Assist)
	}

	@After
	fun tearDown() {
		appScope.cancel()
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
	fun `startUpdates returns false when Play Services unavailable`() = runTest {
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
	fun `metadata refresh updates existing pending intent without provider request`() = runTest {
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
	fun `failed GMS removal preserves pending intent for durable retry`() = runTest {
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
	fun `successful GMS removal cancels the recovered pending intent`() = runTest {
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

	// region onActivityResult
	@Test
	fun `onActivityResult updates lastActivity`() {
		val activity = RecognizedActivity(DetectedActivityType.WALKING, 85)

		backend.onActivityResult(activity, 5000L)

		backend.lastActivity shouldBe activity
		backend.lastActivityElapsedTimeMillis shouldBe 5000L
	}

	@Test
	fun `transition activity updates last state and emits watcher update`() = runTest {
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
	fun `activity burst is delivered without dropping updates`() = runTest {
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
	fun `transition events are emitted as one ordered batch`() = runTest {
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
