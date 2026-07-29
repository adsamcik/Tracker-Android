package com.adsamcik.tracker.app.startup

import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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
