package com.adsamcik.tracker.tracker.module

import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerModuleInitializerTest {
	@Test
	fun `pending automation waits for coherent authority then retries to completion`() = runTest {
		val authorityReady = MutableStateFlow(false)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
			) {
				attempts += 1
				if (attempts == 1) {
					ActivityAutomationDrainResult.Retryable(0, 0)
				} else {
					drainRequired.value = false
					ActivityAutomationDrainResult.Complete(1, 0)
				}
			}
		}

		runCurrent()
		attempts shouldBe 0

		authorityReady.value = true
		runCurrent()
		attempts shouldBe 1

		advanceTimeBy(10)
		runCurrent()
		attempts shouldBe 2
	}

	@Test
	fun `authority closing cancels retry until a coherent revision returns`() = runTest {
		val authorityReady = MutableStateFlow(true)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
			) {
				attempts += 1
				ActivityAutomationDrainResult.Retryable(0, 0)
			}
		}

		runCurrent()
		attempts shouldBe 1
		authorityReady.value = false
		runCurrent()
		advanceTimeBy(100)
		runCurrent()
		attempts shouldBe 1

		authorityReady.value = true
		runCurrent()
		attempts shouldBe 2
	}

	@Test
	fun `bounded backlog continuations yield and drain without retry delay`() = runTest {
		val authorityReady = MutableStateFlow(true)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
			) {
				attempts += 1
				if (attempts < 3) {
					ActivityAutomationDrainResult.MorePending(attempts, 0)
				} else {
					drainRequired.value = false
					ActivityAutomationDrainResult.Complete(attempts, 0)
				}
			}
		}

		runCurrent()

		attempts shouldBe 3
		currentTime shouldBe 0
	}

	@Test
	fun `projection failure waits for a later bounded signal instead of retry spinning`() = runTest {
		val authorityReady = MutableStateFlow(true)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
			) {
				attempts += 1
				if (attempts == 1) {
					ActivityAutomationDrainResult.ProjectionDeferred()
				} else {
					ActivityAutomationDrainResult.Complete(0, 0)
				}
			}
		}

		runCurrent()
		attempts shouldBe 1
		advanceTimeBy(1_000)
		runCurrent()
		attempts shouldBe 1

		drainRequired.value = false
		runCurrent()
		drainRequired.value = true
		runCurrent()
		attempts shouldBe 2
	}
}
