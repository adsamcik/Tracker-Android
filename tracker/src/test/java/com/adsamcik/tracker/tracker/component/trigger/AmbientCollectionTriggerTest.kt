package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [AmbientCollectionTrigger].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AmbientCollectionTriggerTest {

	private lateinit var context: Context
	private lateinit var receiver: TrackerTimerReceiver
	private lateinit var trigger: AmbientCollectionTrigger

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
		receiver = mockk(relaxed = true)
		trigger = AmbientCollectionTrigger()
	}

	@Test
	fun `requiredPermissions is empty`() {
		assertTrue(trigger.requiredPermissions.isEmpty())
	}

	@Test
	fun `onEnable fires callback after default 60 second interval`() {
		trigger.onEnable(context, receiver)

		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
		verify(exactly = 0) { receiver.onUpdate(any()) }

		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
		verify(atLeast = 1) { receiver.onUpdate(any()) }
	}

	@Test
	fun `updateInterval changes firing rate`() {
		trigger.onEnable(context, receiver)
		shadowOf(Looper.getMainLooper()).idle()

		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 0)
		shadowOf(Looper.getMainLooper()).idle()

		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
		verify(atLeast = 1) { receiver.onUpdate(any()) }
	}

	@Test
	fun `onDisable stops handler callbacks`() {
		trigger.onEnable(context, receiver)
		shadowOf(Looper.getMainLooper()).idle()

		trigger.onDisable(context)

		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
		verify(exactly = 0) { receiver.onUpdate(any()) }
	}

	@Test
	fun `handler posts callbacks repeatedly at configured interval`() {
		trigger.onEnable(context, receiver)
		trigger.updateInterval(context, intervalSeconds = 10, minDistanceMeters = 0)
		shadowOf(Looper.getMainLooper()).idle()

		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
		verify(atLeast = 2) { receiver.onUpdate(any()) }
	}

	@Test
	fun `callback provides valid temp data with timestamp`() {
		trigger.onEnable(context, receiver)
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))

		val dataSlot = slot<TrackingCycle>()
		verify(atLeast = 1) { receiver.onUpdate(capture(dataSlot)) }
		assertTrue(dataSlot.captured.timestampMs > 0)
	}

	@Test
	fun `default interval constant is 60 seconds`() {
		assertEquals(60, AmbientCollectionTrigger.DEFAULT_INTERVAL_SECONDS)
	}
}
