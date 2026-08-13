package com.adsamcik.tracker.activity.api.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockkObject
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
	// endregion

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
	fun `stalled activity subscriber keeps a bounded queue and receives the newest state`() = runTest {
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
