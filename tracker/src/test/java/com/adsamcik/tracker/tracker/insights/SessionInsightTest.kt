package com.adsamcik.tracker.tracker.insights

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@DisplayName("InsightCategory")
class InsightCategoryTest {

	@Nested
	@DisplayName("Enum completeness")
	inner class Completeness {

		@Test
		fun `has exactly 4 values`() {
			InsightCategory.entries.size shouldBe 4
		}

		@Test
		fun `contains all expected values`() {
			InsightCategory.entries shouldContainAll listOf(
				InsightCategory.ACHIEVEMENT,
				InsightCategory.FUN_FACT,
				InsightCategory.COMPARISON,
				InsightCategory.EXPLORATION,
			)
		}

		@ParameterizedTest
		@EnumSource(InsightCategory::class)
		fun `valueOf round-trip works`(category: InsightCategory) {
			InsightCategory.valueOf(category.name) shouldBe category
		}
	}
}

@DisplayName("SessionInsight")
class SessionInsightTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `all fields are preserved`() {
			val insight = SessionInsight(
				category = InsightCategory.ACHIEVEMENT,
				title = "Great job!",
				description = "You walked 10,000 steps",
				iconRes = 42,
			)
			insight.category shouldBe InsightCategory.ACHIEVEMENT
			insight.title shouldBe "Great job!"
			insight.description shouldBe "You walked 10,000 steps"
			insight.iconRes shouldBe 42
		}
	}

	@Nested
	@DisplayName("Data class equality")
	inner class Equality {

		@Test
		fun `two insights with same data are equal`() {
			val a = SessionInsight(InsightCategory.FUN_FACT, "T", "D", 1)
			val b = SessionInsight(InsightCategory.FUN_FACT, "T", "D", 1)
			a shouldBe b
		}

		@Test
		fun `insights with different category are not equal`() {
			val a = SessionInsight(InsightCategory.FUN_FACT, "T", "D", 1)
			val b = SessionInsight(InsightCategory.COMPARISON, "T", "D", 1)
			a shouldNotBe b
		}

		@Test
		fun `insights with different title are not equal`() {
			val a = SessionInsight(InsightCategory.FUN_FACT, "Title A", "D", 1)
			val b = SessionInsight(InsightCategory.FUN_FACT, "Title B", "D", 1)
			a shouldNotBe b
		}
	}

	@Nested
	@DisplayName("Copy")
	inner class Copy {

		@Test
		fun `copy with modified category preserves other fields`() {
			val original = SessionInsight(InsightCategory.ACHIEVEMENT, "T", "D", 1)
			val copied = original.copy(category = InsightCategory.EXPLORATION)
			copied.category shouldBe InsightCategory.EXPLORATION
			copied.title shouldBe "T"
			copied.description shouldBe "D"
			copied.iconRes shouldBe 1
		}
	}
}
