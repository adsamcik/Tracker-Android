package com.adsamcik.tracker.stats.data.di

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StatsDataModule")
class StatsDataModuleTest {

	@Nested
	@DisplayName("Module annotations")
	inner class ModuleAnnotations {

		@Test
		fun `class has Module annotation`() {
			val hasModule = StatsDataModule::class.java.annotations.any {
				it.annotationClass.qualifiedName == "dagger.Module"
			}
			hasModule shouldBe true
		}

		@Test
		fun `class has InstallIn annotation`() {
			// InstallIn has CLASS retention so it's not available via reflection at
			// runtime. Verify it is present in the class-file bytecode instead.
			val classFile = StatsDataModule::class.java
				.getResource("${StatsDataModule::class.java.simpleName}.class")
			val bytes = classFile!!.readBytes()
			val classContent = String(bytes, Charsets.ISO_8859_1)
			val hasInstallIn = classContent.contains("dagger/hilt/InstallIn")
			hasInstallIn shouldBe true
		}
	}
}
