package com.adsamcik.tracker.logging.api

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@DisplayName("ReporterFacade")
class ReporterFacadeTest {

	@BeforeEach
	fun resetDelegate() {
		// Use reflection to reset the private delegate field between tests
		val field = ReporterFacade::class.java.getDeclaredField("delegate")
		field.isAccessible = true
		field.set(ReporterFacade, null)
	}

	@Nested
	@DisplayName("when no delegate is set")
	inner class NullDelegate {

		@Test
		fun `report message does not throw`() {
			ReporterFacade.report("ignored")
			// no exception = pass
		}

		@Test
		fun `report exception does not throw`() {
			ReporterFacade.report(RuntimeException("ignored"))
		}

		@Test
		fun `log does not throw`() {
			ReporterFacade.log("ignored")
		}
	}

	@Nested
	@DisplayName("when delegate is set")
	inner class WithDelegate {

		private val delegate = mockk<ErrorReporter>(relaxed = true)

		@BeforeEach
		fun install() {
			ReporterFacade.setDelegate(delegate)
		}

		@Test
		fun `report message forwards to delegate`() {
			ReporterFacade.report("hello")

			verify(exactly = 1) { delegate.report("hello") }
		}

		@Test
		fun `report exception forwards to delegate`() {
			val ex = IllegalStateException("bad state")

			ReporterFacade.report(ex)

			verify(exactly = 1) { delegate.report(ex) }
		}

		@Test
		fun `log forwards to delegate`() {
			ReporterFacade.log("info")

			verify(exactly = 1) { delegate.log("info") }
		}
	}

	@Nested
	@DisplayName("delegate replacement")
	inner class DelegateReplacement {

		@Test
		fun `replacing delegate routes calls to new delegate only`() {
			val first = mockk<ErrorReporter>(relaxed = true)
			val second = mockk<ErrorReporter>(relaxed = true)

			ReporterFacade.setDelegate(first)
			ReporterFacade.report("to-first")

			ReporterFacade.setDelegate(second)
			ReporterFacade.report("to-second")

			verify(exactly = 1) { first.report("to-first") }
			verify(exactly = 0) { first.report("to-second") }
			verify(exactly = 1) { second.report("to-second") }
		}
	}

	@Nested
	@DisplayName("thread safety")
	inner class ThreadSafety {

		@Test
		fun `concurrent calls do not throw`() {
			val delegate = mockk<ErrorReporter>(relaxed = true)
			ReporterFacade.setDelegate(delegate)

			val threadCount = 8
			val latch = CountDownLatch(threadCount)
			val errors = mutableListOf<Throwable>()

			repeat(threadCount) { i ->
				Thread {
					try {
						repeat(100) { j ->
							when (j % 3) {
								0 -> ReporterFacade.report("thread-$i-$j")
								1 -> ReporterFacade.report(RuntimeException("t-$i-$j"))
								2 -> ReporterFacade.log("thread-$i-$j")
							}
						}
					} catch (t: Throwable) {
						synchronized(errors) { errors.add(t) }
					} finally {
						latch.countDown()
					}
				}.start()
			}

			val finished = latch.await(10, TimeUnit.SECONDS)

			finished shouldBe true
			errors.size shouldBe 0
		}

		@Test
		fun `setDelegate during concurrent calls does not throw`() {
			val delegates = List(4) { mockk<ErrorReporter>(relaxed = true) }
			ReporterFacade.setDelegate(delegates[0])

			val threadCount = 8
			val latch = CountDownLatch(threadCount)
			val errors = mutableListOf<Throwable>()

			repeat(threadCount) { i ->
				Thread {
					try {
						repeat(50) { j ->
							if (j % 10 == 0) {
								ReporterFacade.setDelegate(delegates[j % delegates.size])
							}
							ReporterFacade.report("msg-$i-$j")
							ReporterFacade.log("log-$i-$j")
						}
					} catch (t: Throwable) {
						synchronized(errors) { errors.add(t) }
					} finally {
						latch.countDown()
					}
				}.start()
			}

			val finished = latch.await(10, TimeUnit.SECONDS)

			finished shouldBe true
			errors.size shouldBe 0
		}
	}
}
