package com.adsamcik.tracker.logger

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.shared.preferences.Preferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the Logger init/shutdown race fixed in R3 round 5.
 *
 * Two scenarios are covered:
 *  1. [InitThenShutdown.shutdownDuringInitLeavesStateReset] — a `shutdown()` issued
 *     while the init wrapper is still queued must guarantee the wrapper cannot
 *     repopulate state after [shutdown] returns.
 *  2. [ConcurrentInit.concurrentInitRunsBodyExactlyOnce] — 16 parallel
 *     [Logger.initialize] calls must execute the init body once and only once
 *     (no duplicate DB or Preferences construction).
 */
@DisplayName("Logger lifecycle races")
class LoggerLifecycleRaceTest {

	private fun setField(name: String, value: Any?) {
		val field = Logger::class.java.getDeclaredField(name)
		field.isAccessible = true
		field.set(Logger, value)
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> getField(name: String): T {
		val field = Logger::class.java.getDeclaredField(name)
		field.isAccessible = true
		return field.get(Logger) as T
	}

	private fun hardResetLogger() {
		setField("isInitialized", false)
		setField("initDeferred", CompletableDeferred<Unit>())
		setField("preferences", null)
		setField("genericDao", null)
		setField("initializationJob", null)
		setField("preferenceCollectionJob", null)
		getField<java.util.concurrent.ConcurrentLinkedQueue<*>>("logBuffer").clear()
	}

	private fun stubInitDependencies() {
		mockkObject(LogDatabase.Companion)
		every { LogDatabase.database(any()) } returns mockk(relaxed = true)
		mockkConstructor(Preferences::class)
		@Suppress("DEPRECATION")
		every {
			anyConstructed<Preferences>().getBoolean(any(), any())
		} returns true
		every {
			anyConstructed<Preferences>().observeBoolean(any(), any())
		} returns emptyFlow()
	}

	@BeforeEach
	fun setUp() {
		mockkStatic(Log::class)
		every { Log.d(any(), any()) } returns 0
		every { Log.e(any(), any()) } returns 0
		hardResetLogger()
	}

	@AfterEach
	fun tearDown() {
		// Best-effort shutdown so any in-flight init job from the test under
		// test is joined and cleaned up before the next test runs.
		runCatching {
			runBlocking { withTimeout(5_000) { Logger.shutdown() } }
		}
		hardResetLogger()
		unmockkAll()
	}

	@Nested
	@DisplayName("init + immediate shutdown")
	inner class InitThenShutdown {

		@Test
		@DisplayName("leaves state fully reset and does not repopulate globals")
		fun shutdownDuringInitLeavesStateReset() = runBlocking {
			stubInitDependencies()
			val ctx = mockk<Context>(relaxed = true)

			// Kick off init; on Dispatchers.Default the wrapper may still be
			// queued or part-way through when we issue shutdown below.
			Logger.initialize(ctx)
			Logger.shutdown()

			// After shutdown returns, state MUST be reset and stay reset even
			// if a stale wrapper somehow keeps running.
			getField<Boolean>("isInitialized") shouldBe false
			getField<Preferences?>("preferences") shouldBe null
			getField<GenericLogDao?>("genericDao") shouldBe null

			// Poll for 500ms watching for a late wrapper repopulating state.
			repeat(50) {
				delay(10)
				val nowInitialized = getField<Boolean>("isInitialized")
				val nowPrefs = getField<Preferences?>("preferences")
				val nowDao = getField<GenericLogDao?>("genericDao")
				check(!nowInitialized) {
					"isInitialized flipped back to true ${10 * it}ms after shutdown — wrapper survived the race"
				}
				check(nowPrefs == null) {
					"preferences was repopulated ${10 * it}ms after shutdown — wrapper survived the race"
				}
				check(nowDao == null) {
					"genericDao was repopulated ${10 * it}ms after shutdown — wrapper survived the race"
				}
			}
		}

		@Test
		@DisplayName("second initialize after shutdown succeeds with fresh state")
		fun reinitAfterShutdownSucceeds() = runBlocking {
			stubInitDependencies()
			val ctx = mockk<Context>(relaxed = true)

			Logger.initialize(ctx)
			Logger.shutdown()

			// Re-initialise; should start cleanly from a fully reset state.
			Logger.initialize(ctx)
			val secondJob = getField<Job?>("initializationJob")
				?: error("re-init did not launch a wrapper job")
			withTimeout(5_000) { secondJob.join() }

			getField<Boolean>("isInitialized") shouldBe true
			(getField<Preferences?>("preferences") != null) shouldBe true
			(getField<GenericLogDao?>("genericDao") != null) shouldBe true
		}
	}

	@Nested
	@DisplayName("concurrent initialize calls")
	inner class ConcurrentInit {

		@Test
		@DisplayName("runs the init body exactly once under 16-way contention")
		fun concurrentInitRunsBodyExactlyOnce() = runBlocking {
			stubInitDependencies()
			val ctx = mockk<Context>(relaxed = true)

			val parallelism = 16
			val launchGate = CompletableDeferred<Unit>()

			val callers = (1..parallelism).map {
				async(Dispatchers.Default) {
					launchGate.await()
					Logger.initialize(ctx)
				}
			}
			launchGate.complete(Unit)
			callers.awaitAll()

			// Wait for the wrapper (whichever caller won the race) to finish so
			// we can assert on the post-init state deterministically.
			val initJob = getField<Job?>("initializationJob")
				?: error("no init wrapper was scheduled")
			withTimeout(5_000) { initJob.join() }

			getField<Boolean>("isInitialized") shouldBe true
			// LogDatabase.database() is the canonical side-effect of the init
			// body; verify it ran exactly once across all 16 callers.
			verify(exactly = 1) { LogDatabase.database(any()) }
		}

		@Test
		@DisplayName("post-init calls short-circuit on the volatile flag")
		fun postInitCallsShortCircuit() = runBlocking {
			stubInitDependencies()
			val ctx = mockk<Context>(relaxed = true)

			Logger.initialize(ctx)
			val initJob = getField<Job?>("initializationJob")
				?: error("initial init wrapper not scheduled")
			withTimeout(5_000) { initJob.join() }

			// Already initialised — these 5 calls should hit the fast
			// `isInitialized` short-circuit and never invoke the body.
			repeat(5) { Logger.initialize(ctx) }

			verify(exactly = 1) { LogDatabase.database(any()) }
		}
	}
}
