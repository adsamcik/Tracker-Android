package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.android.EntryPointAccessors
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@DisplayName("ActivityReceiver Thread Safety")
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class ActivityReceiverThreadSafetyTest {

	private val receiver = ActivityReceiver()

	private lateinit var mockBackend: GmsActivityRecognitionBackend

	@BeforeEach
	fun setUp() {
		mockkStatic(ActivityRecognitionResult::class)
		mockkStatic(ActivityTransitionResult::class)
		mockkObject(ActivityRequestManager)
		mockkObject(Logger)
		mockkObject(Time)

		every { Logger.logWithPreference(any(), any(), any()) } just runs

		// Mock the Hilt EntryPoint
		mockBackend = mockk(relaxed = true)
		every { mockBackend.lastActivity } returns ActivityInfo(DetectedActivity.UNKNOWN, 0)
		every { mockBackend.lastActivityElapsedTimeMillis } returns 0L

		val mockEntryPoint = mockk<ActivityReceiverEntryPoint> {
			every { backend() } returns mockBackend
		}
		mockkStatic(EntryPointAccessors::class)
		every {
			EntryPointAccessors.fromApplication(any(), ActivityReceiverEntryPoint::class.java)
		} returns mockEntryPoint
	}

	@AfterEach
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
		}

		every { ActivityRecognitionResult.hasResult(any()) } returns true
		every { ActivityRecognitionResult.extractResult(any()) } returns result
		every { ActivityTransitionResult.hasResult(any()) } returns false
		every { ActivityRequestManager.onActivityUpdate(any(), any(), any()) } just runs

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
