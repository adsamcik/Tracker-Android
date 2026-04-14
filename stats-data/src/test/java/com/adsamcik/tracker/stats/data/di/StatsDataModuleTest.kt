package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.achievement.AchievementEvaluator
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StatsDataModule")
class StatsDataModuleTest {

	@Nested
	@DisplayName("AchievementEvaluator provider")
	inner class AchievementEvaluatorProvider {

		@Test
		fun `provideAchievementEvaluator returns non-null instance`() {
			StatsDataModule.provideAchievementEvaluator().shouldNotBeNull()
		}

		@Test
		fun `provideAchievementEvaluator returns AchievementEvaluator type`() {
			StatsDataModule.provideAchievementEvaluator()
				.shouldBeInstanceOf<AchievementEvaluator>()
		}

		@Test
		fun `provideAchievementEvaluator creates new instance each call`() {
			val first = StatsDataModule.provideAchievementEvaluator()
			val second = StatsDataModule.provideAchievementEvaluator()
			first shouldNotBeSameInstanceAs second
		}
	}

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
