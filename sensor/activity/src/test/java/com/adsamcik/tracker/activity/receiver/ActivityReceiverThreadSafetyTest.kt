package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.ingress.ActivityDurableSelection
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressResult
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.android.EntryPointAccessors
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityReceiverThreadSafetyTest {

	private val receiver = ActivityReceiver()

	private lateinit var mockBackend: GmsActivityRecognitionBackend
	private lateinit var mockIngress: ActivityRecognitionEventIngress

	@Before
	fun setUp() {
		mockkStatic(ActivityRecognitionResult::class)
		mockkStatic(ActivityTransitionResult::class)
		mockkObject(Time)

		// Mock the Hilt EntryPoint
		mockBackend = mockk(relaxed = true)
		mockIngress = mockk()
		coEvery { mockIngress.admit(any()) } returns ActivityIngressResult.durable(
			1,
			0,
			ActivityDurableSelection(recognitionIndexes = setOf(0)),
		)
		every { mockBackend.lastActivity } returns RecognizedActivity(DetectedActivityType.UNKNOWN, 0)
		every { mockBackend.lastActivityElapsedTimeMillis } returns 0L
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

	@Test
	fun `concurrent onReceive calls do not throw`() {
		val mockContext = mockk<Context>(relaxed = true)
		every { mockContext.applicationContext } returns mockContext

		val gmsActivity = mockk<com.google.android.gms.location.DetectedActivity> {
			every { type } returns com.google.android.gms.location.DetectedActivity.WALKING
			every { confidence } returns 85
		}
		val result = mockk<ActivityRecognitionResult> {
			every { mostProbableActivity } returns gmsActivity
			every { elapsedRealtimeMillis } returns 1L
		}

		every { ActivityRecognitionResult.hasResult(any()) } returns true
		every { ActivityRecognitionResult.extractResult(any()) } returns result
		every { ActivityTransitionResult.hasResult(any()) } returns false

		val timeCounter = AtomicLong(0)
		every { Time.elapsedRealtimeMillis } answers { timeCounter.incrementAndGet() }

		val threadCount = 10
		val iterationsPerThread = 100
		val barrier = CyclicBarrier(threadCount)
		val latch = CountDownLatch(threadCount)
		val errors = ConcurrentLinkedQueue<Throwable>()

		repeat(threadCount) {
			Thread {
				try {
					barrier.await(5, TimeUnit.SECONDS)
					repeat(iterationsPerThread) {
						receiver.onReceive(mockContext, mockk(relaxed = true))
					}
				} catch (e: Throwable) {
					errors.add(e)
				} finally {
					latch.countDown()
				}
			}.start()
		}

		latch.await(30, TimeUnit.SECONDS) shouldBe true
		errors.toList().shouldBeEmpty()

		// Verify the backend received activity updates
		verify(atLeast = 1) { mockBackend.onActivityResult(any(), any()) }
	}
}
