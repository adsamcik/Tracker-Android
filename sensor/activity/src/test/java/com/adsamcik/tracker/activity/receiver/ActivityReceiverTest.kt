package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.DefaultActivityRequestManager
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.android.EntryPointAccessors
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityReceiverTest {

	private val receiver = ActivityReceiver()
	private val context: Context
		get() = ApplicationProvider.getApplicationContext()

	private lateinit var mockBackend: GmsActivityRecognitionBackend
	private lateinit var mockRequestManager: DefaultActivityRequestManager

	@Before
	fun setUp() {
		mockkStatic(ActivityRecognitionResult::class)
		mockkStatic(ActivityTransitionResult::class)
		mockkObject(Logger)
		mockkObject(Time)

		every { Logger.logWithStringPreference(any(), any(), any()) } just runs
		every { Time.elapsedRealtimeMillis } returns 5000L

		// Mock the Hilt EntryPoint so the receiver can obtain a backend and request manager
		mockBackend = mockk(relaxed = true)
		mockRequestManager = mockk(relaxed = true)
		val mockEntryPoint = mockk<ActivityReceiverEntryPoint> {
			every { backend() } returns mockBackend
			every { defaultActivityRequestManager() } returns mockRequestManager
		}
		mockkStatic(EntryPointAccessors::class)
		every {
			EntryPointAccessors.fromApplication(any(), ActivityReceiverEntryPoint::class.java)
		} returns mockEntryPoint
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	private fun mockGmsDetectedActivity(
		activityType: Int,
		confidenceValue: Int,
	): com.google.android.gms.location.DetectedActivity = mockk {
		every { type } returns activityType
		every { confidence } returns confidenceValue
	}

	private fun intentWithActivityResult(
		activityType: Int,
		confidenceValue: Int,
	): Intent {
		val gmsDetectedActivity = mockGmsDetectedActivity(activityType, confidenceValue)
		val result: ActivityRecognitionResult = mockk {
			every { mostProbableActivity } returns gmsDetectedActivity
		}
		val intent: Intent = mockk(relaxed = true)

		every { ActivityRecognitionResult.hasResult(intent) } returns true
		every { ActivityRecognitionResult.extractResult(intent) } returns result
		every { ActivityTransitionResult.hasResult(intent) } returns false

		return intent
	}

	private fun intentWithTransitionResult(
		transitions: List<ActivityTransitionEvent>,
	): Intent {
		val result: ActivityTransitionResult = mockk {
			every { transitionEvents } returns transitions
		}
		val intent: Intent = mockk(relaxed = true)

		every { ActivityRecognitionResult.hasResult(intent) } returns false
		every { ActivityTransitionResult.hasResult(intent) } returns true
		every { ActivityTransitionResult.extractResult(intent) } returns result

		return intent
	}

	// region onReceive with activity result
	@Test
	fun `updates backend lastActivity on activity recognition result`() {
			val intent = intentWithActivityResult(
				com.google.android.gms.location.DetectedActivity.WALKING, 85,
			)

			receiver.onReceive(context, intent)

			verify {
				mockBackend.onActivityResult(
					match { it.activityType == DetectedActivity.WALKING.value && it.confidence == 85 },
					any(),
				)
			}
		}

		@Test
		fun `passes elapsed time to backend`() {
			every { Time.elapsedRealtimeMillis } returns 12345L
			val intent = intentWithActivityResult(
				com.google.android.gms.location.DetectedActivity.RUNNING, 70,
			)

			receiver.onReceive(context, intent)

			verify {
				mockBackend.onActivityResult(any(), eq(12345L))
			}
		}

		@Test
		fun `calls ActivityRequestManager onActivityUpdate`() {
			val intent = intentWithActivityResult(
				com.google.android.gms.location.DetectedActivity.IN_VEHICLE, 90,
			)

			receiver.onReceive(context, intent)

			verify(exactly = 1) {
				mockRequestManager.onActivityUpdate(context, any(), any())
		}
	}
	// endregion

	// region onReceive with transition result

		@Test
		fun `calls ActivityRequestManager onActivityTransition`() {
			val transitionEvent: ActivityTransitionEvent = mockk {
				every { activityType } returns com.google.android.gms.location.DetectedActivity.WALKING
				every { elapsedRealTimeNanos } returns 9999L
				every { transitionType } returns 0
			}
			val intent = intentWithTransitionResult(listOf(transitionEvent))

			receiver.onReceive(context, intent)

			verify(exactly = 1) {
				mockRequestManager.onActivityTransition(context, any())
			}
		}

		@Test
		fun `forwards transition to backend`() {
			val transitionEvent: ActivityTransitionEvent = mockk {
				every { activityType } returns com.google.android.gms.location.DetectedActivity.ON_BICYCLE
				every { elapsedRealTimeNanos } returns 7777L
				every { transitionType } returns 0
			}
			val intent = intentWithTransitionResult(listOf(transitionEvent))

			receiver.onReceive(context, intent)

			verify { mockBackend.onTransitionResult(any()) }
			verify {
				mockBackend.onTransitionActivityResult(
					match { it.activityType == DetectedActivity.ON_BICYCLE.value && it.confidence == 100 },
					eq(7777L),
				)
			}
		}

		@Test
		fun `forwards last transition event elapsed time to backend`() {
			val event1: ActivityTransitionEvent = mockk {
				every { activityType } returns com.google.android.gms.location.DetectedActivity.STILL
				every { elapsedRealTimeNanos } returns 1000L
				every { transitionType } returns 0
			}
			val event2: ActivityTransitionEvent = mockk {
				every { activityType } returns com.google.android.gms.location.DetectedActivity.WALKING
				every { elapsedRealTimeNanos } returns 2000L
				every { transitionType } returns 0
			}
			val intent = intentWithTransitionResult(listOf(event1, event2))

			receiver.onReceive(context, intent)

			// setActivityResultFromTransition uses the last event
			verify {
				mockBackend.onTransitionActivityResult(any(), eq(2000L))
			}
		}
	// endregion

	// region onReceive with no results

		@Test
		fun `does nothing when neither result type is present`() {
			val intent: Intent = mockk(relaxed = true)
			every { ActivityRecognitionResult.hasResult(intent) } returns false
			every { ActivityTransitionResult.hasResult(intent) } returns false

			receiver.onReceive(context, intent)

			verify(exactly = 0) { mockRequestManager.onActivityUpdate(any(), any(), any()) }
			verify(exactly = 0) { mockRequestManager.onActivityTransition(any(), any()) }
		}
	// endregion
}
