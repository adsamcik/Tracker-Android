package com.adsamcik.tracker.shared.base.startup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ModuleInitializer")
class ModuleInitializerTest {

	@Nested
	@DisplayName("contract")
	inner class Contract {

		@Test
		fun `DEFAULT_PRIORITY is 1000`() {
			ModuleInitializer.DEFAULT_PRIORITY shouldBe 1_000
		}

		@Test
		fun `default priority property returns DEFAULT_PRIORITY`() {
			val initializer = object : ModuleInitializer {
				override fun initialize() { /* no-op */ }
			}
			initializer.priority shouldBe ModuleInitializer.DEFAULT_PRIORITY
		}

		@Test
		fun `custom priority overrides default`() {
			val initializer = object : ModuleInitializer {
				override val priority: Int get() = 500
				override fun initialize() { /* no-op */ }
			}
			initializer.priority shouldBe 500
		}

		@Test
		fun `initialize can be called without error`() {
			var called = false
			val initializer = object : ModuleInitializer {
				override fun initialize() {
					called = true
				}
			}

			initializer.initialize()

			called shouldBe true
		}

		@Test
		fun `implementations are sortable by priority`() {
			val low = object : ModuleInitializer {
				override val priority: Int get() = 100
				override fun initialize() {}
			}
			val mid = object : ModuleInitializer {
				override fun initialize() {}
			}
			val high = object : ModuleInitializer {
				override val priority: Int get() = 5_000
				override fun initialize() {}
			}

			val sorted = listOf(high, low, mid).sortedBy { it.priority }

			sorted[0].priority shouldBe 100
			sorted[1].priority shouldBe 1_000
			sorted[2].priority shouldBe 5_000
		}

		@Test
		fun `zero priority is valid`() {
			val initializer = object : ModuleInitializer {
				override val priority: Int get() = 0
				override fun initialize() {}
			}
			initializer.priority shouldBe 0
		}

		@Test
		fun `negative priority is valid`() {
			val initializer = object : ModuleInitializer {
				override val priority: Int get() = -1
				override fun initialize() {}
			}
			initializer.priority shouldBe -1
		}
	}
}
