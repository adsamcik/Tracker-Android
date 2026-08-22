package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_CLOCK_DOMAIN_ID
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_COLLECTED_DATA_EPOCH
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_PHYSICAL_CONFIGURATION_FINGERPRINT
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_REGISTRATION_GENERATION
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend.Companion.EXTRA_SOURCE_INSTANCE_ID
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.ingress.ActivityDurableSelection
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressResult
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.android.EntryPointAccessors
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
	private lateinit var mockIngress: ActivityRecognitionEventIngress

	@Before
	fun setUp() {
		ActivityReceiver.lastActivity = RecognizedActivity.UNKNOWN
		mockkStatic(ActivityRecognitionResult::class)
		mockkStatic(ActivityTransitionResult::class)
		mockkObject(Time)

		every { Time.elapsedRealtimeMillis } returns 5000L

		// Mock the Hilt EntryPoint so the receiver can obtain the backend.
		mockBackend = mockk(relaxed = true)
		mockIngress = mockk()
		coEvery { mockIngress.admit(any()) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(recognitionIndexes = setOf(0)),
		)
		val mockEntryPoint = mockk<ActivityReceiverEntryPoint> {
			every { backend() } returns mockBackend
			every { eventIngress() } returns mockIngress
			every { applicationScope() } returns CoroutineScope(Dispatchers.Unconfined)
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
			every { elapsedRealtimeMillis } returns 5_000L
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
					match { it.type == DetectedActivityType.WALKING && it.confidence == 85 },
					any(),
				)
			}
		}

	@Test
	fun `does not publish process local effects when durable handoff fails`() {
		coEvery { mockIngress.admit(any()) } returns ActivityIngressResult.retryable(
			admittedCount = 0,
			duplicateCount = 0,
			failureCode = "storage_unavailable",
		)
		val intent = intentWithActivityResult(
			com.google.android.gms.location.DetectedActivity.WALKING,
			85,
		)

		receiver.onReceive(context, intent)

		verify(exactly = 0) { mockBackend.onActivityResult(any(), any()) }
	}

	@Test
	fun `durable empty selection publishes nothing`() {
		coEvery { mockIngress.admit(any()) } returns ActivityIngressResult.durable(0, 0)
		val intent = intentWithActivityResult(
			com.google.android.gms.location.DetectedActivity.WALKING,
			85,
		)

		receiver.onReceive(context, intent)

		verify(exactly = 0) { mockBackend.onActivityResult(any(), any()) }
	}

	@Test
	fun `duplicate transition produces no backend or cache effect`() {
		coEvery { mockIngress.admit(any()) } returns ActivityIngressResult.durable(0, 1)
		val prior = RecognizedActivity(DetectedActivityType.STILL, 91)
		ActivityReceiver.lastActivity = prior
		val transition: ActivityTransitionEvent = mockk {
			every { activityType } returns com.google.android.gms.location.DetectedActivity.WALKING
			every { elapsedRealTimeNanos } returns 4_000_000_000L
			every { transitionType } returns
				com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER
		}

		receiver.onReceive(context, intentWithTransitionResult(listOf(transition)))

		verify(exactly = 0) { mockBackend.onTransitionResult(any()) }
		verify(exactly = 0) { mockBackend.onTransitionActivityResult(any(), any()) }
		ActivityReceiver.lastActivity shouldBe prior
	}

	@Test
	fun `cancelled handoff publishes nothing`() {
		coEvery { mockIngress.admit(any()) } throws CancellationException("cancelled")
		val intent = intentWithActivityResult(
			com.google.android.gms.location.DetectedActivity.WALKING,
			85,
		)

		receiver.onReceive(context, intent)

		verify(exactly = 0) { mockBackend.onActivityResult(any(), any()) }
	}

	@Test
	fun `passes only physical registration identity to durable ingress`() {
		val intent = intentWithActivityResult(
			com.google.android.gms.location.DetectedActivity.WALKING,
			85,
		)
		every { intent.getStringExtra(EXTRA_SOURCE_INSTANCE_ID) } returns "activity-instance"
		every { intent.hasExtra(EXTRA_REGISTRATION_GENERATION) } returns true
		every { intent.hasExtra(EXTRA_COLLECTED_DATA_EPOCH) } returns true
		every { intent.getLongExtra(EXTRA_REGISTRATION_GENERATION, any()) } returns 12L
		every { intent.getLongExtra(EXTRA_COLLECTED_DATA_EPOCH, any()) } returns 4L
		every { intent.getStringExtra(EXTRA_CLOCK_DOMAIN_ID) } returns "boot-4"
		every { intent.getStringExtra(EXTRA_PHYSICAL_CONFIGURATION_FINGERPRINT) } returns "physical-config"

		receiver.onReceive(context, intent)

		coVerify {
			mockIngress.admit(match { batch ->
				batch.registrationIdentity?.sourceInstanceId == "activity-instance" &&
					batch.registrationIdentity?.registrationGeneration == 12L &&
					batch.registrationIdentity?.collectedDataEpoch == 4L &&
					batch.registrationIdentity?.clockDomainId == "boot-4" &&
					batch.registrationIdentity?.physicalConfigurationFingerprint == "physical-config"
			})
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

	// endregion

	// region onReceive with transition result

	@Test
	fun `forwards transition to backend`() {
		coEvery { mockIngress.admit(any()) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(transitionIndexes = setOf(0)),
		)
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
					match { it.type == DetectedActivityType.ON_BICYCLE && it.confidence == 100 },
					eq(7777L),
				)
			}
		}

	@Test
	fun `forwards last transition event elapsed time to backend`() {
		coEvery { mockIngress.admit(any()) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(transitionIndexes = setOf(1)),
		)
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
		verify {
			mockBackend.onTransitionResult(match { updates ->
				updates.size == 1 && updates.single().elapsedRealTimeNanos == 2000L
			})
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

			verify(exactly = 0) { mockBackend.onActivityResult(any(), any()) }
			verify(exactly = 0) { mockBackend.onTransitionResult(any()) }
		}
	// endregion
}
