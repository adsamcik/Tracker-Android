package com.adsamcik.tracker.tracker.module

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.adsamcik.tracker.tracker.worker.AmbientStepsImportWorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AmbientStepsForegroundImportTest {
	@Test
	fun `opening and reopening request bounded copies even after an enqueue failure`() = runTest {
		val lifecycle = mockk<Lifecycle>()
		val observer = slot<DefaultLifecycleObserver>()
		every { lifecycle.addObserver(capture(observer)) } returns Unit
		val scheduler = mockk<AmbientStepsImportWorkScheduler>()
		coEvery { scheduler.requestNow() } throws IllegalStateException("temporarily unavailable") andThen Unit
		val owner = mockk<LifecycleOwner>()
		registerTrackingForegroundWork(lifecycle, this, scheduler)

		observer.captured.onStart(owner)
		runCurrent()
		observer.captured.onStart(owner)
		runCurrent()

		coVerify(exactly = 2) { scheduler.requestNow() }
		coVerify(exactly = 0) { scheduler.reconcile(any(), any()) }
	}

	@Test
	fun `foreground session repair retries independently of bounded Steps copying`() = runTest {
		val lifecycle = mockk<Lifecycle>()
		every { lifecycle.currentState } returns Lifecycle.State.RESUMED
		val observer = slot<DefaultLifecycleObserver>()
		every { lifecycle.addObserver(capture(observer)) } returns Unit
		val scheduler = mockk<AmbientStepsImportWorkScheduler>()
		coEvery { scheduler.requestNow() } returns Unit
		val owner = mockk<LifecycleOwner>()
		var repairs = 0
		registerTrackingForegroundWork(lifecycle, this, scheduler) {
			repairs++
			if (repairs == 1) error("Temporarily unavailable")
		}
		observer.captured.onStart(owner)
		runCurrent()
		assertEquals(0, repairs)
		observer.captured.onResume(owner)
		observer.captured.onResume(owner)
		assertEquals(2, repairs)
		coVerify(exactly = 1) { scheduler.requestNow() }
		coVerify(exactly = 0) { scheduler.reconcile(any(), any()) }
	}

	@Test
	fun `startup ready repairs the visible session but does not start sources from the background`() {
		val lifecycle = mockk<Lifecycle>()
		var state = Lifecycle.State.CREATED
		every { lifecycle.currentState } answers { state }
		var requests = 0
		reconcileForegroundSession(lifecycle) { requests++ }
		assertEquals(0, requests)
		state = Lifecycle.State.RESUMED
		reconcileForegroundSession(lifecycle) { requests++ }
		assertEquals(1, requests)
	}
}
