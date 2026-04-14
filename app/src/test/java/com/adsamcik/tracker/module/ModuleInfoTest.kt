package com.adsamcik.tracker.module

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ModuleInfo")
class ModuleInfoTest {

	@Nested
	@DisplayName("Data class properties")
	inner class Properties {

		@Test
		fun `stores module correctly`() {
			val info = ModuleInfo(module = Module.STATISTICS)
			info.module shouldBe Module.STATISTICS
		}

		@Test
		fun `shouldBeInstalled defaults to false`() {
			val info = ModuleInfo(module = Module.GAME)
			info.shouldBeInstalled shouldBe false
		}

		@Test
		fun `isInstalled defaults to false`() {
			val info = ModuleInfo(module = Module.MAP)
			info.isInstalled shouldBe false
		}

		@Test
		fun `stores shouldBeInstalled when set true`() {
			val info = ModuleInfo(module = Module.STATISTICS, shouldBeInstalled = true)
			info.shouldBeInstalled shouldBe true
		}

		@Test
		fun `stores isInstalled when set true`() {
			val info = ModuleInfo(module = Module.GAME, isInstalled = true)
			info.isInstalled shouldBe true
		}
	}

	@Nested
	@DisplayName("Mutable fields")
	inner class MutableFields {

		@Test
		fun `shouldBeInstalled is mutable`() {
			val info = ModuleInfo(module = Module.STATISTICS)
			info.shouldBeInstalled = true
			info.shouldBeInstalled shouldBe true
		}

		@Test
		fun `isInstalled is mutable`() {
			val info = ModuleInfo(module = Module.MAP)
			info.isInstalled = true
			info.isInstalled shouldBe true
		}
	}

	@Nested
	@DisplayName("Data class equality")
	inner class Equality {

		@Test
		fun `equal instances are equal`() {
			val a = ModuleInfo(Module.STATISTICS, shouldBeInstalled = true, isInstalled = false)
			val b = ModuleInfo(Module.STATISTICS, shouldBeInstalled = true, isInstalled = false)
			a shouldBe b
		}

		@Test
		fun `different module produces inequality`() {
			val a = ModuleInfo(Module.STATISTICS)
			val b = ModuleInfo(Module.GAME)
			a shouldNotBe b
		}

		@Test
		fun `different shouldBeInstalled produces inequality`() {
			val a = ModuleInfo(Module.MAP, shouldBeInstalled = true)
			val b = ModuleInfo(Module.MAP, shouldBeInstalled = false)
			a shouldNotBe b
		}

		@Test
		fun `different isInstalled produces inequality`() {
			val a = ModuleInfo(Module.MAP, isInstalled = true)
			val b = ModuleInfo(Module.MAP, isInstalled = false)
			a shouldNotBe b
		}
	}

	@Nested
	@DisplayName("Data class copy")
	inner class Copy {

		@Test
		fun `copy preserves all fields`() {
			val original = ModuleInfo(Module.GAME, shouldBeInstalled = true, isInstalled = true)
			val copy = original.copy()
			copy shouldBe original
		}

		@Test
		fun `copy can override module`() {
			val original = ModuleInfo(Module.STATISTICS)
			val modified = original.copy(module = Module.MAP)
			modified.module shouldBe Module.MAP
		}
	}

	@Nested
	@DisplayName("hashCode and toString")
	inner class HashCodeToString {

		@Test
		fun `equal objects have same hashCode`() {
			val a = ModuleInfo(Module.STATISTICS)
			val b = ModuleInfo(Module.STATISTICS)
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `toString contains module name`() {
			val info = ModuleInfo(Module.GAME)
			info.toString().contains("GAME") shouldBe true
		}
	}
}
