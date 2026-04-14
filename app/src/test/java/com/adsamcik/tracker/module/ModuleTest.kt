package com.adsamcik.tracker.module

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Module enum")
class ModuleTest {

	@Nested
	@DisplayName("Enum entries")
	inner class EnumEntries {

		@Test
		fun `has exactly three modules`() {
			Module.entries.size shouldBe 3
		}

		@Test
		fun `STATISTICS is present`() {
			Module.valueOf("STATISTICS") shouldBe Module.STATISTICS
		}

		@Test
		fun `GAME is present`() {
			Module.valueOf("GAME") shouldBe Module.GAME
		}

		@Test
		fun `MAP is present`() {
			Module.valueOf("MAP") shouldBe Module.MAP
		}
	}

	@Nested
	@DisplayName("Module names")
	inner class ModuleNames {

		@Test
		fun `STATISTICS moduleName is statistics`() {
			Module.STATISTICS.moduleName shouldBe "statistics"
		}

		@Test
		fun `GAME moduleName is game`() {
			Module.GAME.moduleName shouldBe "game"
		}

		@Test
		fun `MAP moduleName is map`() {
			Module.MAP.moduleName shouldBe "map"
		}
	}

	@Nested
	@DisplayName("Module paths")
	inner class ModulePaths {

		@Test
		fun `modulePath starts with base path`() {
			Module.entries.forEach { module ->
				module.modulePath shouldStartWith "com.adsamcik.tracker."
			}
		}

		@Test
		fun `STATISTICS modulePath is correct`() {
			Module.STATISTICS.modulePath shouldBe "com.adsamcik.tracker.statistics"
		}

		@Test
		fun `GAME modulePath is correct`() {
			Module.GAME.modulePath shouldBe "com.adsamcik.tracker.game"
		}

		@Test
		fun `MAP modulePath is correct`() {
			Module.MAP.modulePath shouldBe "com.adsamcik.tracker.map"
		}

		@Test
		fun `modulePath contains moduleName`() {
			Module.entries.forEach { module ->
				module.modulePath shouldContain module.moduleName
			}
		}
	}

	@Nested
	@DisplayName("Enabled flag")
	inner class EnabledFlag {

		@Test
		fun `all modules are enabled`() {
			Module.entries.forEach { module ->
				module.enabled shouldBe true
			}
		}
	}

	@Nested
	@DisplayName("Title resources")
	inner class TitleResources {

		@Test
		fun `all modules have non-zero titleRes`() {
			Module.entries.forEach { module ->
				(module.titleRes != 0) shouldBe true
			}
		}

		@Test
		fun `all modules have unique titleRes`() {
			val titleResSet = Module.entries.map { it.titleRes }.toSet()
			titleResSet.size shouldBe Module.entries.size
		}
	}
}
