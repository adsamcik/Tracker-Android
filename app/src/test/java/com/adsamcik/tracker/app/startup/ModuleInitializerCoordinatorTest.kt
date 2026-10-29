package com.adsamcik.tracker.app.startup

import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Provider

class ModuleInitializerCoordinatorTest {

	@Test
	fun `runs initializers in ascending priority order`() {
		val calls = mutableListOf<String>()
		val coordinator = ModuleInitializerCoordinator(
			FixedProvider(
				setOf(
					FakeInitializer("points", 40, calls),
					FakeInitializer("activity", 10, calls),
					FakeInitializer("game", 30, calls),
					FakeInitializer("tracker", 20, calls),
				),
			),
		)

		coordinator.initializeAll()

		calls shouldBe listOf("activity", "tracker", "game", "points")
	}

	@Test
	fun `initializers run at most once`() {
		val calls = mutableListOf<String>()
		val coordinator = ModuleInitializerCoordinator(
			FixedProvider(setOf(FakeInitializer("activity", 10, calls))),
		)

		coordinator.initializeAll()
		coordinator.initializeAll()

		calls shouldBe listOf("activity")
	}

	@Test
	fun `provider failure can be retried`() {
		val calls = mutableListOf<String>()
		var attempts = 0
		val coordinator = ModuleInitializerCoordinator(Provider {
			if (++attempts == 1) {
				error("Storage is not ready")
			}
			setOf(FakeInitializer("activity", 10, calls))
		})

		assertThrows<IllegalStateException> { coordinator.initializeAll() }
		coordinator.initializeAll()
		coordinator.initializeAll()

		attempts shouldBe 2
		calls shouldBe listOf("activity")
	}

	@Test
	fun `retry resumes at failed initializer without duplicating earlier observers`() {
		val calls = mutableListOf<String>()
		var attempts = 0
		val flaky = object : ModuleInitializer {
			override val priority = 20
			override fun initialize() {
				if (++attempts == 1) {
					error("Initialization failed")
				}
				calls += "tracker"
			}
		}
		val coordinator = ModuleInitializerCoordinator(FixedProvider(setOf(
			FakeInitializer("activity", 10, calls), flaky, FakeInitializer("game", 30, calls),
		)))

		assertThrows<IllegalStateException> { coordinator.initializeAll() }
		calls shouldBe listOf("activity")
		coordinator.initializeAll()
		coordinator.initializeAll()

		attempts shouldBe 2
		calls shouldBe listOf("activity", "tracker", "game")
	}

	@Test
	fun `concurrent startup waits until initialization finishes`() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val secondEntered = CountDownLatch(1)
		val initializer = object : ModuleInitializer {
			override fun initialize() {
				entered.countDown()
				check(release.await(5, TimeUnit.SECONDS))
			}
		}
		val coordinator = ModuleInitializerCoordinator(FixedProvider(setOf(initializer)))
		val executor = Executors.newFixedThreadPool(2)
		try {
			val first = executor.submit { coordinator.initializeAll() }
			check(entered.await(5, TimeUnit.SECONDS))
			val second = executor.submit {
				secondEntered.countDown()
				coordinator.initializeAll()
			}
			check(secondEntered.await(5, TimeUnit.SECONDS))
			assertThrows<TimeoutException> { second.get(100, TimeUnit.MILLISECONDS) }
			release.countDown()
			first.get(5, TimeUnit.SECONDS)
			second.get(5, TimeUnit.SECONDS)
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	private data class FakeInitializer(
		private val name: String,
		override val priority: Int,
		private val calls: MutableList<String>,
	) : ModuleInitializer {
		override fun initialize() {
			calls += name
		}
	}

	private class FixedProvider<T>(private val value: T) : Provider<T> {
		override fun get(): T = value
	}
}
