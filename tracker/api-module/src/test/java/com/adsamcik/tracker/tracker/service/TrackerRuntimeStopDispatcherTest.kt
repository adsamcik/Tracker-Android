package com.adsamcik.tracker.tracker.service

import android.os.Looper
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TrackerRuntimeStopDispatcherTest {
	@Test
	fun `background dispatch invokes handler on main before acknowledging ownership`() {
		val owner = Any()
		val handledOnMain = AtomicBoolean(false)
		val fellBack = AtomicBoolean(false)
		val accepted = AtomicBoolean(false)
		val command = stopCommand()
		TrackerRuntimeStopDispatcher.register(owner) {
			handledOnMain.set(Looper.myLooper() == Looper.getMainLooper())
			true
		}

		thread {
			accepted.set(
				TrackerRuntimeStopDispatcher.dispatch(command) { fellBack.set(true) },
			)
		}.join()
		shadowOf(Looper.getMainLooper()).idle()
		TrackerRuntimeStopDispatcher.unregister(owner)

		accepted.get() shouldBe true
		handledOnMain.get() shouldBe true
		fellBack.get() shouldBe false
	}

	@Test
	fun `owner disappearing after background dispatch invokes exact inactive fallback`() {
		val owner = Any()
		val handled = AtomicBoolean(false)
		val fellBack = AtomicBoolean(false)
		val accepted = AtomicBoolean(false)
		val command = stopCommand()
		TrackerRuntimeStopDispatcher.register(owner) {
			handled.set(true)
			true
		}

		thread {
			accepted.set(
				TrackerRuntimeStopDispatcher.dispatch(command) { undelivered ->
					(undelivered == command) shouldBe true
					fellBack.set(true)
				},
			)
		}.join()
		TrackerRuntimeStopDispatcher.unregister(owner)
		shadowOf(Looper.getMainLooper()).idle()

		accepted.get() shouldBe true
		handled.get() shouldBe false
		fellBack.get() shouldBe true
	}

	@Test
	fun `background stop crossing teardown waits for provider gate before inactive fallback`() {
		val owner = Any()
		val handled = AtomicBoolean(false)
		val accepted = AtomicBoolean(false)
		val fallbackCount = AtomicInteger(0)
		val posted = AtomicReference<Runnable>()
		val command = stopCommand()
		TrackerRuntimeStopDispatcher.register(owner) {
			handled.set(true)
			true
		}

		thread {
			accepted.set(
				TrackerRuntimeStopDispatcher.dispatch(
					command = command,
					onUndelivered = { undelivered ->
						(undelivered == command) shouldBe true
						fallbackCount.incrementAndGet()
					},
					postToMain = { runnable ->
						posted.set(runnable)
						true
					},
				),
			)
		}.join()

		TrackerRuntimeStopDispatcher.beginTeardown(owner) shouldBe true
		posted.get().run()

		accepted.get() shouldBe true
		handled.get() shouldBe false
		fallbackCount.get() shouldBe 0

		TrackerRuntimeStopDispatcher.completeTeardown(owner) shouldBe true
		fallbackCount.get() shouldBe 1
		TrackerRuntimeStopDispatcher.dispatch(command) { fallbackCount.incrementAndGet() } shouldBe false
		fallbackCount.get() shouldBe 1
	}

	@Test
	fun `rejected main looper post reports delivery rejection to the caller`() {
		val owner = Any()
		val handled = AtomicBoolean(false)
		val fellBack = AtomicBoolean(false)
		val accepted = AtomicBoolean(true)
		val command = stopCommand()
		TrackerRuntimeStopDispatcher.register(owner) {
			handled.set(true)
			true
		}

		thread {
			accepted.set(
				TrackerRuntimeStopDispatcher.dispatch(
					command = command,
					onUndelivered = { fellBack.set(true) },
					postToMain = { false },
				),
			)
		}.join()
		TrackerRuntimeStopDispatcher.unregister(owner)

		accepted.get() shouldBe false
		handled.get() shouldBe false
		// The false return delegates exact inactive fallback to routeDurableStop.
		fellBack.get() shouldBe false
	}

	private fun stopCommand() = TrackingStopCommand(
		generation = 2L,
		reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		requestedAtEpochMs = 1_000L,
	)
}
