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
		registerAmbientStepsForegroundImports(lifecycle, this, scheduler)

		observer.captured.onStart(owner)
		runCurrent()
		observer.captured.onStart(owner)
		runCurrent()

		coVerify(exactly = 2) { scheduler.requestNow() }
		coVerify(exactly = 0) { scheduler.reconcile(any(), any()) }
	}
}
