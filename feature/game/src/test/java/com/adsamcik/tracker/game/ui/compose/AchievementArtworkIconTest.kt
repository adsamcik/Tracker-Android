package com.adsamcik.tracker.game.ui.compose

import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class AchievementArtworkIconTest {
	@Test
	fun `each catalog entry has a unique pictogram signature`() {
		val signatures = AchievementCatalog.definitions.map { definition ->
			achievementPictogramSpec(definition).signature
		}

		signatures.distinct().size shouldBe AchievementCatalog.definitions.size
	}

	@Test
	fun `every catalog metric has a generated pictogram mapping`() {
		val failures = AchievementCatalog.byMetric.keys.mapNotNull { metric ->
			val definition = AchievementCatalog.byMetric(metric).firstOrNull() ?: return@mapNotNull metric.storageKey
			runCatching { achievementPictogramSpec(definition).pictogramRes shouldNotBe 0 }
				.exceptionOrNull()
				?.let { metric.storageKey }
		}

		failures.shouldBeEmpty()
	}
}
